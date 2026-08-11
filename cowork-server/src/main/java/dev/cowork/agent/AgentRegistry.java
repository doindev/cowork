package dev.cowork.agent;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import dev.cowork.config.CoworkProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Scans the agents directory for {@code *-agent.md} files, mirrors them into the
 * {@code agent_def} table, and hot-reloads on file changes.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AgentDefRepository repository;
    private final Path agentsDir;
    private volatile WatchService watchService;
    private Thread watchThread;

    public AgentRegistry(AgentDefRepository repository, CoworkProperties properties) {
        this.repository = repository;
        this.agentsDir = Path.of(properties.agentsDir()).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            Files.createDirectories(agentsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create agents directory " + agentsDir, e);
        }
        rescan();
        startWatcher();
    }

    public List<AgentDef> allEnabled() {
        return repository.findByEnabledTrue();
    }

    public List<AgentDef> all() {
        return repository.findAll();
    }

    /** Path of the definition file for an agent name (whether or not it exists yet). */
    public Path definitionPath(String name) {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid agent name (letters, digits, _ and - only)");
        }
        return agentsDir.resolve(name + "-agent.md");
    }

    public String readDefinition(String name) {
        try {
            return Files.readString(definitionPath(name));
        } catch (IOException e) {
            throw new IllegalArgumentException("No definition file for agent '" + name + "'");
        }
    }

    /**
     * Validates and writes an agent definition file, then rescans. The frontmatter
     * {@code name} must match the file's name so the registry stays consistent.
     */
    public synchronized void saveDefinition(String name, String content) {
        Path target = definitionPath(name);
        try {
            Path temp = Files.createTempFile("cowork-agent-validate-", ".md");
            try {
                Files.writeString(temp, content);
                var parsed = AgentFileParser.parse(temp)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Definition is not valid: it needs '---' YAML frontmatter with at least "
                                        + "'name' and 'cli' (claude | codex | copilot), then the persona text."));
                if (!parsed.name().equals(name)) {
                    throw new IllegalArgumentException("Frontmatter name '" + parsed.name()
                            + "' must match the agent name '" + name + "' (rename = create new + delete old).");
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + target + ": " + e.getMessage(), e);
        }
        rescan();
    }

    public synchronized void deleteDefinition(String name) {
        try {
            if (!Files.deleteIfExists(definitionPath(name))) {
                throw new IllegalArgumentException("No definition file for agent '" + name + "'");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete definition for '" + name + "': " + e.getMessage(), e);
        }
        rescan();
    }

    public synchronized void rescan() {
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(f -> f.getFileName().toString().endsWith("-agent.md"))
                    .sorted()
                    .forEach(file -> AgentFileParser.parse(file).ifPresent(parsed -> {
                        upsert(parsed, file);
                        seen.add(parsed.name());
                    }));
        } catch (IOException e) {
            log.error("Failed to scan agents directory {}", agentsDir, e);
            return;
        }
        for (AgentDef def : repository.findAll()) {
            if (!seen.contains(def.getName()) && def.isEnabled()) {
                def.setEnabled(false);
                def.setUpdatedAt(Instant.now());
                repository.save(def);
                log.info("Disabled agent '{}' (definition file removed)", def.getName());
            }
        }
        log.info("Agent registry scan complete: {} agent(s) active", seen.size());
    }

    private void upsert(AgentFileParser.ParsedAgent parsed, Path file) {
        AgentDef def = repository.findByName(parsed.name()).orElseGet(AgentDef::new);
        def.setName(parsed.name());
        def.setCliType(parsed.cliType());
        def.setModel(parsed.model());
        def.setOptions(parsed.options());
        def.setDescription(parsed.description());
        def.setPersona(parsed.persona());
        def.setFilePath(file.toString());
        def.setEnabled(true);
        def.setUpdatedAt(Instant.now());
        repository.save(def);
    }

    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            agentsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.error("Cannot watch agents directory {} — hot reload disabled", agentsDir, e);
            return;
        }
        watchThread = Thread.ofVirtual().name("agent-file-watcher").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var key = watchService.take();
                    // Debounce: editors fire bursts of events per save.
                    TimeUnit.MILLISECONDS.sleep(500);
                    key.pollEvents();
                    key.reset();
                    while (true) {
                        var more = watchService.poll();
                        if (more == null) {
                            break;
                        }
                        more.pollEvents();
                        more.reset();
                    }
                    rescan();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.nio.file.ClosedWatchServiceException e) {
                    return;
                }
            }
        });
    }

    @PreDestroy
    void stop() throws IOException {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            watchService.close();
        }
    }
}
