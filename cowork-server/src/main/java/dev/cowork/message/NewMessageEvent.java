package dev.cowork.message;

import dev.cowork.conversation.Participant;

/** Published after a message is persisted; the orchestrator reacts to it. */
public record NewMessageEvent(Message message, Participant.Kind senderKind) {
}
