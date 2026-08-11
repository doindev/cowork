package dev.cowork.proposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.ParticipantRepository;

public final class ProposalViews {

    private ProposalViews() {
    }

    public record VoteView(String voter, String value, String rationale, Instant at) {
    }

    public record ProposalView(UUID id, UUID conversationId, String proposer, String type, String title,
                               String body, String status, UUID taskId, String commitHash, Instant createdAt,
                               Instant decidedAt, String decidedBy, List<VoteView> votes) {
    }

    public static ProposalView of(Proposal proposal, List<Vote> votes, ParticipantRepository participants) {
        List<VoteView> voteViews = votes.stream()
                .map(v -> new VoteView(
                        participants.findById(v.getVoterParticipantId())
                                .map(p -> p.getDisplayName()).orElse("unknown"),
                        v.getValue().name(), v.getRationale(), v.getCreatedAt()))
                .toList();
        return new ProposalView(proposal.getId(), proposal.getConversationId(),
                participants.findById(proposal.getProposerParticipantId())
                        .map(p -> p.getDisplayName()).orElse("unknown"),
                proposal.getType().name(), proposal.getTitle(), proposal.getBody(),
                proposal.getStatus().name(), proposal.getTaskId(), proposal.getCommitHash(),
                proposal.getCreatedAt(), proposal.getDecidedAt(),
                proposal.getDecidedBy() == null ? null : proposal.getDecidedBy().name(), voteViews);
    }
}
