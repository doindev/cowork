package dev.cowork.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds MCP client configuration for the CLIs. Claude and Copilot share the
 * {@code {"mcpServers": {...}}} JSON shape; Codex uses TOML-style {@code -c} overrides.
 */
public final class McpConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpConfigs() {
    }

    public static Map<String, Object> serversMap(TurnRequest request, String stdioType) {
        Map<String, Object> servers = new LinkedHashMap<>();
        Map<String, Object> cowork = new LinkedHashMap<>();
        cowork.put("type", "http");
        cowork.put("url", request.mcpUrl());
        cowork.put("headers", Map.of("Authorization", "Bearer " + request.mcpToken()));
        servers.put("cowork", cowork);
        if (request.includeChromeDevtools()) {
            Map<String, Object> devtools = new LinkedHashMap<>();
            devtools.put("type", stdioType);
            devtools.put("command", "npx");
            devtools.put("args", List.of("-y", "chrome-devtools-mcp@latest"));
            servers.put("chrome-devtools", devtools);
        }
        return Map.of("mcpServers", servers);
    }

    /** Writes the Claude/Copilot-shaped JSON config to a temp file and returns its path. */
    public static Path writeJsonConfig(TurnRequest request, String stdioType, String filePrefix) throws IOException {
        Path file = Files.createTempFile(filePrefix, ".json");
        MAPPER.writeValue(file.toFile(), serversMap(request, stdioType));
        file.toFile().deleteOnExit();
        return file;
    }
}
