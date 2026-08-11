package dev.cowork.cli;

/** Receives live events while a CLI turn is running. All methods are optional. */
public interface TurnListener {

    TurnListener NOOP = new TurnListener() {
    };

    /** The agent's reply text accumulated so far (called as new text arrives). */
    default void onPartialText(String textSoFar) {
    }

    /** A tool/command invocation observed during the turn. */
    default void onActivity(String tool, String summary) {
    }

    /** The subprocess started; the handle allows cancellation. */
    default void onProcessStart(Process process) {
    }
}
