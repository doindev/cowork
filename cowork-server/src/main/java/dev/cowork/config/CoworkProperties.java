package dev.cowork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cowork")
public record CoworkProperties(
        String agentsDir,
        String workspacesDir,
        String skillsDir,
        Cli cli,
        Mcp mcp) {

    public CoworkProperties {
        if (skillsDir == null || skillsDir.isBlank()) {
            skillsDir = "../skills";
        }
    }

    public record Cli(int maxConcurrent, int turnTimeoutSeconds) {
        public Cli {
            if (maxConcurrent <= 0) maxConcurrent = 4;
            if (turnTimeoutSeconds <= 0) turnTimeoutSeconds = 300;
        }
    }

    public record Mcp(String baseUrl) {
    }
}
