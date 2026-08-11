package dev.cowork.agent;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    public record AgentView(UUID id, String name, String cliType, String model, String description, boolean enabled) {

        static AgentView of(AgentDef def) {
            return new AgentView(def.getId(), def.getName(), def.getCliType().name(), def.getModel(),
                    def.getDescription(), def.isEnabled());
        }
    }

    private final AgentRegistry registry;
    private final AgentAssistService assist;

    public AgentController(AgentRegistry registry, AgentAssistService assist) {
        this.registry = registry;
        this.assist = assist;
    }

    @GetMapping
    public List<AgentView> list() {
        return registry.all().stream().map(AgentView::of).toList();
    }

    @PostMapping("/rescan")
    public List<AgentView> rescan() {
        registry.rescan();
        return list();
    }

    public record DefinitionView(String name, String content) {
    }

    public record SaveDefinitionRequest(@NotBlank String content) {
    }

    public record CreateAgentRequest(@NotBlank String name, String content) {
    }

    @GetMapping("/{name}/definition")
    public DefinitionView definition(@PathVariable String name) {
        return new DefinitionView(name, registry.readDefinition(name));
    }

    @PutMapping("/{name}/definition")
    public DefinitionView saveDefinition(@PathVariable String name, @RequestBody SaveDefinitionRequest request) {
        registry.saveDefinition(name, request.content());
        return new DefinitionView(name, registry.readDefinition(name));
    }

    @PostMapping
    public DefinitionView create(@RequestBody CreateAgentRequest request) {
        String name = request.name().trim();
        String content = request.content() == null || request.content().isBlank()
                ? defaultTemplate(name)
                : request.content();
        registry.saveDefinition(name, content);
        return new DefinitionView(name, registry.readDefinition(name));
    }

    @DeleteMapping("/{name}")
    public List<AgentView> delete(@PathVariable String name) {
        registry.deleteDefinition(name);
        return list();
    }

    private static String defaultTemplate(String name) {
        return """
                ---
                name: %s
                cli: claude
                # model: claude-sonnet-4-5
                description: Describe this agent in one line
                ---
                You are "%s", an AI agent participating in a team chat room with a human user and other AI agents.

                Describe the agent's specialty, working style, and how it should collaborate here.
                Keep chat messages concise.
                """.formatted(name, name);
    }

    public record AssistRequest(String name, String content, @NotBlank String message, String sessionId) {
    }

    public record AssistResponse(String reply, String updatedContent, String sessionId) {
    }

    @PostMapping("/assist")
    public AssistResponse assist(@RequestBody AssistRequest request) {
        try {
            var result = assist.chat(request.name(), request.content(), request.message(), request.sessionId());
            return new AssistResponse(result.reply(), result.updatedContent(), result.sessionId());
        } catch (dev.cowork.cli.CliTurnException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> badRequest(IllegalArgumentException e) {
        return java.util.Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public java.util.Map<String, String> unavailable(IllegalStateException e) {
        return java.util.Map.of("message", e.getMessage());
    }
}
