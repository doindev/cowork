package dev.cowork.skill;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** An explicit per-conversation skill toggle; absence means the skill's phase defaults apply. */
@Table("conversation_skill")
public class ConversationSkill implements UuidAssignable {

    @Id
    private UUID id;
    private UUID conversationId;
    private String skillName;
    private boolean active;
    private Instant updatedAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public UUID getConversationId() { return conversationId; }

    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public String getSkillName() { return skillName; }

    public void setSkillName(String skillName) { this.skillName = skillName; }

    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
