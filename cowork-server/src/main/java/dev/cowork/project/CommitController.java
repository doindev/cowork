package dev.cowork.project;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}/commits")
public class CommitController {

    public record CommitView(String hash, String author, Instant at, String message, String stat) {

        static CommitView of(GitService.CommitInfo info) {
            return new CommitView(info.hash(), info.author(), info.at(), info.message(), info.stat());
        }
    }

    private final ConversationService conversations;
    private final ProjectRepository projects;
    private final GitService git;

    public CommitController(ConversationService conversations, ProjectRepository projects, GitService git) {
        this.conversations = conversations;
        this.projects = projects;
        this.git = git;
    }

    @GetMapping
    public List<CommitView> list(@PathVariable UUID conversationId,
                                 @RequestParam(defaultValue = "50") int limit) {
        Path workspace = workspaceOf(conversationId);
        if (workspace == null) {
            return List.of();
        }
        return git.log(workspace, Math.min(limit, 200)).stream().map(CommitView::of).toList();
    }

    @GetMapping(value = "/{hash}/diff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diff(@PathVariable UUID conversationId, @PathVariable String hash) {
        Path workspace = workspaceOf(conversationId);
        if (workspace == null) {
            return "";
        }
        return git.diff(workspace, hash)
                .orElseThrow(() -> new IllegalArgumentException("Unknown commit " + hash));
    }

    private Path workspaceOf(UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation.getProjectId() == null) {
            return null;
        }
        return projects.findById(conversation.getProjectId())
                .map(p -> Path.of(p.getWorkspacePath()))
                .orElse(null);
    }
}
