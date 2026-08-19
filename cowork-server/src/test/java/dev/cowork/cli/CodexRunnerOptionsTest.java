package dev.cowork.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexRunnerOptionsTest {

    @Test
    void mapsCodexAgentOptionsToCliArguments() {
        TurnRequest request = new TurnRequest("agent", "participant-1", "prompt", null, null, null, null,
                null, null, false, Map.of("effort", "xhigh", "enable", "feature-a,feature-b",
                        "ephemeral", true, "ignore-rules", false), Duration.ofMinutes(1));
        List<String> global = new ArrayList<>();
        List<String> exec = new ArrayList<>();

        CodexRunner.appendCodexGlobalOptions(global, request);
        CodexRunner.appendCodexExecOptions(exec, request);

        assertEquals(List.of("--ask-for-approval", "never",
                "--config", "model_reasoning_effort=\"xhigh\"",
                "--enable", "feature-a", "--enable", "feature-b"), global);
        assertEquals(List.of("--ephemeral"), exec);
    }

    @Test
    void bypassReplacesSandboxAndApprovalFlags() {
        TurnRequest request = new TurnRequest("agent", "participant-1", "prompt", null, null, null, null,
                null, null, false, Map.of("dangerously-bypass-approvals-and-sandbox", true,
                        "approve-for-me", true), Duration.ofMinutes(1));
        List<String> global = new ArrayList<>();

        CodexRunner.appendCodexGlobalOptions(global, request);

        assertEquals(List.of("--dangerously-bypass-approvals-and-sandbox"), global);
    }
}
