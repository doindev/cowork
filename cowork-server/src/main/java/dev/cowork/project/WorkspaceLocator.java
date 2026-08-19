package dev.cowork.project;

import java.nio.file.Path;

import dev.cowork.config.CoworkProperties;
import dev.cowork.conversation.Conversation;
import org.springframework.stereotype.Service;

/**
 * Where a conversation's agents work on disk. Read-only: it never creates anything, so
 * query paths (e.g. reporting) can use it safely; the turn path creates the directory.
 */
@Service
public class WorkspaceLocator {

    private final CoworkProperties properties;
    private final ProjectRepository projects;

    public WorkspaceLocator(CoworkProperties properties, ProjectRepository projects) {
        this.properties = properties;
        this.projects = projects;
    }

    public Path locate(Conversation conversation) {
        Path base = Path.of(properties.workspacesDir()).toAbsolutePath().normalize();
        Path planning = base.resolve("_planning").resolve(conversation.getId().toString());
        if (conversation.getProjectId() == null) {
            return planning;
        }
        // A conversation with a project works in its workspace regardless of phase, so
        // agents can read uploaded implementation_docs while still planning.
        return projects.findById(conversation.getProjectId())
                .map(Project::getWorkspacePath)
                .map(Path::of)
                .orElse(planning);
    }
}
