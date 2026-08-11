package dev.cowork.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageViews {

    private MessageViews() {
    }

    public record MessageView(UUID id, UUID conversationId, UUID senderParticipantId, String senderName,
                              String kind, String content, List<String> mentions, int round, UUID refId,
                              Double costUsd, String activity, Instant createdAt) {

        public static MessageView of(Message m) {
            return new MessageView(m.id(), m.conversationId(), m.senderParticipantId(), m.senderName(),
                    m.kind().name(), m.content(), m.mentions(), m.round(), m.refId(), m.costUsd(),
                    m.activity(), m.createdAt());
        }
    }
}
