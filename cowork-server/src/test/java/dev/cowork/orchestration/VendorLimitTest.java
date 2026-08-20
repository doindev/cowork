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

    /** Per-model ceilings word it differently again — this one cost inv-ui a whole turn. */
    @Test
    void recognizesPerModelLimits() {
        assertTrue(AgentTurnService.isVendorLimit("claude reported an error: You've reached your Fable 5 "
                + "limit. Switch to another model, or manage usage credits at "
                + "claude.ai/settings/usage?from=cc_cli_limit_message, to continue."));
        // Claude Code often renders the apostrophe as a right single quote.
        assertTrue(AgentTurnService.isVendorLimit("You’ve reached your Opus 5 limit."));
        assertTrue(AgentTurnService.isVendorLimit("You've reached your usage limit for this model"));
    }

    @Test
    void ordinaryFailuresAreNotLimits() {
        assertFalse(AgentTurnService.isVendorLimit("codex exited with code 1: compile error"));
        assertFalse(AgentTurnService.isVendorLimit("turn timed out after 15 minutes"));
        assertFalse(AgentTurnService.isVendorLimit(null));
        assertFalse(AgentTurnService.isVendorLimit(""));
    }
}
