package dev.cowork.rtk;

import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import dev.cowork.project.WorkspaceLocator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Reports the token savings rtk realized in a conversation's workspace. */
@RestController
public class RtkController {

    private final RtkService rtk;
    private final ConversationService conversations;
    private final WorkspaceLocator workspaces;

    public RtkController(RtkService rtk, ConversationService conversations, WorkspaceLocator workspaces) {
        this.rtk = rtk;
        this.conversations = conversations;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/conversations/{id}/rtk-savings")
    public RtkSavings savings(@PathVariable UUID id) {
        Conversation conversation = conversations.get(id);
        return rtk.savingsFor(id, workspaces.locate(conversation));
    }
}
