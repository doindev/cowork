package dev.cowork.proposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.stream.SseHub;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposal lifecycle and vote tallying. Tallies run inside a transaction holding a row
 * lock on the proposal, so concurrent votes cannot double-decide.
 */
@Service
public class ProposalService {

    private final ProposalRepository proposals;
    private final VoteRepository votes;
    private final ParticipantRepository participants;
    private final ConversationRepository conversations;
    private final MessageService messages;
    private final SseHub sseHub;
    private final ApplicationEventPublisher events;

    public ProposalService(ProposalRepository proposals, VoteRepository votes,
                           ParticipantRepository participants, ConversationRepository conversations,
                           MessageService messages, SseHub sseHub, ApplicationEventPublisher events) {
        this.proposals = proposals;
        this.votes = votes;
        this.participants = participants;
        this.conversations = conversations;
        this.messages = messages;
        this.sseHub = sseHub;
        this.events = events;
    }

    @Transactional
    public Proposal create(Participant proposer, Proposal.Type type, String title, String body, UUID taskId,
                           UUID assigneeAgentId, String commitHash) {
        Proposal proposal = new Proposal();
        proposal.setConversationId(proposer.getConversationId());
        proposal.setProposerParticipantId(proposer.getId());
        proposal.setType(type);
        proposal.setTitle(title);
        proposal.setBody(body);
        proposal.setTaskId(taskId);
        proposal.setAssigneeAgentId(assigneeAgentId);
        proposal.setCommitHash(commitHash);
        proposal.setCreatedAt(Instant.now());
        proposal = proposals.save(proposal);
        messages.postSystem(proposal.getConversationId(), Message.Kind.PROPOSAL,
                proposer.getDisplayName() + " proposed [" + type + "] \"" + title + "\" — vote with cast_vote("
                        + proposal.getId() + ", YES|NO|ABSTAIN, rationale).", proposal.getId());
        publish(proposal);
        return proposal;
    }

    @Transactional
    public Proposal castVote(UUID proposalId, Participant voter, Vote.Value value, String rationale) {
        Proposal proposal = proposals.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown proposal " + proposalId));
        if (proposal.getStatus() != Proposal.Status.OPEN) {
            throw new IllegalStateException("Proposal is " + proposal.getStatus() + ", voting is closed");
        }
        if (!proposal.getConversationId().equals(voter.getConversationId())) {
            throw new IllegalArgumentException("Voter is not a participant of this conversation");
        }
        Conversation conversation = conversations.findById(proposal.getConversationId()).orElseThrow();
        if (voter.getKind() == Participant.Kind.USER && !conversation.isUserVotes()) {
            throw new IllegalStateException("User voting is disabled for this conversation");
        }

        Vote vote = new Vote();
        vote.setProposalId(proposalId);
        vote.setVoterParticipantId(voter.getId());
        vote.setValue(value);
        vote.setRationale(rationale);
        vote.setCreatedAt(Instant.now());
        try {
            votes.save(vote);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("You have already voted on this proposal");
        }
        messages.postSystem(proposal.getConversationId(), Message.Kind.VOTE,
                voter.getDisplayName() + " voted " + value + " on \"" + proposal.getTitle() + "\""
                        + (rationale == null || rationale.isBlank() ? "" : ": " + rationale), proposalId);

        evaluate(proposal, conversation);
        publish(proposal);
        return proposal;
    }

    /**
     * Re-tallies every OPEN proposal of a conversation. Called when the electorate shrinks
     * (an agent is removed) so a vote that was only waiting on the removed agent can decide
     * instead of hanging forever.
     */
    @Transactional
    public void retallyOpen(UUID conversationId) {
        Conversation conversation = conversations.findById(conversationId).orElseThrow();
        for (Proposal open : proposals.findByConversationIdAndStatusOrderByCreatedAtDesc(
                conversationId, Proposal.Status.OPEN)) {
            Proposal proposal = proposals.findByIdForUpdate(open.getId()).orElse(null);
            if (proposal == null || proposal.getStatus() != Proposal.Status.OPEN) {
                continue;
            }
            evaluate(proposal, conversation);
            publish(proposal);
        }
    }

