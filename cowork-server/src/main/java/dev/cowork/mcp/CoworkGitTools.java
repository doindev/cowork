package dev.cowork.mcp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.Participant;
import dev.cowork.project.GitService;
import dev.cowork.project.ProjectRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Git review tools: agents inspect the project workspace's commit history and diffs. */
@Component
public class CoworkGitTools {

    public record CommitRecord(String hash, String author, Instant at, String message, String stat) {
    }

    private final ConversationRepository conversations;
    private final ProjectRepository projects;
    private final GitService git;

    public CoworkGitTools(ConversationRepository conversations, ProjectRepository projects, GitService git) {
        this.conversations = conversations;
        this.projects = projects;
        this.git = git;
    }

    @McpTool(name = "list_commits", description = """
            List recent commits in this conversation's project workspace, newest first. Each \
            implementation turn that changed files was auto-committed under the agent's name.""")
    public List<CommitRecord> listCommits(
            @McpToolParam(required = false, description = "Max commits (default 20, max 100)") Integer limit) {
        Path workspace = callerWorkspace();
        if (workspace == null) {
            return List.of();
        }
        int n = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        return git.log(workspace, n).stream()
                .map(c -> new CommitRecord(c.hash(), c.author(), c.at(), c.message(), c.stat()))
                .toList();
    }

    @McpTool(name = "get_commit_diff", description = """
            Get the full unified diff of a commit in the project workspace. Use this to review \
            another agent's changes before voting on or raising a CODE_CHANGE proposal \
            (reference the commit hash in create_proposal's commitHash).""")
    public String getCommitDiff(
            @McpToolParam(required = true, description = "Commit hash (short or full)") String hash) {
        Path workspace = callerWorkspace();
        if (workspace == null) {
            return "This conversation has no project workspace yet.";
        }
        return git.diff(workspace, hash.trim())
                .orElse("Unknown commit " + hash);
    }

    private Path callerWorkspace() {
        Participant caller = McpCallerContext.require();
        Conversation conversation = conversations.findById(caller.getConversationId()).orElse(null);
        if (conversation == null || conversation.getProjectId() == null) {
            return null;
        }
        return projects.findById(conversation.getProjectId())
                .map(p -> Path.of(p.getWorkspacePath()))
                .orElse(null);
    }
}
