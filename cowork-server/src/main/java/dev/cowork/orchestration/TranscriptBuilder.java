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

    private final dev.cowork.skill.SkillService skills;

    public TranscriptBuilder(dev.cowork.skill.SkillService skills) {
        this.skills = skills;
    }

    public String build(Conversation conversation, Participant agent, List<Participant> allParticipants,
                        List<Message> newMessages, int roundsRemaining, boolean hasImplementationDocs,
                        String externalWorkspacePath, boolean sessionRecovery, boolean olderOmitted,
                        boolean newerPending) {
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
        sb.append("- HAND-OFF PROTOCOL: end EVERY reply with exactly one of: (a) @name of the agent who ")
                .append("should act next, or (b) @user plus a one-line reason when the team genuinely needs ")
                .append("the user (their decision, missing information, or the goal is reached). A reply that ")
                .append("mentions nobody stops ALL agents until the user returns — never end a reply without ")
                .append("one of these.\n");
        sb.append("- When merely referring to someone, write their name WITHOUT the @ (e.g. \"as reviewer ")
                .append("said\") — an @ triggers a turn, so use it only for the deliberate hand-off above.\n");
        sb.append("- Your final reply text is posted to the chat room under your name automatically — ")
                .append("do NOT call the post_message tool for your main reply, only for optional extra notes.\n");
        sb.append("- Use the \"cowork\" MCP tools to read conversation history (read_conversation, search_messages), ")
                .append("create proposals (create_proposal) and vote on open proposals (cast_vote, list_proposals). ")
                .append("MATERIAL decisions — plan approval, task assignment, architecture/technology choices, ")
                .append("scope changes, anything irreversible — go through proposals and votes. Trivial, ")
                .append("reversible implementation details do NOT need a proposal or discussion: decide, note it, ")
                .append("move on. Do not discuss-by-proposal.\n");
        sb.append("- Decision semantics: a proposal that reaches ").append(conversation.getVoteMode())
                .append(" approval is PASSED and is the binding team decision — the winning option that everyone ")
                .append("now works from. A REJECTED proposal is off the table; raise a revised alternative instead ")
                .append("of re-arguing it. Check list_proposals when unsure what has already been decided.\n");
        sb.append("- Current phase: ").append(conversation.getPhase()).append(". Vote mode: ")
                .append(conversation.getVoteMode()).append(".\n");
        if (conversation.getPhase() == dev.cowork.conversation.Conversation.Phase.PLANNING) {
            sb.append("- PLANNING GOAL: produce a detailed implementation plan for the user's request that the ")
                    .append("team agrees on (").append(conversation.getVoteMode()).append(" vote). Settle the ")
                    .append("significant implementation details — architecture, technology choices, data model, ")
                    .append("interfaces, scope — efficiently: one focused pass per topic, do not revisit agreed ")
                    .append("points. When the plan is complete, raise a PLAN_APPROVAL proposal summarizing it; ")
                    .append("if it passes, the user confirms the switch to implementation. Do not start writing ")
                    .append("application code during planning.\n");
        }
        if (conversation.getPhase() == dev.cowork.conversation.Conversation.Phase.IMPLEMENTATION) {
            sb.append("- Workspace changes you make are auto-committed under your name after each turn. ")
                    .append("Code review is the designated reviewer's job, not a group activity: review ")
                    .append("commits (list_commits, get_commit_diff) only when it is your role or you were ")
                    .append("asked; raise findings as CODE_CHANGE proposals referencing the commit hash.\n");
        }
        if (conversation.hasRoundLimit()) {
            sb.append("- Agent-to-agent hand-off rounds remaining before control returns to the user: ")
                    .append(Math.max(roundsRemaining, 0)).append(".\n");
        } else {
            sb.append("- There is no agent-to-agent round limit: keep handing off (by mentioning the next ")
                    .append("agent) until the current goal is reached; return control to the user when you ")
                    .append("genuinely need their input or the goal is done.\n");
        }
        if (externalWorkspacePath != null) {
            sb.append("- PROJECT WORKSPACE: ").append(externalWorkspacePath)
                    .append(" — this user-provided directory is your working directory and the ONLY ")
                    .append("place you may work. Do NOT modify, create, or delete any file or directory ")
                    .append("outside it (no writes to the home directory, temp dirs, system locations, or ")
                    .append("any other path). Reading elsewhere is allowed only when the user asks for it.\n");
        }
        if (hasImplementationDocs) {
            sb.append("- The user uploaded reference files (specs, mockups, screenshots) to the ")
                    .append("implementation_docs/ directory in your working directory — read them ")
                    .append("before making design decisions. Chat messages list attached files as ")
                    .append("metadata only; fetch contents with the list_files/read_file MCP tools ")
                    .append("or read them straight from implementation_docs/.\n");
        }
        sb.append("- PERSISTENT NOTES: maintain your working notes at agent-notes/")
                .append(agent.getDisplayName()).append(".md in your working directory — current status, key ")
                .append("decisions, important file locations, and next steps. Update it whenever you complete ")
                .append("meaningful work: it is your memory across session resets and restarts.\n");
        sb.append("- STAY FOCUSED on the user's actual request: every discussion point, proposal, and task ")
                .append("must trace back to something the user asked for. Do not invent requirements, expand ")
                .append("scope, or gold-plate. If something is ambiguous or missing, ask the user instead of ")
                .append("assuming. State only what you can verify from the conversation, the workspace files, ")
                .append("or tool results — if you are unsure, say so rather than guessing. If the discussion ")
                .append("drifts off the user's goal, say so and steer it back.\n");
        sb.append("- OUTPUT CONTRACT: default reply is at most ~120 words plus any code or artifacts. ")
                .append("No greetings, no pleasantries, no recaps of other agents' messages, no restating ")
                .append("agreed plans or decisions. Dense technical prose; every sentence must carry new ")
                .append("information. Expand only when the user explicitly asks for a detailed or verbose ")
                .append("answer.\n");

        for (dev.cowork.skill.SkillDef skill : skills.activeFor(conversation)) {
            sb.append("\n[ACTIVE SKILL: ").append(skill.name()).append("]\n")
                    .append("The user activated this protocol for the conversation — follow it in ")
                    .append("addition to the standing instructions above.\n")
                    .append(skill.body()).append('\n');
        }

        if (sessionRecovery) {
            sb.append("\n[SESSION RECOVERY]\n");
            sb.append("You are starting a FRESH session (your previous one was reset — context refresh or ")
                    .append("restart). Read your notes file agent-notes/").append(agent.getDisplayName())
                    .append(".md FIRST, then review the recent messages below. Use read_conversation/")
                    .append("search_messages for older history and list_tasks/list_proposals for what has ")
                    .append("been decided. Do NOT redo completed work — trust your notes and the task list.\n");
            sb.append("\n[RECENT CONVERSATION]\n");
        } else {
            sb.append("\n[NEW MESSAGES SINCE YOUR LAST TURN]\n");
        }
        if (newMessages.isEmpty()) {
            sb.append("(none)\n");
        } else {
            if (olderOmitted) {
                sb.append("(older messages omitted — use read_conversation for more)\n");
            }
            for (Message message : newMessages) {
                sb.append('[').append(message.senderName()).append("]: ").append(message.content()).append('\n');
            }
            if (newerPending) {
                sb.append("(more new messages arrived than fit here — they will follow next turn; ")
                        .append("use read_conversation to see the latest now)\n");
            }
        }
        sb.append("\nRespond now as \"").append(agent.getDisplayName()).append("\".\n");
        return sb.toString();
    }
}