    private void evaluate(Proposal proposal, Conversation conversation) {
        List<Participant> eligible = participants
                .findByConversationIdAndActiveTrue(proposal.getConversationId()).stream()
                .filter(p -> p.getKind() == Participant.Kind.AGENT
                        || (p.getKind() == Participant.Kind.USER && conversation.isUserVotes()))
                .toList();
        List<Vote> cast = votes.findByProposalId(proposal.getId());
        long yes = cast.stream().filter(v -> v.getValue() == Vote.Value.YES).count();
        long no = cast.stream().filter(v -> v.getValue() == Vote.Value.NO).count();
        long remaining = Math.max(0, eligible.size() - cast.size());
        boolean allVoted = remaining == 0;

        Proposal.Status decision = null;
        if (conversation.getVoteMode() == Conversation.VoteMode.UNANIMOUS) {
            if (no > 0) {
                decision = Proposal.Status.REJECTED;
            } else if (allVoted && yes > 0) {
                decision = Proposal.Status.PASSED;
            } else if (allVoted) {
                decision = Proposal.Status.NEEDS_USER; // everyone abstained
            }
        } else {
            if (yes > no + remaining) {
                decision = Proposal.Status.PASSED;
            } else if (no > yes + remaining) {
                decision = Proposal.Status.REJECTED;
            } else if (allVoted) {
                decision = Proposal.Status.NEEDS_USER; // tie or all abstain
            }
        }
        if (decision != null) {
            decide(proposal, decision, Proposal.DecidedBy.TALLY);
        }
    }

    @Transactional
    public Proposal override(UUID proposalId, boolean pass) {
        Proposal proposal = proposals.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown proposal " + proposalId));
        if (proposal.getStatus() != Proposal.Status.OPEN && proposal.getStatus() != Proposal.Status.NEEDS_USER) {
            throw new IllegalStateException("Proposal is already decided (" + proposal.getStatus() + ")");
        }
        decide(proposal, pass ? Proposal.Status.PASSED : Proposal.Status.REJECTED, Proposal.DecidedBy.USER_OVERRIDE);
        publish(proposal);
        return proposal;
    }

    private void decide(Proposal proposal, Proposal.Status status, Proposal.DecidedBy decidedBy) {
        proposal.setStatus(status);
        proposal.setDecidedAt(Instant.now());
        proposal.setDecidedBy(decidedBy);
        proposals.save(proposal);
        String text = switch (status) {
            case PASSED -> "Proposal \"" + proposal.getTitle() + "\" PASSED (" + decidedBy + ").";
            case REJECTED -> "Proposal \"" + proposal.getTitle() + "\" REJECTED (" + decidedBy + ").";
            case NEEDS_USER -> "Proposal \"" + proposal.getTitle() + "\" is deadlocked — the user must decide.";
            default -> null;
        };
        if (text != null) {
            messages.postSystem(proposal.getConversationId(), Message.Kind.SYSTEM, text, proposal.getId());
        }
        if (status == Proposal.Status.PASSED || status == Proposal.Status.REJECTED) {
            events.publishEvent(new ProposalDecidedEvent(proposal));
        }
    }

    public List<Proposal> byConversation(UUID conversationId, Proposal.Status status) {
        return status == null
                ? proposals.findByConversationIdOrderByCreatedAtDesc(conversationId)
                : proposals.findByConversationIdAndStatusOrderByCreatedAtDesc(conversationId, status);
    }

    public List<Vote> votesOf(UUID proposalId) {
        return votes.findByProposalId(proposalId);
    }

    private void publish(Proposal proposal) {
        sseHub.publish(proposal.getConversationId(), "proposal", ProposalViews.of(proposal, votesOf(proposal.getId()), participants));
    }
}
