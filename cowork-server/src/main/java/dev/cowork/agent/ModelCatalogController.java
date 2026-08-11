package dev.cowork.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live model discovery for the agent editor's Ctrl+Space completion. The CLIs expose no
 * model-listing command, so this polls the provider APIs when credentials are present
 * (ANTHROPIC_API_KEY / OPENAI_API_KEY) and reports "live: false" otherwise, letting the
 * frontend fall back to its curated list. Results are cached for an hour.
 */
@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(1);

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
            case "codex" -> fetchOpenAi();
            default -> new ModelCatalog(false, List.of());
        };
        cache.put(key, new CacheEntry(catalog, Instant.now()));
        return catalog;
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

    private ModelCatalog fetchOpenAi() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("CODEX_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return new ModelCatalog(false, List.of());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OpenAI models API returned {}", response.statusCode());
                return new ModelCatalog(false, List.of());
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            List<ModelInfo> models = new ArrayList<>();
            for (JsonNode model : data) {
                String id = model.path("id").asText("");
                // The full OpenAI catalog includes embeddings/audio/etc — keep coding models.
                if (id.startsWith("gpt-") || id.startsWith("o") || id.contains("codex")) {
                    models.add(new ModelInfo(id, ""));
                }
            }
            models.sort((a, b) -> a.value().compareTo(b.value()));
            return new ModelCatalog(!models.isEmpty(), models);
        } catch (Exception e) {
            log.warn("OpenAI model listing failed: {}", e.getMessage());
            return new ModelCatalog(false, List.of());
        }
    }
}
