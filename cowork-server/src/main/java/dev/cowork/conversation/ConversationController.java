package dev.cowork.conversation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.cowork.message.MessageService;
import dev.cowork.message.MessageViews.MessageView;
import dev.cowork.stream.SseHub;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    public record CreateRequest(@NotBlank String title, Conversation.VoteMode voteMode, Boolean userVotes,
                                Integer maxAgentRounds, List<UUID> agentIds) {
    }

    public record UpdateRequest(Conversation.VoteMode voteMode, Boolean userVotes, Integer maxAgentRounds,
                                Conversation.Status status, Double budgetUsd) {
    }

    public record PostMessageRequest(@NotBlank String content) {
    }

    public record ParticipantView(UUID id, String kind, UUID agentId, String displayName, boolean active) {

        static ParticipantView of(Participant p) {
            return new ParticipantView(p.getId(), p.getKind().name(), p.getAgentId(), p.getDisplayName(), p.isActive());
        }
    }

    public record ConversationView(UUID id, String title, String phase, String voteMode, int maxAgentRounds,
                                   boolean userVotes, UUID projectId, String status, Double budgetUsd,
                                   double spentUsd, Instant createdAt, List<ParticipantView> participants) {

        static ConversationView of(Conversation c, List<Participant> participants) {
            return new ConversationView(c.getId(), c.getTitle(), c.getPhase().name(), c.getVoteMode().name(),
                    c.getMaxAgentRounds(), c.isUserVotes(), c.getProjectId(), c.getStatus().name(),
                    c.getBudgetUsd(), c.getSpentUsd(), c.getCreatedAt(),
                    participants.stream().map(ParticipantView::of).toList());
        }
    }

    private final ConversationService service;
    private final MessageService messages;
    private final SseHub sseHub;

    public ConversationController(ConversationService service, MessageService messages, SseHub sseHub) {
        this.service = service;
        this.messages = messages;
        this.sseHub = sseHub;
    }

    @GetMapping
    public List<ConversationView> list(@RequestParam(required = false) Conversation.Status status) {
        return service.list(status).stream()
                .map(c -> ConversationView.of(c, service.participantsOf(c.getId())))
                .toList();
    }

    @PostMapping
    public ConversationView create(@RequestBody CreateRequest request) {
        Conversation conversation = service.create(request.title(), request.voteMode(),
                Boolean.TRUE.equals(request.userVotes()), request.maxAgentRounds(), request.agentIds());
        return ConversationView.of(conversation, service.participantsOf(conversation.getId()));
    }

    @GetMapping("/{id}")
    public ConversationView get(@PathVariable UUID id) {
        return ConversationView.of(service.get(id), service.participantsOf(id));
    }

    @PatchMapping("/{id}")
    public ConversationView update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        Conversation conversation = service.updateSettings(id, request.voteMode(), request.userVotes(),
                request.maxAgentRounds(), request.status(), request.budgetUsd());
        return ConversationView.of(conversation, service.participantsOf(id));
    }

    @PostMapping("/{id}/agents/{agentId}")
    public ParticipantView addAgent(@PathVariable UUID id, @PathVariable UUID agentId) {
        return ParticipantView.of(service.addAgent(id, agentId));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public java.util.Map<String, Boolean> delete(@PathVariable UUID id) {
        service.deletePermanently(id);
        return java.util.Map.of("deleted", true);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public java.util.Map<String, String> conflict(IllegalStateException e) {
        return java.util.Map.of("message", e.getMessage());
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                                      @RequestParam(defaultValue = "100") int limit) {
        return messages.recent(id, before, Math.min(limit, 500)).stream()
                .map(MessageView::of)
                .sorted(Comparator.comparing(MessageView::createdAt))
                .toList();
    }

    @PostMapping("/{id}/messages")
    public MessageView postMessage(@PathVariable UUID id, @RequestBody PostMessageRequest request) {
        Participant user = service.userParticipant(id);
        return MessageView.of(messages.post(id, user, request.content(), 0));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        return sseHub.subscribe(id);
    }
}
