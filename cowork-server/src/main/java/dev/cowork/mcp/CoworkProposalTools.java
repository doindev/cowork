package dev.cowork.mcp;

import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.proposal.Proposal;
import dev.cowork.proposal.ProposalService;
import dev.cowork.proposal.ProposalViews;
import dev.cowork.proposal.Vote;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Proposal and voting MCP tools. One vote per participant, enforced by the database. */
@Component
public class CoworkProposalTools {

    private final ProposalService proposals;
    private final ParticipantRepository participants;
    private final dev.cowork.agent.AgentDefRepository agents;

    public CoworkProposalTools(ProposalService proposals, ParticipantRepository participants,
                               dev.cowork.agent.AgentDefRepository agents) {
        this.proposals = proposals;
        this.participants = participants;
        this.agents = agents;
    }

    @McpTool(name = "create_proposal", description = """
            Create a proposal for the team to vote on. Types: PLAN_APPROVAL (approve the overall plan \
            and move to implementation), TASK_ASSIGNMENT (assign a task to an agent — name the assignee \
            in the body), CODE_CHANGE (a code-review suggestion that must be approved before applying).""")
    public String createProposal(
            @McpToolParam(required = true, description = "PLAN_APPROVAL | TASK_ASSIGNMENT | CODE_CHANGE") String type,
            @McpToolParam(required = true, description = "Short proposal title") String title,
            @McpToolParam(required = true, description = "Full description of what is being proposed and why") String body,
            @McpToolParam(required = false, description = "Related task id (UUID), for TASK_ASSIGNMENT/CODE_CHANGE") String taskId,
            @McpToolParam(required = false, description = "Agent name to assign (TASK_ASSIGNMENT only)") String assignee,
            @McpToolParam(required = false, description = "Commit hash the proposal refers to (CODE_CHANGE reviews)") String commitHash) {
        Participant caller = McpCallerContext.require();
        UUID assigneeAgentId = null;
        if (assignee != null && !assignee.isBlank()) {
            assigneeAgentId = agents.findByName(assignee.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown agent '" + assignee + "'"))
                    .getId();
        }
        Proposal proposal = proposals.create(caller, Proposal.Type.valueOf(type.trim().toUpperCase()),
                title, body, taskId == null || taskId.isBlank() ? null : UUID.fromString(taskId), assigneeAgentId,
                commitHash == null || commitHash.isBlank() ? null : commitHash.trim());
        return "created proposal " + proposal.getId() + " (status OPEN). Other participants should now cast_vote.";
    }

    @McpTool(name = "cast_vote", description = """
            Cast your vote on an open proposal. You can vote once per proposal. The proposal passes or \
            fails according to the conversation's vote mode (majority or unanimous).""")
    public String castVote(
            @McpToolParam(required = true, description = "Proposal id (UUID)") String proposalId,
            @McpToolParam(required = true, description = "YES | NO | ABSTAIN") String value,
            @McpToolParam(required = false, description = "One-sentence rationale for your vote") String rationale) {
        Participant caller = McpCallerContext.require();
        Proposal proposal = proposals.castVote(UUID.fromString(proposalId), caller,
                Vote.Value.valueOf(value.trim().toUpperCase()), rationale);
        return "vote recorded; proposal status is now " + proposal.getStatus();
    }

    @McpTool(name = "list_proposals", description = "List proposals in your conversation with their votes and status.")
    public List<ProposalViews.ProposalView> listProposals(
            @McpToolParam(required = false, description = "Filter: OPEN | PASSED | REJECTED | NEEDS_USER | CANCELLED") String status) {
        Participant caller = McpCallerContext.require();
        Proposal.Status filter = status == null || status.isBlank()
                ? null : Proposal.Status.valueOf(status.trim().toUpperCase());
        return proposals.byConversation(caller.getConversationId(), filter).stream()
                .map(p -> ProposalViews.of(p, proposals.votesOf(p.getId()), participants))
                .toList();
    }
}
