package dev.cowork.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cowork.agent.CliType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Headless Claude Code adapter: one {@code claude -p} process per turn with
 * {@code --output-format stream-json}, so tool activity and reply text stream live;
 * session resume via {@code --resume}, MCP wiring via {@code --mcp-config}.
 */
@Component
public class ClaudeCodeRunner implements CliAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessExecutor executor;
    private volatile Boolean available;

    public ClaudeCodeRunner(ProcessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CliType type() {
        return CliType.CLAUDE;
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached == null) {
            cached = CliBinaries.onPath("claude");
            available = cached;
        }
        return cached;
    }

    /** Mutable per-turn parse state fed line-by-line from the stream-json output. */
    private static final class StreamState {

        final TurnListener listener;
        final StringBuilder partialText = new StringBuilder();
        final List<Map<String, String>> activity = new ArrayList<>();
        String finalText;
        String sessionId;
        Double costUsd;
        String errorText;

        StreamState(TurnListener listener) {
            this.listener = listener;
        }

        void onLine(String line) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                return;
            }
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                switch (node.path("type").asText("")) {
                    case "assistant" -> onAssistant(node.path("message").path("content"));
                    case "result" -> {
                        if (node.path("is_error").asBoolean(false)) {
                            errorText = node.path("result").asText("unknown error");
                        }
                        finalText = node.path("result").asText("");
                        sessionId = node.path("session_id").asText(null);
                        costUsd = node.hasNonNull("total_cost_usd") ? node.get("total_cost_usd").asDouble() : null;
                    }
                    default -> {
                    }
                }
            } catch (IOException ignored) {
                // Non-JSON noise between events.
            }
        }

        private void onAssistant(JsonNode content) {
            if (!content.isArray()) {
                return;
            }
            for (JsonNode block : content) {
                switch (block.path("type").asText("")) {
                    case "text" -> {
                        String text = block.path("text").asText("");
                        if (!text.isBlank()) {
                            if (!partialText.isEmpty()) {
                                partialText.append("\n\n");
                            }
                            partialText.append(text);
                            listener.onPartialText(partialText.toString());
                        }
                    }
                    case "tool_use" -> {
                        String tool = block.path("name").asText("tool");
                        String summary = summarizeInput(block.path("input"));
                        activity.add(activityEntry(tool, summary));
                        listener.onActivity(tool, summary);
                    }
                    default -> {
                    }
                }
            }
        }

        private static Map<String, String> activityEntry(String tool, String summary) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("tool", tool);
            entry.put("summary", summary);
            return entry;
        }

        private static String summarizeInput(JsonNode input) {
            for (String key : List.of("command", "file_path", "pattern", "url", "query", "description")) {
                if (input.hasNonNull(key)) {
                    return truncate(input.get(key).asText(), 160);
                }
            }
            Iterator<String> fields = input.fieldNames();
            if (fields.hasNext()) {
                String first = fields.next();
                return truncate(first + "=" + input.path(first).asText(input.path(first).toString()), 160);
            }
            return "";
        }

        String activityJson() {
            if (activity.isEmpty()) {
                return null;
            }
            try {
                return MAPPER.writeValueAsString(activity);
            } catch (IOException e) {
                return null;
            }
        }
    }

    @Override
    public TurnResult run(TurnRequest request, TurnListener listener) throws CliTurnException {
        Path mcpConfig = null;
        Path personaFile = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(CliBinaries.resolve("claude"));
            cmd.add("-p");
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
            cmd.add("--permission-mode");
            cmd.add(permissionMode(request));
            cmd.add("--allowedTools");
            cmd.add("Bash,Read,Edit,Write,Glob,Grep,WebFetch,WebSearch,mcp__cowork,mcp__chrome-devtools");
            if (request.model() != null && !request.model().isBlank()) {
                cmd.add("--model");
                cmd.add(request.model());
            }
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                cmd.add("--resume");
                cmd.add(request.sessionId());
            }
            if (request.persona() != null && !request.persona().isBlank()) {
                personaFile = Files.createTempFile("cowork-persona-", ".md");
                Files.writeString(personaFile, request.persona());
                cmd.add("--append-system-prompt-file");
                cmd.add(personaFile.toString());
            }
            if (request.mcpUrl() != null && request.mcpToken() != null) {
                mcpConfig = McpConfigs.writeJsonConfig(request, "stdio", "cowork-mcp-");
                cmd.add("--mcp-config");
                cmd.add(mcpConfig.toString());
                cmd.add("--strict-mcp-config");
            }

            StreamState state = new StreamState(listener == null ? TurnListener.NOOP : listener);
            TurnListener effective = listener == null ? TurnListener.NOOP : listener;
            ProcessExecutor.ExecResult result = executor.run(cmd, request.workDir(), Map.of(),
                    request.prompt(), request.timeout(), effective::onProcessStart, state::onLine);
            if (result.timedOut()) {
                throw new CliTurnException("claude turn timed out after " + request.timeout().toMinutes() + " minutes");
            }
            if (state.errorText != null) {
                throw new CliTurnException("claude reported an error: " + truncate(state.errorText, 500));
            }
            if (result.exitCode() != 0) {
                throw new CliTurnException("claude exited with code " + result.exitCode()
                        + ": " + truncate(result.stderr().isBlank() ? result.stdout() : result.stderr(), 500));
            }
            String text = state.finalText != null && !state.finalText.isBlank()
                    ? state.finalText
                    : state.partialText.toString();
            if (state.finalText == null) {
                log.warn("claude produced no result event; falling back to accumulated text");
            }
            return new TurnResult(text, state.sessionId, state.costUsd, state.activityJson());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CliTurnException("Failed to run claude: " + e.getMessage(), e);
        } finally {
            deleteQuietly(mcpConfig);
            deleteQuietly(personaFile);
        }
    }

    private static String permissionMode(TurnRequest request) {
        Object override = request.option("permission-mode");
        return override == null ? "acceptEdits" : override.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
