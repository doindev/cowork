package dev.cowork.rtk;

import java.util.List;

/**
 * Token savings rtk realized inside one conversation's workspace.
 *
 * <p>Counts only commands the agents actually routed through rtk: it is savings
 * <em>realized</em>, not savings available. {@code inputTokens} is what the raw command
 * output would have cost, {@code outputTokens} what the filtered output actually cost.
 *
 * @param available whether rtk is installed and produced readable data
 */
public record RtkSavings(boolean available, int commands, long inputTokens, long outputTokens,
                         long savedTokens, double savingsPct, long totalTimeMs, List<DayStat> daily) {

    public record DayStat(String date, int commands, long inputTokens, long outputTokens,
                          long savedTokens, double savingsPct) {
    }

    public static RtkSavings unavailable() {
        return new RtkSavings(false, 0, 0, 0, 0, 0, 0, List.of());
    }
}
