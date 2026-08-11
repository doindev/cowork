package dev.cowork.proposal;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("vote")
public class Vote implements UuidAssignable {

    public enum Value { YES, NO, ABSTAIN }

    @Id
    private UUID id;
    private UUID proposalId;
    private UUID voterParticipantId;
    private Value value;
    private String rationale;
    private Instant createdAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public UUID getProposalId() { return proposalId; }

    public void setProposalId(UUID proposalId) { this.proposalId = proposalId; }

    public UUID getVoterParticipantId() { return voterParticipantId; }

    public void setVoterParticipantId(UUID voterParticipantId) { this.voterParticipantId = voterParticipantId; }

    public Value getValue() { return value; }

    public void setValue(Value value) { this.value = value; }

    public String getRationale() { return rationale; }

    public void setRationale(String rationale) { this.rationale = rationale; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
