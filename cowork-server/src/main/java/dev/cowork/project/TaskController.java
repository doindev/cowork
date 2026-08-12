package dev.cowork.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.agent.AgentDefRepository;
import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}/tasks")
public class TaskController {

    public record TaskView(UUID id, String title, String description, String status, String assignee,
                           int ordinal, Instant createdAt) {
    }

    public record PatchTaskRequest(String title, String description) {
    }

    private final ConversationService conversations;
    private final ProjectService projectService;
    private final AgentDefRepository agents;

    public TaskController(ConversationService conversations, ProjectService projectService,
                          AgentDefRepository agents) {
        this.conversations = conversations;
        this.projectService = projectService;
        this.agents = agents;
    }

    @GetMapping
    public List<TaskView> list(@PathVariable UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation.getProjectId() == null) {
            return List.of();
        }
        return projectService.tasksOf(conversation.getProjectId()).stream()
                .map(this::toView)
                .toList();
    }

    /** Edit a task's title/description; rejected with 409 once work has started. */
    @PatchMapping("/{taskId}")
    public TaskView patch(@PathVariable UUID conversationId, @PathVariable UUID taskId,
                          @RequestBody PatchTaskRequest request) {
        Conversation conversation = conversations.get(conversationId);
        boolean belongs = conversation.getProjectId() != null
                && projectService.tasksOf(conversation.getProjectId()).stream()
                        .anyMatch(t -> t.getId().equals(taskId));
        if (!belongs) {
            throw new IllegalArgumentException("Task does not belong to this conversation");
        }
        return toView(projectService.updateTaskContent(taskId, request.title(), request.description()));
    }

    private TaskView toView(ProjectTask t) {
        return new TaskView(t.getId(), t.getTitle(), t.getDescription(), t.getStatus().name(),
                t.getAssigneeAgentId() == null ? null
                        : agents.findById(t.getAssigneeAgentId()).map(a -> a.getName()).orElse(null),
                t.getOrdinal(), t.getCreatedAt());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> badRequest(IllegalArgumentException e) {
        return java.util.Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String, String> conflict(IllegalStateException e) {
        return java.util.Map.of("message", e.getMessage());
    }
}
