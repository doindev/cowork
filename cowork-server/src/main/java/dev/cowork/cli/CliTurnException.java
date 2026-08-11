package dev.cowork.cli;

public class CliTurnException extends Exception {

    public CliTurnException(String message) {
        super(message);
    }

    public CliTurnException(String message, Throwable cause) {
        super(message, cause);
    }
}
