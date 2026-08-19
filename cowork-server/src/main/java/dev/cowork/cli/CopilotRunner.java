package dev.cowork.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.cowork.agent.CliType;
import org.springframework.stereotype.Component;

/**
 * EXPERIMENTAL GitHub Copilot CLI adapter. The CLI has no JSON output mode and no
 * machine-readable session-id capture, so this adapter is stateless: every turn gets the
 * full delta transcript on stdin and the reply is the CLI's plain-text stdout. MCP
 * servers are injected via a per-turn synthetic COPILOT_HOME.
 */
@Component
public class CopilotRunner implements CliAgentRunner {

    private static final Pattern ANSI = Pattern.compile("\\[[;\\d]*[ -/]*[@-~]");

    private final ProcessExecutor executor;
    private volatile Boolean available;

    public CopilotRunner(ProcessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CliType type() {
        return CliType.COPILOT;
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached == null) {
            cached = CliBinaries.onPath("copilot");
            available = cached;
        }
        return cached;
    }

    @Override
    public boolean supportsSessions() {
        return false;
    }

    @Override
    public TurnResult run(TurnRequest request, TurnListener listener) throws CliTurnException {
        TurnListener effective = listener == null ? TurnListener.NOOP : listener;
        Path copilotHome = null;
        try {
            copilotHome = prepareCopilotHome(request);

            List<String> cmd = new ArrayList<>();
            if (CliBinaries.needsCmdWrapper("copilot")) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            }
            cmd.add(CliBinaries.resolve("copilot"));
            cmd.add("-s");
            cmd.add("--no-ask-user");
            cmd.add("--allow-all-tools");
            if (request.workDir() != null) {
                cmd.add("--add-dir");
                cmd.add(request.workDir().toString());
            }
            if (request.model() != null && !request.model().isBlank()) {
                cmd.add("--model");
                cmd.add(request.model());
            }
            appendCopilotOptions(cmd, request);

            // Persona has no per-invocation flag; prepend it to the stdin prompt instead.
            String prompt = request.persona() == null || request.persona().isBlank()
                    ? request.prompt()
                    : "[YOUR PERSONA]\n" + request.persona() + "\n\n" + request.prompt();

            ProcessExecutor.ExecResult result = executor.run(cmd, request.workDir(),
                    copilotEnvironment(copilotHome, request), prompt, request.timeout(),
                    effective::onProcessStart, null);
            if (result.timedOut()) {
                throw new CliTurnException("copilot turn timed out after " + request.timeout().toMinutes() + " minutes");
            }
            if (result.exitCode() != 0) {
                throw new CliTurnException("copilot exited with code " + result.exitCode()
                        + ": " + truncate(result.stderr().isBlank() ? result.stdout() : result.stderr()));
            }
            String text = ANSI.matcher(result.stdout()).replaceAll("").trim();
            return new TurnResult(text, null, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CliTurnException("Failed to run copilot: " + e.getMessage(), e);
        } finally {
            deleteRecursively(copilotHome);
        }
    }

    static void appendCopilotOptions(List<String> cmd, TurnRequest request) {
        appendValue(cmd, "--effort", request.option("effort"));
        appendValue(cmd, "--context", request.option("context"));
        appendValue(cmd, "--mode", request.option("mode"));
        appendValue(cmd, "--log-level", request.option("log-level"));
        appendValue(cmd, "--stream", request.option("stream"));
        appendValue(cmd, "--bash-env", request.option("bash-env"));
        appendValue(cmd, "--max-ai-credits", request.option("max-ai-credits"));
        appendValue(cmd, "--max-autopilot-continues", request.option("max-autopilot-continues"));

        for (String key : List.of("add-dir", "allow-tool", "deny-tool", "available-tools",
                "excluded-tools", "allow-url", "deny-url", "disable-mcp-server",
                "add-github-mcp-tool", "add-github-mcp-toolset", "secret-env-vars")) {
            appendRepeated(cmd, "--" + key, request.option(key));
        }
        for (String key : List.of("allow-all", "allow-all-paths", "allow-all-tools",
                "allow-all-urls", "allow-all-mcp-server-instructions", "autopilot",
                "disable-builtin-mcps", "disallow-temp-dir", "enable-all-github-mcp-tools",
                "enable-memory", "enable-reasoning-summaries", "experimental", "no-auto-update",
                "no-bash-env", "no-custom-instructions", "no-remote", "no-remote-export",
                "plain-diff", "plan", "screen-reader", "yolo")) {
            appendFlag(cmd, "--" + key, request.option(key));
        }
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
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> appendValue(cmd, flag, item));
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

    private static Map<String, String> copilotEnvironment(Path copilotHome, TurnRequest request) {
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("COPILOT_HOME", copilotHome.toString());
        environment.putAll(request.env());
        return environment;
    }

    /** Builds a throwaway COPILOT_HOME: copied auth/config + generated mcp-config.json. */
    private Path prepareCopilotHome(TurnRequest request) throws IOException {
        Path home = Files.createTempDirectory("cowork-copilot-home-");
        Path realHome = Path.of(System.getProperty("user.home"), ".copilot");
        if (Files.isDirectory(realHome)) {
            try (var files = Files.list(realHome)) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(source, home.resolve(source.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (request.mcpUrl() != null && request.mcpToken() != null) {
            Files.writeString(home.resolve("mcp-config.json"),
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(McpConfigs.serversMap(request, "local")));
        }
        return home;
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
