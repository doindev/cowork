package dev.cowork.proposal;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("proposal")
public class Proposal implements UuidAssignable {

    public enum Type { PLAN_APPROVAL, TASK_ASSIGNMENT, CODE_CHANGE }

    public enum Status { OPEN, PASSED, REJECTED, CANCELLED, NEEDS_USER }

    public enum DecidedBy { TALLY, USER_OVERRIDE }

    @Id
    private UUID id;
    private UUID conversationId;
    private UUID proposerParticipantId;
    private Type type;
    private String title;
    private String body;
    private Status status = Status.OPEN;
    private UUID taskId;
    private UUID assigneeAgentId;
    private String commitHash;
    private Instant createdAt;
    private Instant decidedAt;
    private DecidedBy decidedBy;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public UUID getConversationId() { return conversationId; }

    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public UUID getProposerParticipantId() { return proposerParticipantId; }

    public void setProposerParticipantId(UUID proposerParticipantId) { this.proposerParticipantId = proposerParticipantId; }

    public Type getType() { return type; }

    public void setType(Type type) { this.type = type; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }

    public void setBody(String body) { this.body = body; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public UUID getTaskId() { return taskId; }

    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public UUID getAssigneeAgentId() { return assigneeAgentId; }

    public void setAssigneeAgentId(UUID assigneeAgentId) { this.assigneeAgentId = assigneeAgentId; }

    public String getCommitHash() { return commitHash; }

    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDecidedAt() { return decidedAt; }

    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public DecidedBy getDecidedBy() { return decidedBy; }

    public void setDecidedBy(DecidedBy decidedBy) { this.decidedBy = decidedBy; }
}
