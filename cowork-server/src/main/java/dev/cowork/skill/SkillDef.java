package dev.cowork.skill;

import java.util.Set;

import dev.cowork.conversation.Conversation;

/**
 * A skill: a reusable protocol block injected into every agent's standing instructions
 * while active in a conversation. Defined by {@code skills/<name>-skill.md} files.
 *
 * @param defaultPhases phases where the skill is active by default (empty = never default)
 */
public record SkillDef(String name, String description, Set<Conversation.Phase> defaultPhases, String body) {

    public boolean defaultActiveIn(Conversation.Phase phase) {
        return defaultPhases.contains(phase);
    }
}
