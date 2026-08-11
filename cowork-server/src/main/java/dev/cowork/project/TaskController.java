package dev.cowork.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.agent.AgentDefRepository;
import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}/tasks")
public class TaskController {

    public record TaskView(UUID id, String title, String description, String status, String assignee,
                           int ordinal, Instant createdAt) {
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
                .map(t -> new TaskView(t.getId(), t.getTitle(), t.getDescription(), t.getStatus().name(),
                        t.getAssigneeAgentId() == null ? null
                                : agents.findById(t.getAssigneeAgentId()).map(a -> a.getName()).orElse(null),
                        t.getOrdinal(), t.getCreatedAt()))
                .toList();
    }
}
