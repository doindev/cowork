package dev.cowork.project;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("task")
public class ProjectTask implements UuidAssignable {

    public enum Status { PROPOSED, APPROVED, IN_PROGRESS, IN_REVIEW, DONE }

    @Id
    private UUID id;
    private UUID projectId;
    private String title;
    private String description;
    private Status status = Status.PROPOSED;
    private UUID assigneeAgentId;
    @Column("origin_proposal_id")
    private UUID originProposalId;
    private int ordinal;
    private Instant createdAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public UUID getProjectId() { return projectId; }

    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public UUID getAssigneeAgentId() { return assigneeAgentId; }

    public void setAssigneeAgentId(UUID assigneeAgentId) { this.assigneeAgentId = assigneeAgentId; }

    public UUID getOriginProposalId() { return originProposalId; }

    public void setOriginProposalId(UUID originProposalId) { this.originProposalId = originProposalId; }

    public int getOrdinal() { return ordinal; }

    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
