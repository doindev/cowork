package dev.cowork.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface ParticipantRepository extends ListCrudRepository<Participant, UUID> {

    List<Participant> findByConversationId(UUID conversationId);

    List<Participant> findByConversationIdAndActiveTrue(UUID conversationId);

    Optional<Participant> findByConversationIdAndDisplayName(UUID conversationId, String displayName);

    Optional<Participant> findByMcpTokenHash(String mcpTokenHash);
}
