package dev.cowork.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cowork.cli.CliBinaries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live model discovery for the agent editor's Ctrl+Space completion. */
@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final Pattern COPILOT_MODEL = Pattern.compile(
            "(?i)(?<![a-z0-9._/-])(auto|(?:claude|gpt|gemini|mai|kimi|o[1-9])[a-z0-9._/-]*)(?![a-z0-9._/-])");

    public record ModelInfo(String value, String hint) {
    }

    public record ModelCatalog(boolean live, List<ModelInfo> models) {
    }

    private record CacheEntry(ModelCatalog catalog, Instant fetchedAt) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @GetMapping("/{cli}")
    public ModelCatalog list(@PathVariable String cli) {
        String key = cli.toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.catalog();
        }
        ModelCatalog catalog = switch (key) {
            case "claude" -> fetchAnthropic();
            case "codex" -> fetchCodex();
            case "copilot" -> fetchCopilot();
            default -> new ModelCatalog(false, List.of());
        };
        // Do not pin a failed/unavailable lookup for an hour. A CLI may be installed
        // or authenticated while the Manage Agents modal is open.
        if (catalog.live()) {
            cache.put(key, new CacheEntry(catalog, Instant.now()));
        }
        return catalog;
    }

    /** Reads model IDs from the --model description exposed by `copilot help`. */
    private ModelCatalog fetchCopilot() {
        if (!CliBinaries.onPath("copilot")) {
            return new ModelCatalog(false, List.of());
        }
        List<String> command = new ArrayList<>();
        if (CliBinaries.needsCmdWrapper("copilot")) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(CliBinaries.resolve("copilot"));
        command.add("help");

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            FutureTask<String> outputFuture = new FutureTask<>(() -> {
                try (var output = running.getInputStream()) {
                    return new String(output.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            });
            Thread.ofVirtual().start(outputFuture);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for Copilot help");
            }
            List<ModelInfo> models = parseCopilotModels(outputFuture.get(2, TimeUnit.SECONDS));
            boolean hasSelectableModel = models.stream().anyMatch(model -> !model.value().equals("auto"));
            return new ModelCatalog(hasSelectableModel, hasSelectableModel ? models : List.of());
        } catch (Exception e) {
            log.warn("Copilot CLI model listing failed: {}", e.getMessage());
            return new ModelCatalog(false, List.of());
        } finally {
            stop(process);
        }
    }

    static List<ModelInfo> parseCopilotModels(String helpOutput) {
        String help = ANSI_ESCAPE.matcher(helpOutput == null ? "" : helpOutput).replaceAll("");
        int modelOption = help.toLowerCase(Locale.ROOT).indexOf("--model");
        if (modelOption < 0) {
            return List.of();
        }
        int nextOption = help.length();
        Matcher optionMatcher = Pattern.compile("(?m)^\\s{0,8}--[a-z][a-z0-9-]*").matcher(help);
        while (optionMatcher.find()) {
            if (optionMatcher.start() > modelOption) {
                nextOption = optionMatcher.start();
                break;
            }
        }
        String block = help.substring(modelOption, nextOption);
        List<ModelInfo> models = new ArrayList<>();
        COPILOT_MODEL.matcher(block).results()
                .map(match -> match.group(1).toLowerCase(Locale.ROOT))
                .distinct()
                .forEach(value -> models.add(new ModelInfo(value,
                        value.equals("auto") ? "Copilot chooses the model" : "GitHub Copilot CLI")));
        return models;
    }

    private ModelCatalog fetchAnthropic() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return new ModelCatalog(false, List.of());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=100"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Anthropic models API returned {}", response.statusCode());
                return new ModelCatalog(false, List.of());
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            List<ModelInfo> models = new ArrayList<>();
            for (JsonNode model : data) {
                String id = model.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(new ModelInfo(id, model.path("display_name").asText("")));
                }
            }
            return new ModelCatalog(!models.isEmpty(), models);
        } catch (Exception e) {
            log.warn("Anthropic model listing failed: {}", e.getMessage());
            return new ModelCatalog(false, List.of());
        }
    }

    /** Reads the same authenticated, filtered catalog displayed by the Codex model picker. */
    private ModelCatalog fetchCodex() {
        if (!CliBinaries.onPath("codex")) {
            return new ModelCatalog(false, List.of());
        }
        List<String> command = new ArrayList<>();
        if (CliBinaries.needsCmdWrapper("codex")) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(CliBinaries.resolve("codex"));
        command.add("app-server");
        command.add("--listen");
        command.add("stdio://");

        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
            Process running = process;
            Thread.ofVirtual().start(() -> readLines(running, stdout));
            Thread.ofVirtual().start(() -> drainErrors(running));

            try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8))) {
                send(input, "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":"
                        + "{\"name\":\"cowork\",\"version\":\"0.1.0\"}}}");
                awaitResponse(stdout, 1);
                send(input, "{\"method\":\"initialized\"}");

                List<ModelInfo> models = new ArrayList<>();
                String cursor = null;
                int id = 2;
                do {
                    String params = cursor == null
                            ? "{\"limit\":100,\"includeHidden\":false}"
                            : "{\"limit\":100,\"includeHidden\":false,\"cursor\":"
                                    + MAPPER.writeValueAsString(cursor) + "}";
                    send(input, "{\"id\":" + id + ",\"method\":\"model/list\",\"params\":"
                            + params + "}");
                    JsonNode result = awaitResponse(stdout, id).path("result");
                    models.addAll(parseCodexModels(result));
                    cursor = result.path("nextCursor").isTextual()
                            ? result.path("nextCursor").asText() : null;
                    id++;
                } while (cursor != null && !cursor.isBlank());
                return new ModelCatalog(!models.isEmpty(), models);
            }
        } catch (Exception e) {
            log.warn("Codex CLI model listing failed: {}", e.getMessage());
            return new ModelCatalog(false, List.of());
        } finally {
            stop(process);
        }
    }

    static List<ModelInfo> parseCodexModels(JsonNode result) {
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : result.path("data")) {
            if (model.path("hidden").asBoolean(false)) {
                continue;
            }
            String value = model.path("model").asText(model.path("id").asText(""));
            if (value.isBlank()) {
                continue;
            }
            String displayName = model.path("displayName").asText("");
            String description = model.path("description").asText("");
            String hint = displayName;
            if (!description.isBlank() && !description.equals(displayName)) {
                hint += (hint.isBlank() ? "" : " · ") + description;
            }
            if (model.path("isDefault").asBoolean(false)) {
                hint += (hint.isBlank() ? "" : " · ") + "default";
            }
            models.add(new ModelInfo(value, hint));
        }
        return models;
    }

    private static void send(BufferedWriter input, String json) throws Exception {
        input.write(json);
        input.newLine();
        input.flush();
    }

    private static JsonNode awaitResponse(BlockingQueue<String> stdout, int id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            String line = stdout.poll(250, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            JsonNode response = MAPPER.readTree(line);
            if (response.path("id").asInt(-1) == id) {
                if (response.has("error")) {
                    throw new IllegalStateException(response.path("error").toString());
                }
                return response;
            }
        }
        throw new IllegalStateException("timed out waiting for Codex app-server response " + id);
    }

    private static void readLines(Process process, BlockingQueue<String> output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.offer(line);
            }
        } catch (Exception ignored) {
        }
    }

    private static void drainErrors(Process process) {
        try (var errors = process.getErrorStream()) {
            errors.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) {
        }
    }

    private static void stop(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
