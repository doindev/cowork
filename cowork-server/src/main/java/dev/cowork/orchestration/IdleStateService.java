package dev.cowork.orchestration;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.project.ProjectTask;
import dev.cowork.project.ProjectTaskRepository;
import dev.cowork.proposal.Proposal;
import dev.cowork.proposal.ProposalRepository;
import dev.cowork.proposal.Vote;
import dev.cowork.proposal.VoteRepository;
import org.springframework.stereotype.Component;

/**
 * Deterministic classification of why a conversation's agent chain is (or should be)
 * paused: votes outstanding, a decision only the user can make, or the phase goal
 * simply not reached yet. Pure DB reads — no LLM involvement.
 */
@Component
public class IdleStateService {

    public enum Reason {
        /** An open proposal is missing votes from one or more agents — nudge them. */
        AWAITING_AGENT_VOTES,
        /** An open proposal only lacks the user's vote — wait for the user. */
        AWAITING_USER_VOTE,
        /** A proposal is deadlocked (NEEDS_USER) — the user must decide. */
        DEADLOCKED,
        /** The plan passed; the user must confirm the phase switch. */
        PHASE_CONFIRM,
        /** No user action pending, but the phase goal is not reached. */
        GOAL_INCOMPLETE,
        /** Nothing outstanding — being idle is correct. */
        COMPLETE
    }

    public record Classification(Reason reason, String detail, List<Participant> agentsToNudge) {

        static Classification of(Reason reason, String detail) {
            return new Classification(reason, detail, List.of());
        }
    }

    private final ProposalRepository proposals;
    private final VoteRepository votes;
    private final ParticipantRepository participants;
    private final ProjectTaskRepository tasks;

    public IdleStateService(ProposalRepository proposals, VoteRepository votes,
                            ParticipantRepository participants, ProjectTaskRepository tasks) {
        this.proposals = proposals;
        this.votes = votes;
        this.participants = participants;
        this.tasks = tasks;
    }

    public Classification classify(Conversation conversation) {
        UUID conversationId = conversation.getId();
        List<Participant> active = participants.findByConversationIdAndActiveTrue(conversationId);
        List<Participant> agents = active.stream()
                .filter(p -> p.getKind() == Participant.Kind.AGENT)
                .toList();

        for (Proposal proposal : proposals.findByConversationIdAndStatusOrderByCreatedAtDesc(
                conversationId, Proposal.Status.OPEN)) {
            Set<UUID> voted = votes.findByProposalId(proposal.getId()).stream()
                    .map(Vote::getVoterParticipantId)
                    .collect(Collectors.toSet());
            List<Participant> missingAgents = agents.stream()
                    .filter(p -> !voted.contains(p.getId()))
                    .toList();
            if (!missingAgents.isEmpty()) {
                return new Classification(Reason.AWAITING_AGENT_VOTES,
                        "\"" + proposal.getTitle() + "\"", missingAgents);
            }
            if (conversation.isUserVotes()) {
                boolean userVoted = active.stream()
                        .filter(p -> p.getKind() == Participant.Kind.USER)
                        .anyMatch(p -> voted.contains(p.getId()));
                if (!userVoted) {
                    return Classification.of(Reason.AWAITING_USER_VOTE, "\"" + proposal.getTitle() + "\"");
                }
            }
        }

        List<Proposal> deadlocked = proposals.findByConversationIdAndStatusOrderByCreatedAtDesc(
                conversationId, Proposal.Status.NEEDS_USER);
        if (!deadlocked.isEmpty()) {
            return Classification.of(Reason.DEADLOCKED, "\"" + deadlocked.getFirst().getTitle() + "\"");
        }

        if (conversation.getPhase() == Conversation.Phase.PLANNING) {
            boolean planApproved = proposals.findByConversationIdAndStatusOrderByCreatedAtDesc(
                            conversationId, Proposal.Status.PASSED).stream()
                    .anyMatch(p -> p.getType() == Proposal.Type.PLAN_APPROVAL);
            if (planApproved) {
                return Classification.of(Reason.PHASE_CONFIRM,
                        "the plan was approved — the user confirms the switch to implementation");
            }
            return Classification.of(Reason.GOAL_INCOMPLETE, "the plan has not been approved yet");
        }

        // IMPLEMENTATION: open tasks mean the goal is not reached.
        if (conversation.getProjectId() != null) {
            List<ProjectTask> all = tasks.findByProjectIdOrderByOrdinal(conversation.getProjectId());
            long open = all.stream().filter(t -> t.getStatus() != ProjectTask.Status.DONE).count();
            if (open > 0) {
                return Classification.of(Reason.GOAL_INCOMPLETE,
                        open + " of " + all.size() + " tasks are not done yet");
            }
        }
        return Classification.of(Reason.COMPLETE, null);
    }
}
