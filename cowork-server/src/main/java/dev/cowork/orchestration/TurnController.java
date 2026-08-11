package dev.cowork.orchestration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User controls over running turns: inspect, cancel, and grant more rounds. */
@RestController
@RequestMapping("/api/conversations/{conversationId}")
public class TurnController {

    public record ActiveTurnView(UUID participantId, String agentName) {
    }

    private final ActiveTurnRegistry activeTurns;
    private final ConversationOrchestrator orchestrator;

    public TurnController(ActiveTurnRegistry activeTurns, ConversationOrchestrator orchestrator) {
        this.activeTurns = activeTurns;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/turns")
    public List<ActiveTurnView> active(@PathVariable UUID conversationId) {
        return activeTurns.active(conversationId).stream()
                .map(t -> new ActiveTurnView(t.participantId(), t.agentName()))
                .toList();
    }

    @DeleteMapping("/turns/{participantId}")
    public Map<String, Boolean> cancel(@PathVariable UUID conversationId, @PathVariable UUID participantId) {
        return Map.of("cancelled", activeTurns.cancel(conversationId, participantId));
    }

    @PostMapping("/continue")
    public Map<String, Integer> continueRounds(@PathVariable UUID conversationId) {
        return Map.of("resumed", orchestrator.continueRounds(conversationId));
    }
}
