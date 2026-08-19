package dev.cowork.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendorLimitTest {

    @Test
    void recognizesClaudeSessionAndUsageLimits() {
        assertTrue(AgentTurnService.isVendorLimit(
                "claude reported an error: You've hit your session limit · resets 3:20pm (America/Chicago)"));
        assertTrue(AgentTurnService.isVendorLimit("usage limit reached|1755630000"));
        assertTrue(AgentTurnService.isVendorLimit("Rate limit exceeded, try again later"));
    }

    @Test
    void ordinaryFailuresAreNotLimits() {
        assertFalse(AgentTurnService.isVendorLimit("codex exited with code 1: compile error"));
        assertFalse(AgentTurnService.isVendorLimit("turn timed out after 15 minutes"));
        assertFalse(AgentTurnService.isVendorLimit(null));
        assertFalse(AgentTurnService.isVendorLimit(""));
    }
}
