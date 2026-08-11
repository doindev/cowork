package dev.cowork.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Message(
        UUID id,
        UUID conversationId,
        UUID senderParticipantId,
        String senderName,
        Kind kind,
        String content,
        List<String> mentions,
        int round,
        UUID refId,
        Double costUsd,
        String activity,
        Instant createdAt) {

    public enum Kind { CHAT, SYSTEM, PROPOSAL, VOTE, PHASE }
}
