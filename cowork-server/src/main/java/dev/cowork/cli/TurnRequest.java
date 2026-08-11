package dev.cowork.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * One headless CLI invocation on behalf of an agent.
 *
 * @param agentName        display name of the agent taking the turn
 * @param prompt           the delta transcript + instructions fed on stdin
 * @param persona          system-prompt persona text (may be null)
 * @param model            model override from the agent definition (may be null)
 * @param sessionId        CLI session to resume (null on the first turn)
 * @param workDir          working directory for the subprocess
 * @param mcpUrl           URL of the embedded cowork MCP server
 * @param mcpToken         per-turn bearer token identifying this participant
 * @param includeChromeDevtools whether to attach the chrome-devtools MCP server
 * @param options          agent-definition options (permission mode overrides etc.)
 * @param timeout          hard per-turn timeout
 */
public record TurnRequest(
        String agentName,
        String prompt,
        String persona,
        String model,
        String sessionId,
        Path workDir,
        String mcpUrl,
        String mcpToken,
        boolean includeChromeDevtools,
        Map<String, Object> options,
        Duration timeout) {

    public Object option(String key) {
        return options == null ? null : options.get(key);
    }
}
