package dev.cowork.proposal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface VoteRepository extends ListCrudRepository<Vote, UUID> {

    List<Vote> findByProposalId(UUID proposalId);

    Optional<Vote> findByProposalIdAndVoterParticipantId(UUID proposalId, UUID voterParticipantId);
}
