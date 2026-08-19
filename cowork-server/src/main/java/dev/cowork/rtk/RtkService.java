package dev.cowork.rtk;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cowork.cli.CliBinaries;
import dev.cowork.cli.ProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads rtk's own savings ledger for a conversation. rtk records every wrapped command in a
 * local SQLite database tagged with the working directory it ran in; since each conversation
 * has its own workspace, {@code rtk gain --project} run there reports exactly that
 * conversation's savings.
 */
@Service
public class RtkService {

    private static final Logger log = LoggerFactory.getLogger(RtkService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Results are cached briefly: the UI polls, and each read spawns a subprocess. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record Cached(RtkSavings savings, Instant readAt) {
    }

    private final ProcessExecutor executor;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public RtkService(ProcessExecutor executor) {
        this.executor = executor;
    }

    public boolean available() {
        return CliBinaries.onPath("rtk");
    }

    /** Drops the cached reading so the next request re-reads the ledger (call after a turn). */
    public void invalidate(UUID conversationId) {
        cache.remove(conversationId);
    }

    public RtkSavings savingsFor(UUID conversationId, Path workDir) {
        Cached cached = cache.get(conversationId);
        if (cached != null && Duration.between(cached.readAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.savings();
        }
        RtkSavings savings = read(workDir);
        cache.put(conversationId, new Cached(savings, Instant.now()));
        return savings;
    }

    private RtkSavings read(Path workDir) {
        if (!available() || workDir == null) {
            return RtkSavings.unavailable();
        }
        List<String> cmd = new ArrayList<>();
        if (CliBinaries.needsCmdWrapper("rtk")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(CliBinaries.resolve("rtk"));
        cmd.add("gain");
        cmd.add("--project");
        cmd.add("--daily");
        cmd.add("--format");
        cmd.add("json");
        try {
            ProcessExecutor.ExecResult result = executor.run(cmd, workDir, Map.of(), null, TIMEOUT);
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("rtk gain failed (exit {}): {}", result.exitCode(), result.stderr());
                return RtkSavings.unavailable();
            }
            return parse(result.stdout());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Could not read rtk savings: {}", e.getMessage());
            return RtkSavings.unavailable();
        }
    }

    /** Parses {@code rtk gain --daily --format json}; unknown/missing fields degrade to zero. */
    static RtkSavings parse(String json) {
        if (json == null || json.isBlank()) {
            return RtkSavings.unavailable();
        }
        try {
            JsonNode root = MAPPER.readTree(json.trim());
            JsonNode summary = root.path("summary");
            if (summary.isMissingNode()) {
                return RtkSavings.unavailable();
            }
            List<RtkSavings.DayStat> daily = new ArrayList<>();
            for (JsonNode day : root.path("daily")) {
                daily.add(new RtkSavings.DayStat(
                        day.path("date").asText(""),
                        day.path("commands").asInt(),
                        day.path("input_tokens").asLong(),
                        day.path("output_tokens").asLong(),
                        day.path("saved_tokens").asLong(),
                        day.path("savings_pct").asDouble()));
            }
            return new RtkSavings(true,
                    summary.path("total_commands").asInt(),
                    summary.path("total_input").asLong(),
                    summary.path("total_output").asLong(),
                    summary.path("total_saved").asLong(),
                    summary.path("avg_savings_pct").asDouble(),
                    summary.path("total_time_ms").asLong(),
                    List.copyOf(daily));
        } catch (Exception e) {
            log.debug("Unparseable rtk gain output: {}", e.getMessage());
            return RtkSavings.unavailable();
        }
    }
}
