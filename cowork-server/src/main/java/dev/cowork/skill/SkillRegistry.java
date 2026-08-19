package dev.cowork.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import dev.cowork.config.CoworkProperties;
import dev.cowork.conversation.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads {@code <name>-skill.md} files from the skills directory (YAML frontmatter:
 * name, description, phases; body = the protocol text). Files are re-read on access,
 * so edits take effect on the next agent turn without a restart.
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Path skillsDir;

    public SkillRegistry(CoworkProperties properties) {
        this.skillsDir = Path.of(properties.skillsDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("Cannot create skills directory {}: {}", skillsDir, e.getMessage());
        }
    }

    public List<SkillDef> all() {
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(skillsDir)) {
            return files.filter(f -> f.getFileName().toString().endsWith("-skill.md"))
                    .sorted()
                    .map(SkillRegistry::parse)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            log.error("Failed to scan skills directory {}", skillsDir, e);
            return List.of();
        }
    }

    public Optional<SkillDef> byName(String name) {
        return all().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    static Optional<SkillDef> parse(Path file) {
        try {
            String normalized = Files.readString(file).replace("\r\n", "\n");
            if (!normalized.startsWith("---")) {
                log.warn("Skill file {} has no YAML frontmatter, skipping", file);
                return Optional.empty();
            }
            int end = normalized.indexOf("\n---", 3);
            if (end < 0) {
                log.warn("Skill file {} has unterminated frontmatter, skipping", file);
                return Optional.empty();
            }
            String yaml = normalized.substring(normalized.indexOf('\n') + 1, end + 1);
            int bodyStart = normalized.indexOf('\n', end + 1);
            String body = (bodyStart < 0 ? "" : normalized.substring(bodyStart + 1)).trim();

            Map<String, Object> meta = new Yaml().load(yaml);
            if (meta == null || meta.get("name") == null) {
                log.warn("Skill file {} is missing the required 'name' key, skipping", file);
                return Optional.empty();
            }
            String name = meta.get("name").toString().trim();
            if (!name.matches("[A-Za-z0-9_-]+")) {
                log.warn("Skill file {} has invalid name '{}', skipping", file, name);
                return Optional.empty();
            }
            String description = meta.get("description") == null ? "" : meta.get("description").toString();
            Set<Conversation.Phase> phases = EnumSet.noneOf(Conversation.Phase.class);
            if (meta.get("phases") instanceof List<?> list) {
                for (Object phase : list) {
                    try {
                        phases.add(Conversation.Phase.valueOf(phase.toString().trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Skill file {} lists unknown phase '{}', ignoring it", file, phase);
                    }
                }
            }
            return Optional.of(new SkillDef(name, description, phases, body));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
