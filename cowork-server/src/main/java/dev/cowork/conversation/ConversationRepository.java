package dev.cowork.conversation;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface ConversationRepository extends ListCrudRepository<Conversation, UUID> {

    List<Conversation> findByStatusOrderByCreatedAtDesc(Conversation.Status status);
}
