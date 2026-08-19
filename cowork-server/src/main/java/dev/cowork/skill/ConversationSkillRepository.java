package dev.cowork.skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface ConversationSkillRepository extends ListCrudRepository<ConversationSkill, UUID> {

    List<ConversationSkill> findByConversationId(UUID conversationId);

    Optional<ConversationSkill> findByConversationIdAndSkillName(UUID conversationId, String skillName);
}
