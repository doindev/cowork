package dev.cowork.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.cowork.cli.ClaudeCodeRunner;
import dev.cowork.cli.CliTurnException;
import dev.cowork.cli.TurnListener;
import dev.cowork.cli.TurnRequest;
import dev.cowork.cli.TurnResult;
import org.springframework.stereotype.Service;

/**
 * The agent-editor "sparkle" assistant: a headless Claude session that helps the user
 * write or refine an agent definition file. When it proposes changes it returns the
 * complete updated file, which the UI applies to the editor as an unsaved draft.
 */
@Service
public class AgentAssistService {

    private static final Pattern FILE_BLOCK =
            Pattern.compile("```agentmd\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
            You are the agent-design assistant inside "cowork", a multi-agent collaboration app. \
            You help the user write and refine agent definition files (*-agent.md) through chat.

            File format: YAML frontmatter delimited by --- lines with keys: name (required, \
            alphanumeric/_/- only), cli (required: claude | codex | copilot), model (optional), \
            description (optional one-liner), options (optional map, e.g. permission-mode, \
            turn-timeout-seconds to give slow agents more than the default 300s per turn, \
            effort: low|medium|high|xhigh|max to set the claude CLI thinking level, \
            autocompact: auto|100000-1000000 to auto-compact long claude sessions, \
            max-session-turns: N to auto-refresh the agent's session every N turns, 0 = off, or \
            fallback-cli + fallback-model (+ optional fallback-effort) naming another vendor's \
            CLI and model that turns automatically reroute to while the primary vendor's usage \
            limit is exhausted). \
            After the frontmatter comes the persona: the system prompt defining the agent's \
            specialty, working style, and collaboration behavior in the team chat room.

            Good personas: give the agent a clear specialty and point of view, tell it to be \
            concise in chat, to disagree constructively, and to use proposals/votes for decisions.

            Rules:
            - Answer conversationally and briefly.
            - Whenever you propose a new or modified file, output the COMPLETE updated file in a \
            fenced block tagged agentmd (```agentmd ... ```). Never output partial files.
            - Keep the frontmatter 'name' unchanged unless the user explicitly asks to rename.
            - Do not use any tools; just reply with text.
            """;

    public record AssistResult(String reply, String updatedContent, String sessionId) {
    }

    private final ClaudeCodeRunner claude;

    public AgentAssistService(ClaudeCodeRunner claude) {
        this.claude = claude;
    }

    public AssistResult chat(String agentName, String currentContent, String userMessage, String sessionId)
            throws CliTurnException {
        if (!claude.available()) {
            throw new CliTurnException("The claude CLI is not installed — the assistant is unavailable.");
        }
        StringBuilder prompt = new StringBuilder();
        if (sessionId == null || sessionId.isBlank()) {
            prompt.append("The user is editing the agent definition file ")
                    .append(agentName == null || agentName.isBlank() ? "(new agent)" : agentName + "-agent.md")
                    .append(".\n\nCurrent editor content:\n```\n")
                    .append(currentContent == null ? "" : currentContent)
                    .append("\n```\n\n");
        }
        prompt.append("User: ").append(userMessage).append('\n');

        Path workDir;
        try {
            workDir = Files.createTempDirectory("cowork-assist-");
        } catch (IOException e) {
            throw new CliTurnException("Could not create assistant working directory", e);
        }
        try {
            TurnRequest request = new TurnRequest(
                    "assistant",
                    "agent-manager-assistant",
                    prompt.toString(),
                    SYSTEM_PROMPT,
                    null,
                    sessionId,
                    workDir,
                    null,
                    null,
                    false,
                    Map.of("permission-mode", "dontAsk"),
                    Duration.ofSeconds(120));
            TurnResult result = claude.run(request, TurnListener.NOOP);

            String text = result.text() == null ? "" : result.text();
            Matcher matcher = FILE_BLOCK.matcher(text);
            String updated = matcher.find() ? matcher.group(1).trim() + "\n" : null;
            String reply = FILE_BLOCK.matcher(text)
                    .replaceAll("(updated file applied to the editor)")
                    .trim();
            return new AssistResult(reply, updated, result.sessionId());
        } finally {
            try {
                Files.deleteIfExists(workDir);
            } catch (IOException ignored) {
            }
        }
    }
}
