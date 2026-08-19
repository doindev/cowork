package dev.cowork.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
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
 * participant-specific synthetic CODEX_HOME (config.toml + AGENTS.md), with auth
 * files copied from the real ~/.codex so login and rollout history are preserved.
 */
@Component
public class CodexRunner implements CliAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessExecutor executor;
    public CodexRunner(ProcessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CliType type() {
        return CliType.CODEX;
    }

    @Override
    public boolean available() {
        // Do not cache a miss: desktop/npm installs and PATH changes are common while
        // the server is already running.
        return CliBinaries.onPath("codex");
    }

    @Override
    public TurnResult run(TurnRequest request, TurnListener listener) throws CliTurnException {
        TurnListener effective = listener == null ? TurnListener.NOOP : listener;
        try {
            Path codexHome = prepareCodexHome(request);
            boolean resume = request.sessionId() != null && !request.sessionId().isBlank();
            Map<String, String> environment = codexEnvironment(codexHome);
            environment.putAll(request.env());
            ProcessExecutor.ExecResult result = executor.run(buildCommand(request, resume), request.workDir(),
                    environment, request.prompt(), request.timeout(),
                    effective::onProcessStart, null);
            if (result.exitCode() != 0 && resume && missingRollout(result)) {
                log.warn("Codex rollout '{}' is unavailable; starting a fresh session for agent '{}'",
                        request.sessionId(), request.agentName());
                result = executor.run(buildCommand(request, false), request.workDir(),
                        environment, request.prompt(), request.timeout(),
                        effective::onProcessStart, null);
            }
            effective.onRawOutput(result.stdout());
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
        }
    }

    /** Prevent a parent Codex session from imposing its managed sandbox on child agents. */
    private static Map<String, String> codexEnvironment(Path codexHome) {
        Map<String, String> environment = new HashMap<>();
        environment.put("CODEX_HOME", codexHome.toString());
        environment.put("CODEX_PERMISSION_PROFILE", null);
        environment.put("CODEX_SANDBOX_NETWORK_DISABLED", null);
        environment.put("CODEX_THREAD_ID", null);
        return environment;
    }

    private List<String> buildCommand(TurnRequest request, boolean resume) {
        List<String> cmd = new ArrayList<>();
        if (CliBinaries.needsCmdWrapper("codex")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(CliBinaries.resolve("codex"));
        if (!bypassEnabled(request)) {
            cmd.add("--sandbox");
            cmd.add(sandboxMode(request));
        }
        if (request.workDir() != null) {
            cmd.add("-C");
            cmd.add(request.workDir().toString());
        }
        if (request.model() != null && !request.model().isBlank()) {
            cmd.add("--model");
            cmd.add(request.model());
        }
        appendCodexGlobalOptions(cmd, request);
        cmd.add("exec");
        if (resume) {
            cmd.add("resume");
            cmd.add(request.sessionId());
        }
        cmd.add("--json");
        cmd.add("--skip-git-repo-check");
        appendCodexExecOptions(cmd, request);
        cmd.add("-");
        return cmd;
    }

    static boolean missingRollout(ProcessExecutor.ExecResult result) {
        String output = result.stderr() + "\n" + result.stdout();
        return output.toLowerCase().contains("no rollout found for thread id");
    }

    private String sandboxMode(TurnRequest request) {
        Object override = request.option("sandbox");
        return override == null ? "workspace-write" : override.toString();
    }

    /**
     * Whether the agent opted into {@code dangerously-bypass-approvals-and-sandbox}. When
     * set, the bypass flag replaces the sandbox/approval flags entirely: Codex removed its
     * Windows sandbox implementations (codex-cli 0.147+), so on native Windows every
     * sandboxed session silently degrades to read-only and the bypass is the only way for
     * an agent to write to its workspace.
     */
    static boolean bypassEnabled(TurnRequest request) {
        Object bypass = request.option("dangerously-bypass-approvals-and-sandbox");
        return bypass != null && Boolean.parseBoolean(bypass.toString());
    }

    static void appendCodexGlobalOptions(List<String> cmd, TurnRequest request) {
        Object automaticReview = request.option("approve-for-me");
        if (bypassEnabled(request)) {
            cmd.add("--dangerously-bypass-approvals-and-sandbox");
        } else if (automaticReview != null && Boolean.parseBoolean(automaticReview.toString())) {
            cmd.add("--approve-for-me");
        } else {
            String approvalPolicy = request.option("approval-policy") == null
                    ? "never" : request.option("approval-policy").toString().trim().toLowerCase();
            if (approvalPolicy.matches("untrusted|on-request|never")) {
                cmd.add("--ask-for-approval");
                cmd.add(approvalPolicy);
            } else {
                log.warn("Ignoring invalid Codex approval-policy '{}'; using 'never'",
                        request.option("approval-policy"));
                cmd.add("--ask-for-approval");
                cmd.add("never");
            }
        }
        Object effort = request.option("effort");
        if (effort != null) {
            String value = effort.toString().trim().toLowerCase();
            if (value.matches("minimal|low|medium|high|xhigh")) {
                cmd.add("--config");
                cmd.add("model_reasoning_effort=\"" + value + "\"");
            } else {
                log.warn("Ignoring invalid Codex effort option '{}'", effort);
            }
        }
        appendRepeated(cmd, "--config", request.option("config"));
        appendRepeated(cmd, "--enable", request.option("enable"));
        appendRepeated(cmd, "--disable", request.option("disable"));
        appendValue(cmd, "--local-provider", request.option("local-provider"));
        appendValue(cmd, "--profile", request.option("profile"));
        appendFlag(cmd, "--oss", request.option("oss"));
        appendFlag(cmd, "--dangerously-bypass-hook-trust",
                request.option("dangerously-bypass-hook-trust"));
        appendRepeated(cmd, "--add-dir", request.option("add-dir"));
    }

    static void appendCodexExecOptions(List<String> cmd, TurnRequest request) {
        appendRepeated(cmd, "--image", request.option("image"));
        appendValue(cmd, "--output-schema", request.option("output-schema"));
        appendFlag(cmd, "--strict-config", request.option("strict-config"));
        appendFlag(cmd, "--ephemeral", request.option("ephemeral"));
        appendFlag(cmd, "--ignore-user-config", request.option("ignore-user-config"));
        appendFlag(cmd, "--ignore-rules", request.option("ignore-rules"));
    }

    private static void appendValue(List<String> cmd, String flag, Object value) {
        if (value != null && !value.toString().isBlank()) {
            cmd.add(flag);
            cmd.add(value.toString().trim());
        }
    }

    private static void appendRepeated(List<String> cmd, String flag, Object value) {
        if (value == null) {
            return;
        }
        for (String item : value.toString().split(",")) {
            appendValue(cmd, flag, item);
        }
    }

    private static void appendFlag(List<String> cmd, String flag, Object value) {
        if (value != null && Boolean.parseBoolean(value.toString())) {
            cmd.add(flag);
        }
    }

    /** Builds a stable per-participant CODEX_HOME so Codex can resume local rollouts. */
    private Path prepareCodexHome(TurnRequest request) throws IOException {
        String owner = request.sessionOwnerId() == null || request.sessionOwnerId().isBlank()
                ? request.agentName() : request.sessionOwnerId();
        owner = owner == null ? "unknown" : owner.replaceAll("[^A-Za-z0-9_-]", "_");
        Path home = Path.of(System.getProperty("user.home"), ".cowork", "codex-homes", owner);
        Files.createDirectories(home);
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
        // Trust the workspace and allow network inside the workspace-write sandbox. The
        // config is regenerated every turn, so without an explicit trust entry Codex sees
        // an untrusted folder each time — and in non-interactive exec mode it cannot ask,
        // so the model replies asking the user to change permissions instead of working.
        if (request.workDir() != null) {
            toml.append("[projects.'").append(request.workDir().toAbsolutePath()).append("']\n");
            toml.append("trust_level = \"trusted\"\n\n");
        }
        toml.append("[sandbox_workspace_write]\n");
        toml.append("network_access = true\n\n");
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

}
