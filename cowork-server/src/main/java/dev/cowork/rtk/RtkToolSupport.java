package dev.cowork.rtk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.cowork.cli.CliBinaries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Which commands rtk can actually wrap on this machine.
 *
 * <p>rtk shells out to the real tool, so wrapping one that is not installed fails the
 * agent's command outright ({@code rtk cat file} on Windows: "Failed to resolve 'cat' via
 * PATH"). Two defences: a PATH check up front, and failures learned from CLI output at
 * runtime and remembered across restarts.
 */
@Service
public class RtkToolSupport {

    private static final Logger log = LoggerFactory.getLogger(RtkToolSupport.class);

    /** Tools the rtk skill may suggest that rtk runs by shelling out to a real binary. */
    private static final List<String> WRAPPABLE = List.of(
            "git", "gh", "ls", "cat", "grep", "rg", "find", "tree", "diff",
            "pytest", "jest", "vitest", "playwright", "go", "cargo", "rspec", "rake",
            "tsc", "eslint", "biome", "prettier", "ruff", "golangci-lint", "rubocop", "sbt",
            "npm", "pnpm", "yarn", "pip", "uv", "bundle", "prisma",
            "docker", "kubectl", "oc", "pulumi", "aws", "curl", "wget");

    /** rtk's own launch failures — the wrapped program never ran, so the tool is unusable. */
    private static final Pattern LAUNCH_FAILURE = Pattern.compile(
            "(?:Failed to resolve '([A-Za-z0-9._-]+)' via PATH"
                    + "|Failed to run ([A-Za-z0-9._-]+): Failed to spawn process)");

    private static final Duration PATH_CACHE_TTL = Duration.ofMinutes(5);

    private final Path learnedFile;
    private final Set<String> learned = new CopyOnWriteArraySet<>();
    private volatile Set<String> missingCache = Set.of();
    private volatile Instant missingReadAt = Instant.EPOCH;

    public RtkToolSupport() {
        this(Path.of(System.getProperty("user.home"), ".cowork", "rtk-unwrappable.txt"));
    }

    RtkToolSupport(Path learnedFile) {
        this.learnedFile = learnedFile;
        loadLearned();
    }

    /**
     * Records a tool rtk could not launch. Returns true when this is newly learned, so the
     * caller can log it once rather than on every occurrence.
     */
    public boolean recordFailure(String tool) {
        if (tool == null || tool.isBlank() || !learned.add(tool.trim().toLowerCase())) {
            return false;
        }
        persistLearned();
        log.info("rtk cannot wrap '{}' on this machine — agents will be told to run it plain", tool);
        return true;
    }

    /** Scans CLI output for rtk launch failures and records every tool named in it. */
    public void learnFrom(String cliOutput) {
        if (cliOutput == null || !cliOutput.contains("Failed to")) {
            return;
        }
        Matcher m = LAUNCH_FAILURE.matcher(cliOutput);
        while (m.find()) {
            recordFailure(m.group(1) != null ? m.group(1) : m.group(2));
        }
    }

    /** Tools that must not be wrapped: missing from PATH, or observed failing. */
    public Set<String> unwrappable() {
        Set<String> all = new TreeSet<>(missingFromPath());
        all.addAll(learned);
        return all;
    }

    /** A line for the agent prompt, or empty when everything the skill suggests works here. */
    public String promptNote() {
        Set<String> unwrappable = unwrappable();
        if (unwrappable.isEmpty()) {
            return "";
        }
        return "- NOT AVAILABLE on this machine — run these plain, never via rtk: "
                + String.join(", ", unwrappable) + ".\n";
    }

    private Set<String> missingFromPath() {
        if (Duration.between(missingReadAt, Instant.now()).compareTo(PATH_CACHE_TTL) < 0) {
            return missingCache;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String tool : WRAPPABLE) {
            if (!CliBinaries.onPath(tool)) {
                missing.add(tool);
            }
        }
        missingCache = Set.copyOf(missing);
        missingReadAt = Instant.now();
        return missingCache;
    }

    private void loadLearned() {
        try {
            if (Files.isRegularFile(learnedFile)) {
                Files.readAllLines(learnedFile).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(learned::add);
            }
        } catch (IOException e) {
            log.debug("Could not read {}: {}", learnedFile, e.getMessage());
        }
    }

    private void persistLearned() {
        try {
            Files.createDirectories(learnedFile.getParent());
            Files.writeString(learnedFile,
                    "# Tools rtk failed to launch here; cowork tells agents to run them plain.\n"
                            + String.join("\n", new TreeSet<>(learned)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.debug("Could not persist {}: {}", learnedFile, e.getMessage());
        }
    }
}
