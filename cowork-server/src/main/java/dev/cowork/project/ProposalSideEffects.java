package dev.cowork.project;

import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.orchestration.ConversationOrchestrator;
import dev.cowork.proposal.Proposal;
import dev.cowork.proposal.ProposalDecidedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Applies the concrete side effects of passed proposals. */
@Component
public class ProposalSideEffects {

    private static final Logger log = LoggerFactory.getLogger(ProposalSideEffects.class);

    private final ProjectService projectService;
    private final ProjectTaskRepository tasks;
    private final ParticipantRepository participants;
    private final ConversationRepository conversations;
    private final MessageService messages;
    private final ConversationOrchestrator orchestrator;

    public ProposalSideEffects(ProjectService projectService, ProjectTaskRepository tasks,
                               ParticipantRepository participants, ConversationRepository conversations,
                               MessageService messages, ConversationOrchestrator orchestrator) {
        this.projectService = projectService;
        this.tasks = tasks;
        this.participants = participants;
        this.conversations = conversations;
        this.messages = messages;
        this.orchestrator = orchestrator;
    }

    @Async
    @EventListener
    public void onDecided(ProposalDecidedEvent event) {
        Proposal proposal = event.proposal();
        if (proposal.getStatus() != Proposal.Status.PASSED) {
            return;
        }
        Conversation conversation = conversations.findById(proposal.getConversationId()).orElse(null);
        if (conversation == null) {
            return;
        }
        switch (proposal.getType()) {
            case PLAN_APPROVAL -> messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "The agents approved the plan. The user can now confirm the switch to IMPLEMENTATION "
                            + "from the phase banner.", proposal.getId());
            case TASK_ASSIGNMENT -> applyTaskAssignment(conversation, proposal);
            case CODE_CHANGE -> triggerImplementer(conversation, proposal);
        }
    }

    private void applyTaskAssignment(Conversation conversation, Proposal proposal) {
        if (proposal.getTaskId() != null) {
            try {
                projectService.updateTaskStatus(proposal.getTaskId(), ProjectTask.Status.APPROVED,
                        proposal.getAssigneeAgentId());
            } catch (IllegalArgumentException e) {
                log.warn("TASK_ASSIGNMENT side effect skipped: {}", e.getMessage());
            }
        }
        triggerImplementer(conversation, proposal);
    }

    /** After a passed TASK_ASSIGNMENT/CODE_CHANGE, kick off the responsible agent (implementation phase only). */
    private void triggerImplementer(Conversation conversation, Proposal proposal) {
        if (conversation.getPhase() != Conversation.Phase.IMPLEMENTATION) {
            return;
        }
        UUID agentId = proposal.getAssigneeAgentId();
        if (agentId == null && proposal.getTaskId() != null) {
            agentId = tasks.findById(proposal.getTaskId())
                    .map(ProjectTask::getAssigneeAgentId)
                    .orElse(null);
        }
        Participant target = null;
        if (agentId != null) {
            UUID finalAgentId = agentId;
            target = participants.findByConversationIdAndActiveTrue(conversation.getId()).stream()
                    .filter(p -> finalAgentId.equals(p.getAgentId()))
                    .findFirst()
                    .orElse(null);
        }
        if (target == null) {
            target = participants.findById(proposal.getProposerParticipantId()).orElse(null);
        }
        if (target != null && target.getKind() == Participant.Kind.AGENT && target.isActive()) {
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "@" + target.getDisplayName() + " please implement the approved proposal \""
                            + proposal.getTitle() + "\".", proposal.getId());
            orchestrator.triggerTurn(conversation.getId(), target.getId());
        }
    }
}
