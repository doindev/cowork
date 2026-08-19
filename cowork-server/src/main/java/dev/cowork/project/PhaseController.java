package dev.cowork.project;

import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.ConversationService;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.stream.SseHub;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's explicit phase control. Even a PASSED PLAN_APPROVAL only flips the
 * conversation to IMPLEMENTATION when the user confirms here.
 */
@RestController
@RequestMapping("/api/conversations/{id}/phase")
public class PhaseController {

    public record PhaseRequest(@NotNull Conversation.Phase phase) {
    }

    public record PhaseView(String phase, UUID projectId, String workspacePath) {
    }

    private final ConversationService conversationService;
    private final ConversationRepository conversations;
    private final ProjectService projectService;
    private final MessageService messages;
    private final SseHub sseHub;
    private final dev.cowork.conversation.ParticipantRepository participants;
    private final dev.cowork.orchestration.ConversationOrchestrator orchestrator;
    private final dev.cowork.orchestration.ActiveTurnRegistry activeTurns;
    private final dev.cowork.proposal.ProposalService proposals;

    public PhaseController(ConversationService conversationService, ConversationRepository conversations,
                           ProjectService projectService, MessageService messages, SseHub sseHub,
                           dev.cowork.conversation.ParticipantRepository participants,
                           dev.cowork.orchestration.ConversationOrchestrator orchestrator,
                           dev.cowork.orchestration.ActiveTurnRegistry activeTurns,
                           dev.cowork.proposal.ProposalService proposals) {
        this.conversationService = conversationService;
        this.conversations = conversations;
        this.projectService = projectService;
        this.messages = messages;
        this.sseHub = sseHub;
        this.participants = participants;
        this.orchestrator = orchestrator;
        this.activeTurns = activeTurns;
        this.proposals = proposals;
    }

    @PostMapping
    public PhaseView setPhase(@PathVariable UUID id, @RequestBody PhaseRequest request) {
        Conversation conversation = conversationService.get(id);
        boolean enteringImplementation = request.phase() == Conversation.Phase.IMPLEMENTATION
                && conversation.getPhase() != Conversation.Phase.IMPLEMENTATION;
        conversation.setPhase(request.phase());
        String workspacePath = null;
        if (request.phase() == Conversation.Phase.IMPLEMENTATION) {
            Project project = projectService.ensureProject(conversation);
            workspacePath = project.getWorkspacePath();
        }
        conversations.save(conversation);
        messages.postSystem(id, Message.Kind.PHASE,
                "Phase changed to " + request.phase()
                        + (workspacePath == null ? "" : " — agents now work in " + workspacePath), null);
        sseHub.publish(id, "phase", new PhaseView(request.phase().name(), conversation.getProjectId(), workspacePath));
        applyPhaseScoping(id, request.phase());
        if (enteringImplementation) {
            kickOffTaskDecomposition(conversation);
        }
        return new PhaseView(conversation.getPhase().name(), conversation.getProjectId(), workspacePath);
    }

    /** Applies each agent's phase scope (clearing manual overrides) after a phase switch. */
    private void applyPhaseScoping(UUID conversationId, Conversation.Phase newPhase) {
        var changed = conversationService.applyPhaseScoping(conversationId, newPhase);
        boolean anyDeactivated = false;
        for (var participant : changed) {
            if (participant.isActive()) {
                messages.postSystem(conversationId, Message.Kind.SYSTEM,
                        "\"" + participant.getDisplayName() + "\" joined the team for the " + newPhase
                                + " phase (phase scope: " + participant.getActivePhases() + ").", null);
            } else {
                anyDeactivated = true;
                activeTurns.cancel(conversationId, participant.getId());
                messages.postSystem(conversationId, Message.Kind.SYSTEM,
                        "\"" + participant.getDisplayName() + "\" left the team for the " + newPhase
                                + " phase (phase scope: " + participant.getActivePhases()
                                + ") — it no longer takes turns or counts toward votes.", null);
            }
        }
        if (anyDeactivated) {
            proposals.retallyOpen(conversationId);
        }
    }

    /**
     * On entering IMPLEMENTATION, an architect-type agent decomposes the approved plan
     * into small tasks, added in the order they must be implemented.
     */
    private void kickOffTaskDecomposition(Conversation conversation) {
        var activeAgents = participants.findByConversationIdAndActiveTrue(conversation.getId()).stream()
                .filter(p -> p.getKind() == dev.cowork.conversation.Participant.Kind.AGENT)
                .toList();
        if (activeAgents.isEmpty()) {
            return;
        }
        var decomposer = activeAgents.stream()
                .filter(p -> p.getDisplayName().toLowerCase().contains("architect"))
                .findFirst()
                .orElse(activeAgents.getFirst());
        messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                "@" + decomposer.getDisplayName() + " the plan is approved. Decompose the entire approved "
                        + "implementation plan into small, self-contained tasks using create_task, adding them "
                        + "in the exact order they need to be implemented (dependencies first). Give each task "
                        + "a clear title and a description precise enough for any agent to implement. When the "
                        + "list is complete, raise a TASK_ASSIGNMENT proposal for the first task.", null);
        orchestrator.triggerTurn(conversation.getId(), decomposer.getId());
    }
}
