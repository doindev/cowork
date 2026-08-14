package dev.cowork.conversation;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("participant")
public class Participant implements UuidAssignable {

    public enum Kind { USER, AGENT }

    @Id
    private UUID id;
    private UUID conversationId;
    private Kind kind;
    private UUID agentId;
    private String displayName;
    private String mcpTokenHash;
    private String cliSessionId;
    private boolean active = true;
    private Instant joinedAt;
    /** Timestamp of the newest message this agent has been shown (delta cursor). */
    private Instant lastSeenAt;
    /** When true, the next turn starts a fresh CLI session with a recovery prompt. */
    private boolean sessionResetRequested;
    /** Turns taken on the current CLI session (drives optional auto-rotation). */
    private int sessionTurnCount;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public UUID getConversationId() { return conversationId; }

    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public Kind getKind() { return kind; }

    public void setKind(Kind kind) { this.kind = kind; }

    public UUID getAgentId() { return agentId; }

    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getDisplayName() { return displayName; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getMcpTokenHash() { return mcpTokenHash; }

    public void setMcpTokenHash(String mcpTokenHash) { this.mcpTokenHash = mcpTokenHash; }

    public String getCliSessionId() { return cliSessionId; }

    public void setCliSessionId(String cliSessionId) { this.cliSessionId = cliSessionId; }

    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }

    public Instant getJoinedAt() { return joinedAt; }

    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }

    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public boolean isSessionResetRequested() { return sessionResetRequested; }

    public void setSessionResetRequested(boolean sessionResetRequested) { this.sessionResetRequested = sessionResetRequested; }

    public int getSessionTurnCount() { return sessionTurnCount; }

    public void setSessionTurnCount(int sessionTurnCount) { this.sessionTurnCount = sessionTurnCount; }
}
