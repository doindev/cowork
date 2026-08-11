package dev.cowork.project;

import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.ConversationService;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.stream.SseHub;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's explicit phase control. Even a PASSED PLAN_APPROVAL only flips the
 * conversation to IMPLEMENTATION when the user confirms here.
 */
@RestController
@RequestMapping("/api/conversations/{id}/phase")
public class PhaseController {

    public record PhaseRequest(@NotNull Conversation.Phase phase) {
    }

    public record PhaseView(String phase, UUID projectId, String workspacePath) {
    }

    private final ConversationService conversationService;
    private final ConversationRepository conversations;
    private final ProjectService projectService;
    private final MessageService messages;
    private final SseHub sseHub;

    public PhaseController(ConversationService conversationService, ConversationRepository conversations,
                           ProjectService projectService, MessageService messages, SseHub sseHub) {
        this.conversationService = conversationService;
        this.conversations = conversations;
        this.projectService = projectService;
        this.messages = messages;
        this.sseHub = sseHub;
    }

    @PostMapping
    public PhaseView setPhase(@PathVariable UUID id, @RequestBody PhaseRequest request) {
        Conversation conversation = conversationService.get(id);
        conversation.setPhase(request.phase());
        String workspacePath = null;
        if (request.phase() == Conversation.Phase.IMPLEMENTATION) {
            Project project = projectService.ensureProject(conversation);
            workspacePath = project.getWorkspacePath();
        }
        conversations.save(conversation);
        messages.postSystem(id, Message.Kind.PHASE,
                "Phase changed to " + request.phase()
                        + (workspacePath == null ? "" : " — agents now work in " + workspacePath), null);
        sseHub.publish(id, "phase", new PhaseView(request.phase().name(), conversation.getProjectId(), workspacePath));
        return new PhaseView(conversation.getPhase().name(), conversation.getProjectId(), workspacePath);
    }
}
