package dev.cowork.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.message.Message;
import dev.cowork.message.MessageRepository;
import dev.cowork.message.MessageService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Chat-room MCP tools. The calling agent's identity comes from the request's bearer
 * token; agents can only post as themselves.
 */
@Component
public class CoworkChatTools {

    public record MessageRecord(String sender, String kind, String content, List<String> mentions, Instant at) {

        static MessageRecord of(Message m) {
            return new MessageRecord(m.senderName(), m.kind().name(), m.content(), m.mentions(), m.createdAt());
        }
    }

    public record ParticipantRecord(String name, String kind, boolean active) {
    }

    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final ParticipantRepository participants;

    public CoworkChatTools(MessageService messageService, MessageRepository messageRepository,
                           ParticipantRepository participants) {
        this.messageService = messageService;
        this.messageRepository = messageRepository;
        this.participants = participants;
    }

    @McpTool(name = "post_message", description = """
            Post an additional message to your chat room under your own name. Your final reply text is \
            posted automatically, so use this only for extra notes (e.g. a progress update mid-task). \
            Prefix with @name or @a,@b to address specific participants.""")
    public String postMessage(
            @McpToolParam(required = true, description = "The message text to post") String text) {
        Participant caller = McpCallerContext.require();
        Message message = messageService.post(caller.getConversationId(), caller, text, 0);
        return "posted message " + message.id();
    }

    @McpTool(name = "read_conversation", description = """
            Read the most recent messages of a conversation, newest first. Omit conversation_id for \
            your own conversation; pass another conversation's id to read that one (read-only).""")
    public List<MessageRecord> readConversation(
            @McpToolParam(required = false, description = "Conversation id (UUID); defaults to your own") String conversationId,
            @McpToolParam(required = false, description = "Max messages to return (default 20, max 100)") Integer limit) {
        Participant caller = McpCallerContext.require();
        UUID target = conversationId == null || conversationId.isBlank()
                ? caller.getConversationId()
                : UUID.fromString(conversationId);
        int n = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        return messageRepository.findRecent(target, null, n).stream().map(MessageRecord::of).toList();
    }

    @McpTool(name = "search_messages", description = """
            Full-text search over chat messages. Omit conversation_id to search all conversations; \
            pass one to restrict the search.""")
    public List<MessageRecord> searchMessages(
            @McpToolParam(required = true, description = "Search query (plain words)") String query,
            @McpToolParam(required = false, description = "Conversation id (UUID) to restrict to") String conversationId,
            @McpToolParam(required = false, description = "Max messages to return (default 20, max 100)") Integer limit) {
        UUID target = conversationId == null || conversationId.isBlank() ? null : UUID.fromString(conversationId);
        int n = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        return messageRepository.search(query, target, n).stream().map(MessageRecord::of).toList();
    }

    @McpTool(name = "list_participants", description = "List the participants of your conversation.")
    public List<ParticipantRecord> listParticipants() {
        Participant caller = McpCallerContext.require();
        return participants.findByConversationId(caller.getConversationId()).stream()
                .map(p -> new ParticipantRecord(p.getDisplayName(), p.getKind().name(), p.isActive()))
                .toList();
    }

    @McpTool(name = "refresh_session", description = """
            Call this when your context is filling up: your CLI session will be replaced \
            with a FRESH one before your next turn, which recovers from your notes file \
            plus a recap of recent messages. Update agent-notes/<your-name>.md with \
            everything you need to remember BEFORE ending the current reply.""")
    public String refreshSession() {
        Participant caller = McpCallerContext.require();
        Participant current = participants.findById(caller.getId()).orElse(caller);
        current.setSessionResetRequested(true);
        participants.save(current);
        return "Acknowledged: your next turn starts a fresh session. Make sure agent-notes/"
                + caller.getDisplayName() + ".md is up to date before you finish this reply.";
    }
}
