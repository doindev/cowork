package dev.cowork.mcp;

import dev.cowork.conversation.Participant;

/**
 * Identity of the agent making the current MCP tool call, established by
 * {@link McpIdentityFilter} from the bearer token. Tool handlers run synchronously on
 * the HTTP request thread, so a ThreadLocal is sufficient.
 */
public final class McpCallerContext {

    private static final ThreadLocal<Participant> CALLER = new ThreadLocal<>();

    private McpCallerContext() {
    }

    static void set(Participant participant) {
        CALLER.set(participant);
    }

    static void clear() {
        CALLER.remove();
    }

    public static Participant require() {
        Participant participant = CALLER.get();
        if (participant == null) {
            throw new IllegalStateException("No authenticated MCP caller on this thread");
        }
        return participant;
    }
}
