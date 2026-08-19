package dev.cowork.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.agent.AgentDef;
import dev.cowork.agent.AgentDefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    public static final String USER_NAME = "user";

    private final ConversationRepository conversations;
    private final ParticipantRepository participants;
    private final AgentDefRepository agents;
    private final dev.cowork.message.MessageRepository messages;

    public ConversationService(ConversationRepository conversations, ParticipantRepository participants,
                               AgentDefRepository agents, dev.cowork.message.MessageRepository messages) {
        this.conversations = conversations;
        this.participants = participants;
        this.agents = agents;
        this.messages = messages;
    }

    @Transactional
    public Conversation create(String title, Conversation.VoteMode voteMode, boolean userVotes,
                               Integer maxAgentRounds, List<UUID> agentIds) {
        Conversation conversation = new Conversation();
        conversation.setTitle(title);
        conversation.setVoteMode(voteMode == null ? Conversation.VoteMode.MAJORITY : voteMode);
        conversation.setUserVotes(userVotes);
        if (maxAgentRounds != null && maxAgentRounds >= 0) {
            conversation.setMaxAgentRounds(maxAgentRounds);
        }
        conversation.setCreatedAt(Instant.now());
        conversation = conversations.save(conversation);

        Participant user = new Participant();
        user.setConversationId(conversation.getId());
        user.setKind(Participant.Kind.USER);
        user.setDisplayName(USER_NAME);
        user.setJoinedAt(Instant.now());
        participants.save(user);

        for (UUID agentId : agentIds == null ? List.<UUID>of() : agentIds) {
            addAgent(conversation.getId(), agentId);
        }
        return conversation;
    }

    @Transactional
    public Participant addAgent(UUID conversationId, UUID agentId) {
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent " + agentId));
        Participant existing = participants.findByConversationId(conversationId).stream()
                .filter(p -> agent.getId().equals(p.getAgentId()))
                .findFirst().orElse(null);
        if (existing != null) {
            // Re-adding a removed agent reactivates it; its delta cursor catches it up.
            if (!existing.isActive()) {
                existing.setActive(true);
                participants.save(existing);
            }
            return existing;
        }
        Participant participant = new Participant();
        participant.setConversationId(conversationId);
        participant.setKind(Participant.Kind.AGENT);
        participant.setAgentId(agent.getId());
        participant.setDisplayName(agent.getName());
        participant.setJoinedAt(Instant.now());
        return participants.save(participant);
    }

    public Conversation get(UUID id) {
        return conversations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown conversation " + id));
    }

    public List<Conversation> list(Conversation.Status status) {
        return conversations.findByStatusOrderByCreatedAtDesc(
                status == null ? Conversation.Status.ACTIVE : status);
    }

    public List<Participant> participantsOf(UUID conversationId) {
        return participants.findByConversationId(conversationId);
    }

    public Participant userParticipant(UUID conversationId) {
        return participants.findByConversationIdAndDisplayName(conversationId, USER_NAME)
                .orElseThrow(() -> new IllegalStateException("Conversation has no user participant"));
    }

    @Transactional
    public Conversation updateSettings(UUID id, Conversation.VoteMode voteMode, Boolean userVotes,
                                       Integer maxAgentRounds, Conversation.Status status, Double budgetUsd,
                                       Boolean paused, Integer pasteThreshold, Boolean autoContinue) {
        Conversation conversation = get(id);
        if (voteMode != null) {
            conversation.setVoteMode(voteMode);
        }
        if (userVotes != null) {
            conversation.setUserVotes(userVotes);
        }
        if (maxAgentRounds != null && maxAgentRounds >= 0) {
            conversation.setMaxAgentRounds(maxAgentRounds);
        }
        if (status != null) {
            conversation.setStatus(status);
        }
        if (budgetUsd != null) {
            conversation.setBudgetUsd(budgetUsd <= 0 ? null : budgetUsd);
        }
        if (paused != null) {
            conversation.setPaused(paused);
        }
        if (pasteThreshold != null && pasteThreshold >= 0) {
            conversation.setPasteThreshold(pasteThreshold);
        }
        if (autoContinue != null) {
            conversation.setAutoContinue(autoContinue);
        }
        return conversations.save(conversation);
    }

    /**
     * Permanently deletes an ARCHIVED conversation: its messages, participants,
     * proposals, and votes. The project workspace on disk (and any code the agents
     * wrote) is deliberately left untouched.
     */
    @Transactional
    public void deletePermanently(UUID id) {
        Conversation conversation = get(id);
        if (conversation.getStatus() != Conversation.Status.ARCHIVED) {
            throw new IllegalStateException("Only archived conversations can be permanently deleted");
        }
        messages.deleteByConversation(id);
        conversations.deleteById(id); // participants/proposals/votes cascade via FKs
    }

    @Transactional
    public Participant setParticipantActive(UUID conversationId, UUID participantId, boolean active) {
        Participant participant = requireAgentParticipant(conversationId, participantId);
        participant.setActive(active);
        // A manual toggle overrides the phase scope until the next phase change.
        participant.setPhaseOverride(true);
        return participants.save(participant);
    }

    /** Sets a participant's phase scope, clears any manual override, and applies it now. */
    @Transactional
    public Participant setParticipantPhases(UUID conversationId, UUID participantId,
                                            Participant.ActivePhases phases) {
        Participant participant = requireAgentParticipant(conversationId, participantId);
        Conversation conversation = get(conversationId);
        participant.setActivePhases(phases);
        participant.setPhaseOverride(false);
        participant.setActive(phases.appliesTo(conversation.getPhase()));
        return participants.save(participant);
    }

    /**
     * On a phase switch: clears manual overrides and applies each agent's phase scope.
     * Returns the participants whose active flag actually changed.
     */
    @Transactional
    public List<Participant> applyPhaseScoping(UUID conversationId, Conversation.Phase newPhase) {
        List<Participant> changed = new java.util.ArrayList<>();
        for (Participant participant : participants.findByConversationId(conversationId)) {
            if (participant.getKind() != Participant.Kind.AGENT) {
                continue;
            }
            boolean target = participant.getActivePhases().appliesTo(newPhase);
            if (participant.isActive() != target || participant.isPhaseOverride()) {
                boolean activeChanged = participant.isActive() != target;
                participant.setActive(target);
                participant.setPhaseOverride(false);
                participants.save(participant);
                if (activeChanged) {
                    changed.add(participant);
                }
            }
        }
        return changed;
    }

    private Participant requireAgentParticipant(UUID conversationId, UUID participantId) {
        Participant participant = participants.findById(participantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown participant " + participantId));
        if (!participant.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Participant does not belong to conversation");
        }
        if (participant.getKind() != Participant.Kind.AGENT) {
            throw new IllegalArgumentException("Only agent participants can be removed or re-added");
        }
        return participant;
    }
}
