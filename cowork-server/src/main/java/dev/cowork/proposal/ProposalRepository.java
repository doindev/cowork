package dev.cowork.proposal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ProposalRepository extends ListCrudRepository<Proposal, UUID> {

    List<Proposal> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    List<Proposal> findByConversationIdAndStatusOrderByCreatedAtDesc(UUID conversationId, Proposal.Status status);

    @Query("SELECT * FROM proposal WHERE id = :id FOR UPDATE")
    Optional<Proposal> findByIdForUpdate(@Param("id") UUID id);
}
