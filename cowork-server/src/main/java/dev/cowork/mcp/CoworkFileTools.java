package dev.cowork.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import dev.cowork.conversation.Participant;
import dev.cowork.project.ImplementationDocsController;
import dev.cowork.project.ProjectRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for the user-uploaded reference files (implementation_docs/). Chat messages
 * carry only file metadata; these tools let an agent pull the contents on demand.
 */
@Component
public class CoworkFileTools {

    /** Cap on returned content so a huge upload cannot blow up an agent's context. */
    private static final int MAX_CHARS = 200_000;

    public record FileRecord(String name, long size, Instant modifiedAt) {
    }

    private final ConversationService conversations;
    private final ProjectRepository projects;

    public CoworkFileTools(ConversationService conversations, ProjectRepository projects) {
        this.conversations = conversations;
        this.projects = projects;
    }

    @McpTool(name = "list_files", description = """
            List the files the user attached or uploaded to your conversation \
            (stored in implementation_docs/ in the workspace).""")
    public List<FileRecord> listFiles() throws IOException {
        Path dir = docsDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> {
                        try {
                            return new FileRecord(f.getFileName().toString(), Files.size(f),
                                    Files.getLastModifiedTime(f).toInstant());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted((a, b) -> b.modifiedAt().compareTo(a.modifiedAt()))
                    .toList();
        }
    }

    @McpTool(name = "read_file", description = """
            Read the text content of a file the user attached or uploaded to your conversation. \
            Pass the file name exactly as given in the message metadata or list_files. \
            Returns at most ~200k characters; larger files are truncated.""")
    public String readFile(
            @McpToolParam(required = true, description = "File name, e.g. \"notes.txt\"") String name) throws IOException {
        Path dir = docsDir();
        if (dir == null) {
            return "ERROR: this conversation has no uploaded files.";
        }
        Path file = dir.resolve(ImplementationDocsController.sanitize(name));
        if (!Files.isRegularFile(file)) {
            return "ERROR: no such file \"" + name + "\" — use list_files to see what exists.";
        }
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS)
                    + "\n\n[TRUNCATED — file is " + text.length() + " characters, showing the first "
                    + MAX_CHARS + "]";
        }
        return text;
    }

    /** The caller's implementation_docs directory, or null when no project exists yet. */
    private Path docsDir() {
        Participant caller = McpCallerContext.require();
        Conversation conversation = conversations.get(caller.getConversationId());
        if (conversation.getProjectId() == null) {
            return null;
        }
        return projects.findById(conversation.getProjectId())
                .map(p -> Path.of(p.getWorkspacePath()).resolve(ImplementationDocsController.DOCS_DIR))
                .orElse(null);
    }
}
