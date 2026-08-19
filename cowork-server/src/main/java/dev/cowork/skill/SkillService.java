package dev.cowork.skill;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.cowork.conversation.Conversation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves which skills are active in a conversation: explicit override, else phase default. */
@Service
public class SkillService {

    /** A skill plus its effective state in one conversation. */
    public record EffectiveSkill(SkillDef def, boolean active, boolean overridden) {
    }

    private final SkillRegistry registry;
    private final ConversationSkillRepository overrides;

    public SkillService(SkillRegistry registry, ConversationSkillRepository overrides) {
        this.registry = registry;
        this.overrides = overrides;
    }

    public List<SkillDef> allSkills() {
        return registry.all();
    }

    public List<EffectiveSkill> forConversation(Conversation conversation) {
        Map<String, Boolean> overrideByName = overrides.findByConversationId(conversation.getId()).stream()
                .collect(Collectors.toMap(ConversationSkill::getSkillName, ConversationSkill::isActive));
        return registry.all().stream()
                .map(def -> {
                    Boolean override = overrideByName.get(def.name());
                    boolean active = override != null ? override : def.defaultActiveIn(conversation.getPhase());
                    return new EffectiveSkill(def, active, override != null);
                })
                .toList();
    }

    /** The skills whose body should be injected into agent turns of this conversation. */
    public List<SkillDef> activeFor(Conversation conversation) {
        return forConversation(conversation).stream()
                .filter(EffectiveSkill::active)
                .map(EffectiveSkill::def)
                .toList();
    }

    @Transactional
    public EffectiveSkill setActive(UUID conversationId, String skillName, boolean active) {
        SkillDef def = registry.byName(skillName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown skill '" + skillName + "'"));
        ConversationSkill override = overrides.findByConversationIdAndSkillName(conversationId, skillName)
                .orElseGet(() -> {
                    ConversationSkill created = new ConversationSkill();
                    created.setConversationId(conversationId);
                    created.setSkillName(skillName);
                    return created;
                });
        override.setActive(active);
        override.setUpdatedAt(Instant.now());
        overrides.save(override);
        return new EffectiveSkill(def, active, true);
    }
}
