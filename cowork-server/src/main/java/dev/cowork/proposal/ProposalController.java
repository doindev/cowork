package dev.cowork.proposal;

import java.util.List;
import java.util.UUID;

import dev.cowork.conversation.ConversationService;
import dev.cowork.conversation.Participant;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProposalController {

    public record UserVoteRequest(@NotNull Vote.Value value, String rationale) {
    }

    public record OverrideRequest(@NotNull Decision decision) {

        public enum Decision { PASS, REJECT }
    }

    private final ProposalService proposals;
    private final ProposalRepository proposalRepository;
    private final ConversationService conversationService;
    private final dev.cowork.conversation.ParticipantRepository participants;

    public ProposalController(ProposalService proposals, ProposalRepository proposalRepository,
                              ConversationService conversationService,
                              dev.cowork.conversation.ParticipantRepository participants) {
        this.proposals = proposals;
        this.proposalRepository = proposalRepository;
        this.conversationService = conversationService;
        this.participants = participants;
    }

    @GetMapping("/conversations/{conversationId}/proposals")
    public List<ProposalViews.ProposalView> list(@PathVariable UUID conversationId,
                                                 @RequestParam(required = false) Proposal.Status status) {
        return proposals.byConversation(conversationId, status).stream()
                .map(p -> ProposalViews.of(p, proposals.votesOf(p.getId()), participants))
                .toList();
    }

    @PostMapping("/proposals/{proposalId}/votes")
    public ProposalViews.ProposalView userVote(@PathVariable UUID proposalId, @RequestBody UserVoteRequest request) {
        Proposal proposal = proposals.castVote(proposalId,
                userOf(proposalId), request.value(), request.rationale());
        return ProposalViews.of(proposal, proposals.votesOf(proposalId), participants);
    }

    @PostMapping("/proposals/{proposalId}/override")
    public ProposalViews.ProposalView override(@PathVariable UUID proposalId, @RequestBody OverrideRequest request) {
        Proposal proposal = proposals.override(proposalId,
                request.decision() == OverrideRequest.Decision.PASS);
        return ProposalViews.of(proposal, proposals.votesOf(proposalId), participants);
    }

    private Participant userOf(UUID proposalId) {
        Proposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown proposal " + proposalId));
        return conversationService.userParticipant(proposal.getConversationId());
    }
}
