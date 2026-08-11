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
}
