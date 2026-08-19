package dev.cowork.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import dev.cowork.conversation.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryParseTest {

    @TempDir
    Path dir;

    @Test
    void parsesFrontmatterPhasesAndBody() throws Exception {
        Path file = dir.resolve("decision-tree-skill.md");
        Files.writeString(file, """
                ---
                name: decision-tree
                description: Options with pros/cons
                phases: [PLANNING]
                ---
                Present options, not assumptions.
                """);
        SkillDef skill = SkillRegistry.parse(file).orElseThrow();
        assertEquals("decision-tree", skill.name());
        assertEquals("Options with pros/cons", skill.description());
        assertEquals(Set.of(Conversation.Phase.PLANNING), skill.defaultPhases());
        assertTrue(skill.defaultActiveIn(Conversation.Phase.PLANNING));
        assertFalse(skill.defaultActiveIn(Conversation.Phase.IMPLEMENTATION));
        assertEquals("Present options, not assumptions.", skill.body());
    }

    @Test
    void emptyPhasesMeansNeverDefault() throws Exception {
        Path file = dir.resolve("quiet-skill.md");
        Files.writeString(file, """
                ---
                name: quiet
                ---
                Body only.
                """);
        SkillDef skill = SkillRegistry.parse(file).orElseThrow();
        assertFalse(skill.defaultActiveIn(Conversation.Phase.PLANNING));
        assertFalse(skill.defaultActiveIn(Conversation.Phase.IMPLEMENTATION));
    }

    @Test
    void unknownPhaseIsIgnoredNotFatal() throws Exception {
        Path file = dir.resolve("odd-skill.md");
        Files.writeString(file, """
                ---
                name: odd
                phases: [PLANNING, LAUNCH]
                ---
                Body.
                """);
        SkillDef skill = SkillRegistry.parse(file).orElseThrow();
        assertEquals(Set.of(Conversation.Phase.PLANNING), skill.defaultPhases());
    }

    @Test
    void rejectsMissingNameOrFrontmatter() throws Exception {
        Path noName = dir.resolve("anon-skill.md");
        Files.writeString(noName, """
                ---
                description: nameless
                ---
                Body.
                """);
        assertTrue(SkillRegistry.parse(noName).isEmpty());

        Path bare = dir.resolve("bare-skill.md");
        Files.writeString(bare, "no frontmatter");
        assertTrue(SkillRegistry.parse(bare).isEmpty());
    }
}
