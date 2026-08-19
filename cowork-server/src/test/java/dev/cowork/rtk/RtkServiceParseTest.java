package dev.cowork.rtk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtkServiceParseTest {

    /** Verbatim output of `rtk gain --project --daily --format json` (rtk 0.45.0). */
    private static final String REAL_OUTPUT = """
            {
              "summary": {
                "total_commands": 2,
                "total_input": 53,
                "total_output": 11,
                "total_saved": 42,
                "avg_savings_pct": 79.24528301886792,
                "total_time_ms": 194,
                "avg_time_ms": 97
              },
              "daily": [
                {
                  "date": "2026-08-19",
                  "commands": 2,
                  "input_tokens": 53,
                  "output_tokens": 11,
                  "saved_tokens": 42,
                  "savings_pct": 79.24528301886792,
                  "total_time_ms": 194,
                  "avg_time_ms": 97
                }
              ]
            }
            """;

    @Test
    void parsesSummaryAndDailyBreakdown() {
        RtkSavings savings = RtkService.parse(REAL_OUTPUT);

        assertTrue(savings.available());
        assertEquals(2, savings.commands());
        assertEquals(53, savings.inputTokens());
        assertEquals(11, savings.outputTokens());
        assertEquals(42, savings.savedTokens());
        assertEquals(79.245, savings.savingsPct(), 0.001);
        assertEquals(194, savings.totalTimeMs());
        assertEquals(1, savings.daily().size());
        assertEquals("2026-08-19", savings.daily().getFirst().date());
        assertEquals(42, savings.daily().getFirst().savedTokens());
    }

    @Test
    void emptyLedgerIsAvailableButZero() {
        RtkSavings savings = RtkService.parse("""
                {"summary": {"total_commands": 0, "total_input": 0, "total_output": 0,
                 "total_saved": 0, "avg_savings_pct": 0.0, "total_time_ms": 0, "avg_time_ms": 0}}
                """);

        assertTrue(savings.available());
        assertEquals(0, savings.commands());
        assertTrue(savings.daily().isEmpty());
    }

    @Test
    void garbageOrEmptyOutputIsUnavailableNotAnError() {
        assertFalse(RtkService.parse("").available());
        assertFalse(RtkService.parse(null).available());
        assertFalse(RtkService.parse("No tracking data yet.").available());
        assertFalse(RtkService.parse("{\"unexpected\": true}").available());
    }
}
