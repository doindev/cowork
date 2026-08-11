package dev.cowork.message;

import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Participant;
import dev.cowork.stream.SseHub;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    private final MessageRepository repository;
    private final SseHub sseHub;
    private final ApplicationEventPublisher events;

    public MessageService(MessageRepository repository, SseHub sseHub, ApplicationEventPublisher events) {
        this.repository = repository;
        this.sseHub = sseHub;
        this.events = events;
    }

    /** Persists a chat message, broadcasts it over SSE, and notifies the orchestrator. */
    public Message post(UUID conversationId, Participant sender, String content, int round) {
        return post(conversationId, sender, content, round, null, null);
    }

    /** Persists a chat message with turn metadata (cost, tool activity). */
    public Message post(UUID conversationId, Participant sender, String content, int round,
                        Double costUsd, String activity) {
        List<String> mentions = MentionParser.parse(content);
        Message message = repository.insert(conversationId, sender.getId(), sender.getDisplayName(),
                Message.Kind.CHAT, content, mentions, round, null, costUsd, activity);
        sseHub.publish(conversationId, "message", MessageViews.MessageView.of(message));
        events.publishEvent(new NewMessageEvent(message, sender.getKind()));
        return message;
    }

    /** Persists a non-chat (system/proposal/vote/phase) message. Never triggers agent turns. */
    public Message postSystem(UUID conversationId, Message.Kind kind, String content, UUID refId) {
        Message message = repository.insert(conversationId, null, "system", kind, content, List.of(), 0, refId,
                null, null);
        sseHub.publish(conversationId, "message", MessageViews.MessageView.of(message));
        return message;
    }

    public List<Message> recent(UUID conversationId, java.time.Instant before, int limit) {
        return repository.findRecent(conversationId, before, limit);
    }
}
