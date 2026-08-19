package dev.cowork.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopilotRunnerOptionsTest {

    @Test
    void appendsValueRepeatedAndBooleanOptions() {
        TurnRequest request = new TurnRequest("agent", "owner", "prompt", null, null, null,
                null, null, null, false, Map.of(
                        "effort", "high",
                        "context", "long_context",
                        "allow-tool", List.of("write", "shell(git:*)"),
                        "deny-url", "bad.example,tracking.example",
                        "experimental", true,
                        "allow-all-paths", false), Duration.ofMinutes(5));
        List<String> command = new ArrayList<>();

        CopilotRunner.appendCopilotOptions(command, request);

        assertEquals(List.of("--effort", "high", "--context", "long_context",
                "--allow-tool", "write", "--allow-tool", "shell(git:*)",
                "--deny-url", "bad.example", "--deny-url", "tracking.example",
                "--experimental"), command);
    }
}
