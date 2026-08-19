package dev.cowork.orchestration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Tracks running CLI turns per conversation so the user can cancel them. */
@Component
public class ActiveTurnRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveTurnRegistry.class);

    public record ActiveTurn(UUID participantId, String agentName, Process process, int round) {
    }

    private final Map<UUID, Map<UUID, ActiveTurn>> byConversation = new ConcurrentHashMap<>();
    /** Participants whose running turn the user cancelled (conversationId:participantId). */
    private final java.util.Set<String> cancelledByUser = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void register(UUID conversationId, ActiveTurn turn) {
        byConversation.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>())
                .put(turn.participantId(), turn);
    }

    public void unregister(UUID conversationId, UUID participantId) {
        Map<UUID, ActiveTurn> turns = byConversation.get(conversationId);
        if (turns != null) {
            turns.remove(participantId);
        }
    }

    public List<ActiveTurn> active(UUID conversationId) {
        Map<UUID, ActiveTurn> turns = byConversation.get(conversationId);
        return turns == null ? List.of() : List.copyOf(turns.values());
    }

    /**
     * The hand-off round of the participant's currently running turn, or 0 when none is
     * running. Lets MCP tools inherit the turn's round instead of resetting the budget.
     */
    public int currentRound(UUID conversationId, UUID participantId) {
        Map<UUID, ActiveTurn> turns = byConversation.get(conversationId);
        ActiveTurn turn = turns == null ? null : turns.get(participantId);
        return turn == null ? 0 : turn.round();
    }

    /** Kills the process tree of the given agent's running turn. Returns true if one was killed. */
    public boolean cancel(UUID conversationId, UUID participantId) {
        Map<UUID, ActiveTurn> turns = byConversation.get(conversationId);
        if (turns == null) {
            return false;
        }
        ActiveTurn turn = turns.get(participantId);
        if (turn == null || turn.process() == null || !turn.process().isAlive()) {
            return false;
        }
        log.info("Cancelling turn of '{}' in conversation {}", turn.agentName(), conversationId);
        cancelledByUser.add(conversationId + ":" + participantId);
        turn.process().descendants().forEach(ProcessHandle::destroyForcibly);
        turn.process().destroyForcibly();
        try {
            turn.process().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** True (once) when the given participant's last turn failure was a user cancellation. */
    public boolean consumeCancelled(UUID conversationId, UUID participantId) {
        return cancelledByUser.remove(conversationId + ":" + participantId);
    }
}
