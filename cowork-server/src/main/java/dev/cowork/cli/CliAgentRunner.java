package dev.cowork.cli;

import dev.cowork.agent.CliType;

/** Adapter SPI: one implementation per supported coding CLI. */
public interface CliAgentRunner {

    CliType type();

    /** Whether the CLI binary is available on this machine. */
    boolean available();

    /**
     * Whether the CLI supports resuming a session across invocations. Stateless CLIs get
     * the full conversation history each turn instead of a delta.
     */
    default boolean supportsSessions() {
        return true;
    }

    TurnResult run(TurnRequest request, TurnListener listener) throws CliTurnException;
}
