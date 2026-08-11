package dev.cowork.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexRunnerParseTest {

    @Test
    void parsesThreadIdAndFinalAgentMessage() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"th_123"}
                {"type":"turn.started"}
                {"type":"item.completed","item":{"item_type":"command_execution","command":"ls"}}
                {"type":"item.completed","item":{"item_type":"agent_message","text":"first draft"}}
                {"type":"item.completed","item":{"item_type":"agent_message","text":"final answer"}}
                {"type":"turn.completed","usage":{"input_tokens":10}}
                """;
        TurnResult result = CodexRunner.parseJsonl(jsonl);
        assertEquals("th_123", result.sessionId());
        assertEquals("final answer", result.text());
    }

    @Test
    void supportsTypeFieldVariant() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"th_9"}
                {"type":"item.completed","item":{"type":"agent_message","text":"hi"}}
                """;
        TurnResult result = CodexRunner.parseJsonl(jsonl);
        assertEquals("hi", result.text());
    }

    @Test
    void surfacesTurnFailure() {
        String jsonl = """
                {"type":"thread.started","thread_id":"th_1"}
                {"type":"turn.failed","error":{"message":"quota exceeded"}}
                """;
        assertThrows(CliTurnException.class, () -> CodexRunner.parseJsonl(jsonl));
    }

    @Test
    void toleratesNoiseLines() throws Exception {
        String jsonl = """
                some banner text
                {"type":"item.completed","item":{"item_type":"agent_message","text":"ok"}}
                """;
        assertEquals("ok", CodexRunner.parseJsonl(jsonl).text());
    }

    @Test
    void collectsActivityFromCommandAndFileItems() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"th_5"}
                {"type":"item.completed","item":{"item_type":"command_execution","command":"npm test"}}
                {"type":"item.completed","item":{"item_type":"file_change","path":"src/App.tsx"}}
                {"type":"item.completed","item":{"item_type":"agent_message","text":"done"}}
                """;
        TurnResult result = CodexRunner.parseJsonl(jsonl);
        assertEquals("done", result.text());
        String activity = result.activity();
        org.junit.jupiter.api.Assertions.assertNotNull(activity);
        org.junit.jupiter.api.Assertions.assertTrue(activity.contains("npm test"));
        org.junit.jupiter.api.Assertions.assertTrue(activity.contains("src/App.tsx"));
    }
}
