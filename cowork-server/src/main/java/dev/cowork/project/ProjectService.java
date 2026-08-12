package dev.cowork.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.cowork.config.CoworkProperties;
import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectTaskRepository tasks;
    private final ConversationRepository conversations;
    private final CoworkProperties properties;
    private final GitService git;

    public ProjectService(ProjectRepository projects, ProjectTaskRepository tasks,
                          ConversationRepository conversations, CoworkProperties properties,
                          GitService git) {
        this.projects = projects;
        this.tasks = tasks;
        this.conversations = conversations;
        this.properties = properties;
        this.git = git;
    }

    /** Returns the conversation's project, creating one (with its workspace dir) on first use. */
    @Transactional
    public Project ensureProject(Conversation conversation) {
        if (conversation.getProjectId() != null) {
            return projects.findById(conversation.getProjectId()).orElseThrow();
        }
        String slug = slugify(conversation.getTitle());
        String name = slug;
        int suffix = 2;
        while (projects.findByName(name).isPresent()) {
            name = slug + "-" + suffix++;
        }
        Path workspace = Path.of(properties.workspacesDir()).toAbsolutePath().normalize().resolve(name);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create workspace directory " + workspace, e);
        }
        git.ensureRepo(workspace);
        Project project = new Project();
        project.setName(name);
        project.setWorkspacePath(workspace.toString());
        project.setCreatedAt(Instant.now());
        project = projects.save(project);

        conversation.setProjectId(project.getId());
        conversations.save(conversation);
        return project;
    }

    /** Validates a user-supplied workspace directory; returns the normalized absolute path. */
    public static Path validateWorkspaceDir(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Workspace path is empty");
        }
        Path dir;
        try {
            dir = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Invalid workspace path: " + rawPath);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + dir);
        }
        return dir;
    }

    /**
     * Creates the conversation's project in a user-chosen existing directory instead of the
     * managed workspaces dir. Agents are then instructed to stay inside it.
     */
    @Transactional
    public Project createExternalProject(Conversation conversation, String rawPath) {
        if (conversation.getProjectId() != null) {
            throw new IllegalStateException("Conversation already has a project");
        }
        Path workspace = validateWorkspaceDir(rawPath);
        String slug = slugify(workspace.getFileName() == null
                ? "project" : workspace.getFileName().toString());
        String name = slug;
        int suffix = 2;
        while (projects.findByName(name).isPresent()) {
            name = slug + "-" + suffix++;
        }
        git.ensureRepo(workspace);
        Project project = new Project();
        project.setName(name);
        project.setWorkspacePath(workspace.toString());
        project.setExternal(true);
        project.setCreatedAt(Instant.now());
        project = projects.save(project);

        conversation.setProjectId(project.getId());
        conversations.save(conversation);
        return project;
    }

    @Transactional
    public ProjectTask createTask(Project project, String title, String description) {
        ProjectTask task = new ProjectTask();
        task.setProjectId(project.getId());
        task.setTitle(title);
        task.setDescription(description);
        task.setOrdinal(tasks.findByProjectIdOrderByOrdinal(project.getId()).size());
        task.setCreatedAt(Instant.now());
        return tasks.save(task);
    }

    public List<ProjectTask> tasksOf(UUID projectId) {
        return tasks.findByProjectIdOrderByOrdinal(projectId);
    }

    /** Edits a task's title/description. Only allowed before work starts (PROPOSED or APPROVED). */
    @Transactional
    public ProjectTask updateTaskContent(UUID taskId, String title, String description) {
        ProjectTask task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task " + taskId));
        if (task.getStatus() != ProjectTask.Status.PROPOSED
                && task.getStatus() != ProjectTask.Status.APPROVED) {
            throw new IllegalStateException(
                    "Task has already been started (" + task.getStatus() + ") and can no longer be edited");
        }
        if (title != null) {
            if (title.isBlank()) {
                throw new IllegalArgumentException("Task title cannot be blank");
            }
            task.setTitle(title.trim());
        }
        if (description != null) {
            task.setDescription(description);
        }
        return tasks.save(task);
    }

    @Transactional
    public ProjectTask updateTaskStatus(UUID taskId, ProjectTask.Status status, UUID assigneeAgentId) {
        ProjectTask task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task " + taskId));
        if (status != null) {
            task.setStatus(status);
        }
        if (assigneeAgentId != null) {
            task.setAssigneeAgentId(assigneeAgentId);
        }
        return tasks.save(task);
    }

    private static String slugify(String title) {
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "project" : (slug.length() > 40 ? slug.substring(0, 40) : slug);
    }
}
