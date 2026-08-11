package dev.cowork.orchestration;

import java.util.List;
import java.util.stream.Collectors;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.Participant;
import dev.cowork.message.Message;
import org.springframework.stereotype.Component;

/** Builds the stdin prompt for an agent turn: standing instructions + delta transcript. */
@Component
public class TranscriptBuilder {

    public String build(Conversation conversation, Participant agent, List<Participant> allParticipants,
                        List<Message> newMessages, int roundsRemaining, boolean hasImplementationDocs) {
        String roster = allParticipants.stream()
                .filter(Participant::isActive)
                .map(Participant::getDisplayName)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[STANDING INSTRUCTIONS]\n");
        sb.append("You are agent \"").append(agent.getDisplayName()).append("\" in the team chat room \"")
                .append(conversation.getTitle()).append("\".\n");
        sb.append("Participants: ").append(roster).append(". \"user\" is the human.\n");
        sb.append("- A message prefixed with @name (or @a,@b) addresses those participants specifically; ")
                .append("anything else is a broadcast.\n");
        sb.append("- To hand off to another agent so they respond next, START your reply with their mention, ")
                .append("e.g. \"@architect what do you think about X?\". Replies without a leading mention are ")
                .append("shown to everyone but trigger no other agent.\n");
        sb.append("- Your final reply text is posted to the chat room under your name automatically — ")
                .append("do NOT call the post_message tool for your main reply, only for optional extra notes.\n");
        sb.append("- Use the \"cowork\" MCP tools to read conversation history (read_conversation, search_messages), ")
                .append("create proposals (create_proposal) and vote on open proposals (cast_vote, list_proposals). ")
                .append("All significant decisions — plan approval, task assignment, code-change suggestions — ")
                .append("must go through proposals and votes.\n");
        sb.append("- Current phase: ").append(conversation.getPhase()).append(". Vote mode: ")
                .append(conversation.getVoteMode()).append(".\n");
        if (conversation.getPhase() == dev.cowork.conversation.Conversation.Phase.IMPLEMENTATION) {
            sb.append("- Workspace changes you make are auto-committed under your name after each turn. ")
                    .append("Review teammates' work with list_commits and get_commit_diff; raise review ")
                    .append("feedback as CODE_CHANGE proposals referencing the commit hash.\n");
        }
        sb.append("- Agent-to-agent hand-off rounds remaining before control returns to the user: ")
                .append(Math.max(roundsRemaining, 0)).append(".\n");
        if (hasImplementationDocs) {
            sb.append("- The user uploaded reference files (specs, mockups, screenshots) to the ")
                    .append("implementation_docs/ directory in your working directory — read them ")
                    .append("before making design decisions.\n");
        }
        sb.append("- Keep replies concise; this is a chat room, not a document.\n");

        sb.append("\n[NEW MESSAGES SINCE YOUR LAST TURN]\n");
        if (newMessages.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Message message : newMessages) {
                sb.append('[').append(message.senderName()).append("]: ").append(message.content()).append('\n');
            }
        }
        sb.append("\nRespond now as \"").append(agent.getDisplayName()).append("\".\n");
        return sb.toString();
    }
}
