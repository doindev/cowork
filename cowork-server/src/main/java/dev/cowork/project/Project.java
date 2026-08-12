package dev.cowork.project;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("project")
public class Project implements UuidAssignable {

    @Id
    private UUID id;
    private String name;
    private String workspacePath;
    /** True when the workspace is a user-chosen directory outside the managed workspaces dir. */
    private boolean external;
    private Instant createdAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getWorkspacePath() { return workspacePath; }

    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }

    public boolean isExternal() { return external; }

    public void setExternal(boolean external) { this.external = external; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
