package dev.cowork.conversation;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("conversation")
public class Conversation implements UuidAssignable {

    public enum Phase { PLANNING, IMPLEMENTATION }

    public enum VoteMode { MAJORITY, UNANIMOUS }

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    private UUID id;
    private String title;
    private Phase phase = Phase.PLANNING;
    private VoteMode voteMode = VoteMode.MAJORITY;
    private int maxAgentRounds = 4;
    private boolean userVotes = false;
    private UUID projectId;
    private Status status = Status.ACTIVE;
    private Double budgetUsd;
    private double spentUsd;
    private Instant createdAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public Phase getPhase() { return phase; }

    public void setPhase(Phase phase) { this.phase = phase; }

    public VoteMode getVoteMode() { return voteMode; }

    public void setVoteMode(VoteMode voteMode) { this.voteMode = voteMode; }

    public int getMaxAgentRounds() { return maxAgentRounds; }

    public void setMaxAgentRounds(int maxAgentRounds) { this.maxAgentRounds = maxAgentRounds; }

    public boolean isUserVotes() { return userVotes; }

    public void setUserVotes(boolean userVotes) { this.userVotes = userVotes; }

    public UUID getProjectId() { return projectId; }

    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Double getBudgetUsd() { return budgetUsd; }

    public void setBudgetUsd(Double budgetUsd) { this.budgetUsd = budgetUsd; }

    public double getSpentUsd() { return spentUsd; }

    public void setSpentUsd(double spentUsd) { this.spentUsd = spentUsd; }

    public boolean isBudgetExhausted() {
        return budgetUsd != null && budgetUsd > 0 && spentUsd >= budgetUsd;
    }
}
