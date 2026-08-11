package dev.cowork.cli;

/**
 * Outcome of a headless CLI turn.
 *
 * @param text      the agent's final reply text (empty if it only used tools)
 * @param sessionId CLI session id to resume next turn (null if the CLI doesn't expose one)
 * @param costUsd   reported cost of the turn, if the CLI provides it
 * @param activity  JSON array of tool/command activity captured during the turn (may be null)
 */
public record TurnResult(String text, String sessionId, Double costUsd, String activity) {

    public TurnResult(String text, String sessionId, Double costUsd) {
        this(text, sessionId, costUsd, null);
    }
}
