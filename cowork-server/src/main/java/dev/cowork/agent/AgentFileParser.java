package dev.cowork.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses {@code <name>-agent.md} files: YAML frontmatter delimited by {@code ---} lines,
 * followed by a markdown persona body.
 */
public final class AgentFileParser {

    private static final Logger log = LoggerFactory.getLogger(AgentFileParser.class);

    private AgentFileParser() {
    }

    public record ParsedAgent(String name, CliType cliType, String model, String options,
                              String description, String persona) {
    }

    public static Optional<ParsedAgent> parse(Path file) {
        try {
            String raw = Files.readString(file);
            String[] parts = splitFrontmatter(raw);
            if (parts == null) {
                log.warn("Agent file {} has no YAML frontmatter, skipping", file);
                return Optional.empty();
            }
            Map<String, Object> meta = new Yaml().load(parts[0]);
            if (meta == null || meta.get("name") == null || meta.get("cli") == null) {
                log.warn("Agent file {} is missing required 'name' or 'cli' frontmatter keys, skipping", file);
                return Optional.empty();
            }
            String name = meta.get("name").toString().trim();
            if (!name.matches("[A-Za-z0-9_-]+")) {
                log.warn("Agent file {} has invalid name '{}' (must be alphanumeric/_/-), skipping", file, name);
                return Optional.empty();
            }
            CliType cliType = CliType.valueOf(meta.get("cli").toString().trim().toUpperCase());
            String model = meta.get("model") == null ? null : meta.get("model").toString();
            String description = meta.get("description") == null ? null : meta.get("description").toString();
            Object optionsObj = meta.get("options");
            String options = optionsObj == null ? null : new Yaml().dump(optionsObj);
            return Optional.of(new ParsedAgent(name, cliType, model, options, description, parts[1].trim()));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to parse agent file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static String[] splitFrontmatter(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---")) {
            return null;
        }
        int end = normalized.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        String yaml = normalized.substring(normalized.indexOf('\n') + 1, end + 1);
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
        return new String[] {yaml, body};
    }
}
