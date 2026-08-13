package dev.cowork.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cowork.agent.CliType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI Codex CLI adapter: {@code codex exec --json} per turn, resume via
 * {@code codex exec resume <id>}. MCP servers and persona are injected through a
 * per-turn synthetic CODEX_HOME (config.toml + AGENTS.md), with auth files copied
 * from the real ~/.codex so login is preserved.
 */
@Component
public class CodexRunner implements CliAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessExecutor executor;
    private volatile Boolean available;

    public CodexRunner(ProcessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CliType type() {
        return CliType.CODEX;
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached == null) {
            cached = CliBinaries.onPath("codex");
            available = cached;
        }
        return cached;
    }

    @Override
    public TurnResult run(TurnRequest request, TurnListener listener) throws CliTurnException {
        TurnListener effective = listener == null ? TurnListener.NOOP : listener;
        Path codexHome = null;
        try {
            codexHome = prepareCodexHome(request);

            List<String> cmd = new ArrayList<>();
            if (CliBinaries.needsCmdWrapper("codex")) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            }
            cmd.add(CliBinaries.resolve("codex"));
            cmd.add("exec");
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                cmd.add("resume");
                cmd.add(request.sessionId());
            }
            cmd.add("--json");
            cmd.add("--skip-git-repo-check");
            cmd.add("--sandbox");
            cmd.add(sandboxMode(request));
            if (request.workDir() != null) {
                cmd.add("-C");
                cmd.add(request.workDir().toString());
            }
            if (request.model() != null && !request.model().isBlank()) {
                cmd.add("--model");
                cmd.add(request.model());
            }
            cmd.add("-");  // read the prompt from stdin

            ProcessExecutor.ExecResult result = executor.run(cmd, request.workDir(),
                    Map.of("CODEX_HOME", codexHome.toString()), request.prompt(), request.timeout(),
                    effective::onProcessStart, null);
            if (result.timedOut()) {
                throw new CliTurnException("codex turn timed out after " + request.timeout().toMinutes() + " minutes");
            }
            if (result.exitCode() != 0) {
                throw new CliTurnException("codex exited with code " + result.exitCode()
                        + ": " + truncate(result.stderr().isBlank() ? result.stdout() : result.stderr()));
            }
            return parseJsonl(result.stdout());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CliTurnException("Failed to run codex: " + e.getMessage(), e);
        } finally {
            deleteRecursively(codexHome);
        }
    }

    private String sandboxMode(TurnRequest request) {
        Object override = request.option("sandbox");
        return override == null ? "workspace-write" : override.toString();
    }

    /** Builds a throwaway CODEX_HOME: copied auth + generated config.toml + persona AGENTS.md. */
    private Path prepareCodexHome(TurnRequest request) throws IOException {
        Path home = Files.createTempDirectory("cowork-codex-home-");
        Path realHome = Path.of(System.getProperty("user.home"), ".codex");
        for (String authFile : List.of("auth.json", "version.json")) {
            Path source = realHome.resolve(authFile);
            if (Files.isRegularFile(source)) {
                Files.copy(source, home.resolve(authFile), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        StringBuilder toml = new StringBuilder();
        if (request.mcpUrl() != null && request.mcpToken() != null) {
            toml.append("[mcp_servers.cowork]\n");
            toml.append("url = \"").append(request.mcpUrl()).append("\"\n");
            toml.append("http_headers = { \"Authorization\" = \"Bearer ").append(request.mcpToken()).append("\" }\n\n");
        }
        if (request.includeChromeDevtools()) {
            toml.append("[mcp_servers.chrome-devtools]\n");
            toml.append("command = \"npx\"\n");
            toml.append("args = [\"-y\", \"chrome-devtools-mcp@latest\"]\n\n");
        }
        Files.writeString(home.resolve("config.toml"), toml.toString());
        if (request.persona() != null && !request.persona().isBlank()) {
            Files.writeString(home.resolve("AGENTS.md"), request.persona());
        }
        return home;
    }

    /** Parses the codex exec --json event stream: thread id, final agent message, activity. */
    static TurnResult parseJsonl(String stdout) throws CliTurnException {
        String sessionId = null;
        String lastAgentMessage = null;
        List<Map<String, String>> activity = new ArrayList<>();
        for (String line : stdout.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");
                if (type.equals("thread.started")) {
                    sessionId = node.path("thread_id").asText(sessionId);
                } else if (type.equals("item.completed")) {
                    JsonNode item = node.path("item");
                    String itemType = item.path("item_type").asText(item.path("type").asText(""));
                    switch (itemType) {
                        case "agent_message" -> lastAgentMessage = item.path("text").asText(lastAgentMessage);
                        case "command_execution" -> activity.add(Map.of("tool", "command",
                                "summary", truncate(item.path("command").asText(""))));
                        case "file_change" -> activity.add(Map.of("tool", "file_change",
                                "summary", truncate(item.path("path").asText(item.toString()))));
                        case "mcp_tool_call" -> activity.add(Map.of("tool", "mcp",
                                "summary", truncate(item.path("tool").asText(item.path("name").asText("")))));
                        default -> {
                        }
                    }
                } else if (type.equals("turn.failed") || type.equals("error")) {
                    throw new CliTurnException("codex reported an error: "
                            + truncate(node.path("error").path("message").asText(node.toString())));
                }
            } catch (IOException ignored) {
                // Non-JSON noise lines are fine.
            }
        }
        if (lastAgentMessage == null) {
            log.warn("codex produced no agent_message; raw output: {}", truncate(stdout));
        }
        String activityJson = null;
        if (!activity.isEmpty()) {
            try {
                activityJson = MAPPER.writeValueAsString(activity);
            } catch (IOException ignored) {
            }
        }
        return new TurnResult(lastAgentMessage == null ? "" : lastAgentMessage, sessionId, null, activityJson);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
