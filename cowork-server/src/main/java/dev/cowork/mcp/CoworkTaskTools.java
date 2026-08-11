package dev.cowork.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.Participant;
import dev.cowork.project.Project;
import dev.cowork.project.ProjectService;
import dev.cowork.project.ProjectTask;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Project-task MCP tools. Tasks live on the conversation's project (created on demand). */
@Component
public class CoworkTaskTools {

    public record TaskRecord(UUID id, String title, String description, String status, UUID assigneeAgentId,
                             int ordinal, Instant createdAt) {

        static TaskRecord of(ProjectTask t) {
            return new TaskRecord(t.getId(), t.getTitle(), t.getDescription(), t.getStatus().name(),
                    t.getAssigneeAgentId(), t.getOrdinal(), t.getCreatedAt());
        }
    }

    private final ProjectService projectService;
    private final ConversationRepository conversations;
    private final dev.cowork.stream.SseHub sseHub;

    public CoworkTaskTools(ProjectService projectService, ConversationRepository conversations,
                           dev.cowork.stream.SseHub sseHub) {
        this.projectService = projectService;
        this.conversations = conversations;
        this.sseHub = sseHub;
    }

    @McpTool(name = "create_task", description = """
            Create a project task for the plan being discussed. Tasks start as PROPOSED; assignment \
            happens through a TASK_ASSIGNMENT proposal that the team votes on.""")
    public TaskRecord createTask(
            @McpToolParam(required = true, description = "Short task title") String title,
            @McpToolParam(required = false, description = "What the task involves") String description) {
        Conversation conversation = callerConversation();
        Project project = projectService.ensureProject(conversation);
        TaskRecord record = TaskRecord.of(projectService.createTask(project, title, description));
        sseHub.publish(conversation.getId(), "task", record);
        return record;
    }

    @McpTool(name = "list_tasks", description = "List the tasks of this conversation's project, in order.")
    public List<TaskRecord> listTasks() {
        Conversation conversation = callerConversation();
        if (conversation.getProjectId() == null) {
            return List.of();
        }
        return projectService.tasksOf(conversation.getProjectId()).stream().map(TaskRecord::of).toList();
    }

    @McpTool(name = "update_task_status", description = """
            Update a task's status. Statuses: PROPOSED, APPROVED, IN_PROGRESS, IN_REVIEW, DONE. \
            Set IN_PROGRESS when you start implementing an assigned task, IN_REVIEW when ready for \
            review, DONE after the team accepts it.""")
    public TaskRecord updateTaskStatus(
            @McpToolParam(required = true, description = "Task id (UUID)") String taskId,
            @McpToolParam(required = true, description = "New status") String status) {
        Conversation conversation = callerConversation();
        TaskRecord record = TaskRecord.of(projectService.updateTaskStatus(UUID.fromString(taskId),
                ProjectTask.Status.valueOf(status.trim().toUpperCase()), null));
        sseHub.publish(conversation.getId(), "task", record);
        return record;
    }

    private Conversation callerConversation() {
        Participant caller = McpCallerContext.require();
        return conversations.findById(caller.getConversationId())
                .orElseThrow(() -> new IllegalStateException("Caller's conversation no longer exists"));
    }
}
