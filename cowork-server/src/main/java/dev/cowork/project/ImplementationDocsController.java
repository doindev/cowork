package dev.cowork.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.stream.SseHub;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * User-uploaded reference files (specs, mockups, screenshots) for the agents to review.
 * Stored in the project workspace under {@code implementation_docs/}, so agents can read
 * them directly from their working directory.
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/files")
public class ImplementationDocsController {

    public static final String DOCS_DIR = "implementation_docs";

    public record DocView(String name, long size, Instant modifiedAt) {
    }

    private final ConversationService conversations;
    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final MessageService messages;
    private final SseHub sseHub;

    public ImplementationDocsController(ConversationService conversations, ProjectService projectService,
                                        ProjectRepository projects, MessageService messages, SseHub sseHub) {
        this.conversations = conversations;
        this.projectService = projectService;
        this.projects = projects;
        this.messages = messages;
        this.sseHub = sseHub;
    }

    @PostMapping
    public DocView upload(@PathVariable UUID conversationId, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        Conversation conversation = conversations.get(conversationId);
        Path docsDir = docsDir(conversation, true);
        String name = sanitize(file.getOriginalFilename());
        Path target = docsDir.resolve(name);
        int suffix = 2;
        while (Files.exists(target)) {
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            target = docsDir.resolve(base + "-" + suffix++ + ext);
        }
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String storedName = target.getFileName().toString();
        messages.postSystem(conversationId, Message.Kind.SYSTEM,
                "The user uploaded \"" + storedName + "\" — agents can review it at "
                        + DOCS_DIR + "/" + storedName + " in the workspace.", null);
        DocView view = new DocView(storedName, Files.size(target),
                Files.getLastModifiedTime(target).toInstant());
        sseHub.publish(conversationId, "doc", view);
        return view;
    }

    @GetMapping
    public List<DocView> list(@PathVariable UUID conversationId) throws IOException {
        Conversation conversation = conversations.get(conversationId);
        Path docsDir = docsDir(conversation, false);
        if (docsDir == null || !Files.isDirectory(docsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(docsDir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> {
                        try {
                            return new DocView(f.getFileName().toString(), Files.size(f),
                                    Files.getLastModifiedTime(f).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted((a, b) -> b.modifiedAt().compareTo(a.modifiedAt()))
                    .toList();
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable UUID conversationId, @PathVariable String name)
            throws IOException {
        Conversation conversation = conversations.get(conversationId);
        Path docsDir = docsDir(conversation, false);
        String safe = sanitize(name);
        if (docsDir == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = docsDir.resolve(safe);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(file);
        return ResponseEntity.ok()
                .contentType(contentType == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safe + "\"")
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{name}")
    public List<DocView> delete(@PathVariable UUID conversationId, @PathVariable String name)
            throws IOException {
        Conversation conversation = conversations.get(conversationId);
        Path docsDir = docsDir(conversation, false);
        if (docsDir != null) {
            Files.deleteIfExists(docsDir.resolve(sanitize(name)));
        }
        return list(conversationId);
    }

    /** Resolves (optionally creating) the docs dir; creating also creates the project. */
    private Path docsDir(Conversation conversation, boolean create) throws IOException {
        Project project;
        if (conversation.getProjectId() == null) {
            if (!create) {
                return null;
            }
            project = projectService.ensureProject(conversation);
        } else {
            project = projects.findById(conversation.getProjectId()).orElseThrow();
        }
        Path dir = Path.of(project.getWorkspacePath()).resolve(DOCS_DIR);
        if (create) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /** Strips any path components and dangerous characters from a client-supplied filename. */
    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Missing filename");
        }
        String name = Path.of(filename).getFileName().toString()
                .replaceAll("[^A-Za-z0-9._ ()-]", "_")
                .trim();
        if (name.isBlank() || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return name;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> badRequest(IllegalArgumentException e) {
        return java.util.Map.of("message", e.getMessage());
    }
}
