package dev.cowork.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelCatalogControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesVisibleCodexModelsWithCliValueAndHints() throws Exception {
        var result = MAPPER.readTree("""
                {"data":[
                  {"id":"internal-a","model":"gpt-codex-a","displayName":"Codex A",
                   "description":"Best coding model","hidden":false,"isDefault":true},
                  {"id":"internal-b","model":"gpt-codex-b","displayName":"Codex B",
                   "description":"Hidden","hidden":true,"isDefault":false}
                ],"nextCursor":null}
                """);

        var models = ModelCatalogController.parseCodexModels(result);

        assertEquals(1, models.size());
        assertEquals("gpt-codex-a", models.getFirst().value());
        assertEquals("Codex A · Best coding model · default", models.getFirst().hint());
    }

    @Test
    void parsesCopilotModelsOnlyFromModelOptionBlock() {
        String help = """
                Usage: copilot [options]
                  --model <model>  Choose the AI model. Choices: "auto", "claude-sonnet-4.6",
                                   "gpt-5.4", "gemini-3.1-pro-preview", "mai-code-1-flash"
                  --prompt <text>  Prompt (for example, ask about gpt-4.1 documentation)
                """;

        var models = ModelCatalogController.parseCopilotModels(help);

        assertEquals(List.of("auto", "claude-sonnet-4.6", "gpt-5.4",
                        "gemini-3.1-pro-preview", "mai-code-1-flash"),
                models.stream().map(ModelCatalogController.ModelInfo::value).toList());
    }

    @Test
    void copilotParserHandlesAnsiAndMissingCatalog() {
        var models = ModelCatalogController.parseCopilotModels(
                "\u001b[36m  --model MODEL\u001b[0m  one of: claude-haiku-4.5, kimi-k2.7-code\n"
                        + "  --version  print version");

        assertEquals(List.of("claude-haiku-4.5", "kimi-k2.7-code"),
                models.stream().map(ModelCatalogController.ModelInfo::value).toList());
        assertEquals(List.of(), ModelCatalogController.parseCopilotModels("Usage: copilot"));
    }
}
