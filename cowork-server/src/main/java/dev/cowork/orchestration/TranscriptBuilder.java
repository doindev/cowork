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
        sb.append("- Mentioning @name ANYWHERE in a message addresses that participant and, for agents, ")
                .append("triggers them to respond. Mention several (e.g. @architect and later @reviewer) and ")
                .append("each will take a turn.\n");
        sb.append("- Use @name deliberately: only when you want that participant to act or respond. When merely ")
                .append("referring to someone, write their name WITHOUT the @ (e.g. \"as reviewer said\") — ")
                .append("otherwise you will trigger an unnecessary turn. A reply with no mentions is shown to ")
                .append("everyone but triggers no other agent.\n");
        sb.append("- Your final reply text is posted to the chat room under your name automatically — ")
                .append("do NOT call the post_message tool for your main reply, only for optional extra notes.\n");
        sb.append("- Use the \"cowork\" MCP tools to read conversation history (read_conversation, search_messages), ")
                .append("create proposals (create_proposal) and vote on open proposals (cast_vote, list_proposals). ")
                .append("All significant decisions — plan approval, task assignment, code-change suggestions — ")
                .append("must go through proposals and votes.\n");
        sb.append("- Decision semantics: a proposal that reaches ").append(conversation.getVoteMode())
                .append(" approval is PASSED and is the binding team decision — the winning option that everyone ")
                .append("now works from. A REJECTED proposal is off the table; raise a revised alternative instead ")
                .append("of re-arguing it. Check list_proposals when unsure what has already been decided.\n");
        sb.append("- Current phase: ").append(conversation.getPhase()).append(". Vote mode: ")
                .append(conversation.getVoteMode()).append(".\n");
        if (conversation.getPhase() == dev.cowork.conversation.Conversation.Phase.PLANNING) {
            sb.append("- PLANNING GOAL: produce a detailed implementation plan for the user's request that the ")
                    .append("team agrees on (").append(conversation.getVoteMode()).append(" vote). Keep discussing ")
                    .append("and settle every significant implementation detail — architecture, technology choices, ")
                    .append("data model, interfaces, scope — through proposals and votes until the full plan is ")
                    .append("agreed. When the plan is complete, raise a PLAN_APPROVAL proposal summarizing it; ")
                    .append("if it passes, the user confirms the switch to implementation. Do not start writing ")
                    .append("application code during planning.\n");
        }
        if (conversation.getPhase() == dev.cowork.conversation.Conversation.Phase.IMPLEMENTATION) {
            sb.append("- Workspace changes you make are auto-committed under your name after each turn. ")
                    .append("Review teammates' work with list_commits and get_commit_diff; raise review ")
                    .append("feedback as CODE_CHANGE proposals referencing the commit hash.\n");
        }
        if (conversation.hasRoundLimit()) {
            sb.append("- Agent-to-agent hand-off rounds remaining before control returns to the user: ")
                    .append(Math.max(roundsRemaining, 0)).append(".\n");
        } else {
            sb.append("- There is no agent-to-agent round limit: keep handing off (by mentioning the next ")
                    .append("agent) until the current goal is reached; return control to the user when you ")
                    .append("genuinely need their input or the goal is done.\n");
        }
        if (hasImplementationDocs) {
            sb.append("- The user uploaded reference files (specs, mockups, screenshots) to the ")
                    .append("implementation_docs/ directory in your working directory — read them ")
                    .append("before making design decisions.\n");
        }
        sb.append("- STAY FOCUSED on the user's actual request: every discussion point, proposal, and task ")
                .append("must trace back to something the user asked for. Do not invent requirements, expand ")
                .append("scope, or gold-plate. If something is ambiguous or missing, ask the user instead of ")
                .append("assuming. State only what you can verify from the conversation, the workspace files, ")
                .append("or tool results — if you are unsure, say so rather than guessing. If the discussion ")
                .append("drifts off the user's goal, say so and steer it back.\n");
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
