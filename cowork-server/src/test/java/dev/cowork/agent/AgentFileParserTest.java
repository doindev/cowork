package dev.cowork.agent;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFileParserTest {

    @TempDir
    Path dir;

    @Test
    void parsesFrontmatterAndPersona() throws Exception {
        Path file = dir.resolve("tester-agent.md");
        Files.writeString(file, """
                ---
                name: tester
                cli: claude
                model: claude-sonnet-4-5
                description: A test agent
                options: { permission-mode: acceptEdits }
                ---
                You are a test agent.
                Be concise.
                """);
        var parsed = AgentFileParser.parse(file).orElseThrow();
        assertEquals("tester", parsed.name());
        assertEquals(CliType.CLAUDE, parsed.cliType());
        assertEquals("claude-sonnet-4-5", parsed.model());
        assertEquals("A test agent", parsed.description());
        assertTrue(parsed.options().contains("permission-mode"));
        assertTrue(parsed.persona().startsWith("You are a test agent."));
    }

    @Test
    void rejectsMissingFrontmatter() throws Exception {
        Path file = dir.resolve("bad-agent.md");
        Files.writeString(file, "no frontmatter here");
        assertTrue(AgentFileParser.parse(file).isEmpty());
    }

    @Test
    void rejectsInvalidName() throws Exception {
        Path file = dir.resolve("weird-agent.md");
        Files.writeString(file, """
                ---
                name: "has spaces!"
                cli: codex
                ---
                persona
                """);
        assertTrue(AgentFileParser.parse(file).isEmpty());
    }
}
