package dev.cowork.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.message.Message;
import dev.cowork.message.MessageRepository;
import dev.cowork.message.MessageService;
import dev.cowork.message.NewMessageEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Routes chat messages to agent turns. One serialized worker (virtual thread + queue)
 * per conversation; conversations run in parallel.
 *
 * Routing rules:
 * <ul>
 *   <li>User message without mentions → broadcast: every active agent takes a turn.</li>
 *   <li>User message with mentions → only the mentioned agents.</li>
 *   <li>Agent message → only agents it explicitly mentions.</li>
 *   <li>Agent-to-agent hops beyond the conversation's round budget are dropped with a SYSTEM notice.</li>
 * </ul>
 *
 * When an agent message triggers nobody the chain would stall, so a deterministic
 * classifier ({@link IdleStateService}) decides what happens: nudge missing voters,
 * re-prompt the agent that broke the hand-off protocol, surface a wait-on-user state,
 * or accept that the goal is met. Automatic nudges are bounded per user message and
 * can be disabled per conversation ({@code autoContinue}).
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    /** Max automatic turns (corrective re-prompts, vote/goal nudges) per user message. */
    private static final int MAX_AUTO_NUDGES = 3;

    private record TurnJob(UUID participantId, int round, String nudge, boolean retried) {

        TurnJob(UUID participantId, int round) {
            this(participantId, round, null, false);
        }
    }

    private final class Worker {

        private final UUID conversationId;
        private final LinkedBlockingQueue<TurnJob> queue = new LinkedBlockingQueue<>();
        private final Set<UUID> queuedParticipants = new CopyOnWriteArraySet<>();
        private final Map<UUID, Instant> lastSeen = new ConcurrentHashMap<>();
        private final Thread thread;

        Worker(UUID conversationId) {
            this.conversationId = conversationId;
            this.thread = Thread.ofVirtual().name("conversation-" + conversationId).start(this::loop);
        }

        void enqueue(TurnJob job) {
            if (queuedParticipants.add(job.participantId())) {
                queue.add(job);
            }
        }

        boolean isIdle() {
            return queue.isEmpty() && queuedParticipants.isEmpty();
        }

        private void loop() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TurnJob job = queue.take();
                    queuedParticipants.remove(job.participantId());
                    runTurn(job);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.error("Turn execution failed in conversation {}", conversationId, e);
                }
            }
        }

        private void runTurn(TurnJob job) {
            Conversation conversation = conversations.findById(conversationId).orElse(null);
            Participant participant = participants.findById(job.participantId()).orElse(null);
            if (conversation == null || participant == null || !participant.isActive()
                    || conversation.isPaused()) {
                return;
            }
            boolean stateless = turnService.isStateless(participant);
            Instant since = stateless ? Instant.EPOCH : lastSeen.getOrDefault(participant.getId(), Instant.EPOCH);
            List<Message> delta = messageRepository.findSince(conversationId, since, 200);

            List<Participant> roster = participants.findByConversationId(conversationId);
            int roundsRemaining = conversation.getMaxAgentRounds() - job.round();
            String prompt = transcriptBuilder.build(conversation, participant, roster, delta, roundsRemaining,
                    hasImplementationDocs(conversation), externalWorkspacePath(conversation));
            if (job.nudge() != null) {
                prompt = prompt + "\n[COORDINATOR]\n" + job.nudge() + "\n";
            }

            AgentTurnService.TurnOutcome outcome =
                    turnService.execute(conversation, participant, prompt, job.round(), job.retried());

            // Advance the delta cursor only on success so a retried turn re-sends the same context.
            if (!stateless && outcome.failure() == AgentTurnService.TurnFailure.NONE) {
                lastSeen.put(participant.getId(), delta.isEmpty() ? since : delta.getLast().createdAt());
            }
            handleOutcome(conversation, participant, job, outcome);
        }

        private void handleOutcome(Conversation conversation, Participant participant, TurnJob job,
                                   AgentTurnService.TurnOutcome outcome) {
            String name = participant.getDisplayName();
            switch (outcome.failure()) {
                case NONE, SKIPPED -> { /* reply routes via onNewMessage / nothing to do */ }
                case CANCELLED -> {
                    messages.postSystem(conversationId, Message.Kind.SYSTEM,
                            "Turn of '" + name + "' was cancelled by the user.", null);
                    setIdle(conversationId, "cancelled",
                            "You cancelled " + name + "'s turn — agents are idle until you reply or resume.");
                }
                case CONFIG -> setIdle(conversationId, "agent-failed",
                        "Agent '" + name + "' is unavailable (" + outcome.detail() + ").");
                case FAILED, BLANK -> {
                    if (!job.retried()) {
                        log.info("Retrying turn of '{}' in conversation {} once ({})",
                                name, conversationId, outcome.detail());
                        enqueue(new TurnJob(job.participantId(), job.round(), job.nudge(), true));
                    } else {
                        messages.postSystem(conversationId, Message.Kind.SYSTEM,
                                "Agent '" + name + "' failed this turn: " + outcome.detail(), null);
                        setIdle(conversationId, "agent-failed",
                                "Agent '" + name + "' failed twice (" + outcome.detail()
                                        + ") — reply or resume when ready.");
                    }
                }
            }
        }

        void stop() {
            thread.interrupt();
        }
    }

    private final ConversationRepository conversations;
    private final ParticipantRepository participants;
    private final MessageRepository messageRepository;
    private final MessageService messages;
    private final TranscriptBuilder transcriptBuilder;
    private final AgentTurnService turnService;
    private final dev.cowork.stream.SseHub sseHub;
    private final dev.cowork.project.ProjectRepository projectRepository;
    private final IdleStateService idleStateService;
    private final ActiveTurnRegistry activeTurns;
    private final Map<UUID, Worker> workers = new ConcurrentHashMap<>();
    private final Map<UUID, List<TurnJob>> droppedByBudget = new ConcurrentHashMap<>();
    /** Automatic turns consumed since the last user message, per conversation. */
    private final Map<UUID, Integer> nudgesUsed = new ConcurrentHashMap<>();
    /** The participant most recently given a corrective re-prompt, per conversation. */
    private final Map<UUID, UUID> correctedParticipant = new ConcurrentHashMap<>();
    /** Last published idle state per conversation ({@code reason}/{@code detail}). */
    private final Map<UUID, Map<String, String>> idleStates = new ConcurrentHashMap<>();

    public ConversationOrchestrator(ConversationRepository conversations, ParticipantRepository participants,
                                    MessageRepository messageRepository, MessageService messages,
                                    TranscriptBuilder transcriptBuilder, AgentTurnService turnService,
                                    dev.cowork.stream.SseHub sseHub,
                                    dev.cowork.project.ProjectRepository projectRepository,
                                    IdleStateService idleStateService, ActiveTurnRegistry activeTurns) {
        this.conversations = conversations;
        this.participants = participants;
        this.messageRepository = messageRepository;
        this.messages = messages;
        this.transcriptBuilder = transcriptBuilder;
        this.turnService = turnService;
        this.sseHub = sseHub;
        this.projectRepository = projectRepository;
        this.idleStateService = idleStateService;
        this.activeTurns = activeTurns;
    }

    @Async
    @EventListener
    public void onNewMessage(NewMessageEvent event) {
        Message message = event.message();
        if (message.kind() != Message.Kind.CHAT) {
            return;
        }
        Conversation conversation = conversations.findById(message.conversationId()).orElse(null);
        if (conversation == null || conversation.getStatus() != Conversation.Status.ACTIVE) {
            return;
        }

        if (conversation.isPaused()) {
            return; // Recorded but not routed; delta transcripts deliver it after resume.
        }
        boolean fromUser = event.senderKind() == Participant.Kind.USER;
        if (fromUser) {
            // A user message resets the automatic-continuation budget and any idle banner.
            nudgesUsed.remove(conversation.getId());
            correctedParticipant.remove(conversation.getId());
            clearIdle(conversation.getId());
        }
        int nextRound = fromUser ? 0 : message.round() + 1;

        List<Participant> recipients = resolveRecipients(conversation, message, fromUser);
        if (recipients.isEmpty()) {
            if (!fromUser) {
                handleChainPause(conversation, message);
            }
            return;
        }
        if (conversation.isBudgetExhausted()) {
            if (fromUser) {
                messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                        String.format("Budget exhausted ($%.2f of $%.2f) — agents will not respond until the "
                                + "budget is raised in conversation settings.",
                                conversation.getSpentUsd(), conversation.getBudgetUsd()), null);
            }
            return;
        }
        if (!fromUser && conversation.hasRoundLimit() && nextRound > conversation.getMaxAgentRounds()) {
            droppedByBudget.computeIfAbsent(conversation.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .addAll(recipients.stream().map(p -> new TurnJob(p.getId(), 0)).toList());
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "Agent hand-off round limit (" + conversation.getMaxAgentRounds()
                            + ") reached — waiting for the user.", null);
            sseHub.publish(conversation.getId(), "round-limit",
                    Map.of("dropped", recipients.stream().map(Participant::getDisplayName).toList()));
            return;
        }

        clearIdle(conversation.getId());
        Worker worker = workers.computeIfAbsent(conversation.getId(), Worker::new);
        for (Participant recipient : recipients) {
            worker.enqueue(new TurnJob(recipient.getId(), nextRound));
        }
    }

    /**
     * An agent message triggered nobody — the chain would stall here. Decide whether to
     * nudge voters, correct the sender, surface a wait-on-user state, or accept idling.
     */
    private void handleChainPause(Conversation conversation, Message message) {
        UUID conversationId = conversation.getId();
        IdleStateService.Classification c = idleStateService.classify(conversation);
        boolean mentionedUser = message.mentions().stream().anyMatch(m -> m.equalsIgnoreCase("user"));
        log.info("Chain paused in conversation {} after message from '{}': {} ({})",
                conversationId, message.senderName(), c.reason(), c.detail());

        switch (c.reason()) {
            case AWAITING_AGENT_VOTES -> {
                String names = c.agentsToNudge().stream()
                        .map(Participant::getDisplayName)
                        .collect(Collectors.joining(", "));
                if (tryConsumeNudge(conversation)) {
                    log.info("Nudging missing voters in conversation {}: {}", conversationId, names);
                    Worker worker = workers.computeIfAbsent(conversationId, Worker::new);
                    for (Participant voter : c.agentsToNudge()) {
                        worker.enqueue(new TurnJob(voter.getId(), 0, voteNudge(c.detail()), false));
                    }
                } else {
                    setIdle(conversationId, "awaiting-votes",
                            "Proposal " + c.detail() + " is waiting for votes from: " + names + ".");
                }
            }
            case AWAITING_USER_VOTE -> setIdle(conversationId, "your-vote",
                    "Proposal " + c.detail() + " needs your vote to resolve.");
            case DEADLOCKED -> setIdle(conversationId, "deadlocked",
                    "Proposal " + c.detail() + " is deadlocked — approve or reject it in the panel.");
            case PHASE_CONFIRM -> setIdle(conversationId, "phase-confirm",
                    "The plan was approved — confirm the switch to implementation from the phase banner.");
            case GOAL_INCOMPLETE -> {
                if (mentionedUser) {
                    setIdle(conversationId, "returned-control",
                            message.senderName() + " returned control to you (" + c.detail() + ").");
                } else if (message.senderParticipantId() != null
                        && message.senderParticipantId().equals(correctedParticipant.get(conversationId))) {
                    // The corrective re-prompt also went unanswered — stop pushing.
                    correctedParticipant.remove(conversationId);
                    setIdle(conversationId, "returned-control",
                            message.senderName() + " ended the discussion (" + c.detail() + ").");
                } else if (message.senderParticipantId() != null && tryConsumeNudge(conversation)) {
                    log.info("Corrective re-prompt for '{}' in conversation {}",
                            message.senderName(), conversationId);
                    correctedParticipant.put(conversationId, message.senderParticipantId());
                    workers.computeIfAbsent(conversationId, Worker::new)
                            .enqueue(new TurnJob(message.senderParticipantId(), 0,
                                    correctiveNudge(c.detail()), false));
                } else {
                    setIdle(conversationId,
                            conversation.isAutoContinue() ? "auto-continue-limit" : "idle-incomplete",
                            "Agents stopped but " + c.detail() + ".");
                }
            }
            case COMPLETE -> {
                if (mentionedUser) {
                    setIdle(conversationId, "returned-control",
                            message.senderName() + " returned control to you.");
                }
            }
        }
    }

    /** Directly enqueues an agent turn (used for approved-task side effects). Round 0 = fresh budget. */
    public void triggerTurn(UUID conversationId, UUID participantId) {
        clearIdle(conversationId);
        workers.computeIfAbsent(conversationId, Worker::new).enqueue(new TurnJob(participantId, 0));
    }

    /**
     * Enqueues an automatic nudge turn if auto-continue allows it (enabled, budget left).
     * Returns false when the nudge was not sent.
     */
    public boolean tryAutoNudge(Conversation conversation, UUID participantId, String nudge) {
        if (!tryConsumeNudge(conversation)) {
            return false;
        }
        clearIdle(conversation.getId());
        workers.computeIfAbsent(conversation.getId(), Worker::new)
                .enqueue(new TurnJob(participantId, 0, nudge, false));
        return true;
    }

    /**
     * User-initiated resume for ANY stall: re-enqueues round-limit drops, resets the
     * automatic-continuation budget, and restarts whatever the classifier says is next
     * (missing votes or the phase goal). Returns the number of turns started.
     */
    public int resume(UUID conversationId) {
        int resumed = 0;
        Worker worker = workers.computeIfAbsent(conversationId, Worker::new);
        List<TurnJob> dropped = droppedByBudget.remove(conversationId);
        if (dropped != null && !dropped.isEmpty()) {
            messages.postSystem(conversationId, Message.Kind.SYSTEM,
                    "The user granted more agent rounds — resuming.", null);
            dropped.forEach(worker::enqueue);
            resumed += dropped.size();
        }
        nudgesUsed.remove(conversationId);
        correctedParticipant.remove(conversationId);
        clearIdle(conversationId);
        if (resumed > 0) {
            return resumed;
        }
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null || conversation.getStatus() != Conversation.Status.ACTIVE
                || conversation.isPaused() || conversation.isBudgetExhausted()) {
            return resumed;
        }
        IdleStateService.Classification c = idleStateService.classify(conversation);
        switch (c.reason()) {
            case AWAITING_AGENT_VOTES -> {
                for (Participant voter : c.agentsToNudge()) {
                    worker.enqueue(new TurnJob(voter.getId(), 0, voteNudge(c.detail()), false));
                    resumed++;
                }
            }
            case GOAL_INCOMPLETE -> {
                Participant target = lastAgentSpeaker(conversationId);
                if (target != null) {
                    worker.enqueue(new TurnJob(target.getId(), 0, goalNudge(c.detail()), false));
                    resumed++;
                }
            }
            default -> { /* wait-on-user states: nothing to trigger */ }
        }
        return resumed;
    }

    /**
     * The current idle state for the UI: the last published one if any, else a
     * recomputation of the DB-derivable wait-on-user states. {@code reason: ""} = none.
     */
    public Map<String, String> idleState(UUID conversationId) {
        Map<String, String> current = idleStates.get(conversationId);
        if (current != null) {
            return current;
        }
        Worker worker = workers.get(conversationId);
        boolean busy = !activeTurns.active(conversationId).isEmpty() || (worker != null && !worker.isIdle());
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (busy || conversation == null || conversation.getStatus() != Conversation.Status.ACTIVE
                || conversation.isPaused()) {
            return Map.of("reason", "");
        }
        IdleStateService.Classification c = idleStateService.classify(conversation);
        return switch (c.reason()) {
            case AWAITING_USER_VOTE -> Map.of("reason", "your-vote",
                    "detail", "Proposal " + c.detail() + " needs your vote to resolve.");
            case DEADLOCKED -> Map.of("reason", "deadlocked",
                    "detail", "Proposal " + c.detail() + " is deadlocked — approve or reject it in the panel.");
            case PHASE_CONFIRM -> Map.of("reason", "phase-confirm",
                    "detail", "The plan was approved — confirm the switch to implementation from the phase banner.");
            default -> Map.of("reason", "");
        };
    }

    private boolean tryConsumeNudge(Conversation conversation) {
        if (!conversation.isAutoContinue() || conversation.isBudgetExhausted()) {
            return false;
        }
        UUID id = conversation.getId();
        int used = nudgesUsed.getOrDefault(id, 0);
        if (used >= MAX_AUTO_NUDGES) {
            return false;
        }
        nudgesUsed.put(id, used + 1);
        return true;
    }

    private static String voteNudge(String proposalTitle) {
        return "Proposal " + proposalTitle + " is awaiting your vote and the discussion is paused on it. "
                + "Review it with list_proposals and cast your vote with cast_vote now. Then either @mention "
                + "the next agent or return control by mentioning @user with a one-line reason.";
    }

    private static String correctiveNudge(String goalDetail) {
        return "Your last reply mentioned no participant, so no agent was triggered and the discussion has "
                + "stopped — but " + goalDetail + ". Either @mention the agent who should act next, or "
                + "explicitly return control by mentioning @user with a one-line reason.";
    }

    private static String goalNudge(String goalDetail) {
        return "The discussion stopped but " + goalDetail + ". Review the recent conversation and continue "
                + "with the next concrete step, or return control by mentioning @user with what you need.";
    }

    /** The active agent who most recently posted a chat message, else any active agent. */
    private Participant lastAgentSpeaker(UUID conversationId) {
        List<Participant> agents = participants.findByConversationIdAndActiveTrue(conversationId).stream()
                .filter(p -> p.getKind() == Participant.Kind.AGENT)
                .toList();
        if (agents.isEmpty()) {
            return null;
        }
        Map<UUID, Participant> byId = new HashMap<>();
        agents.forEach(p -> byId.put(p.getId(), p));
        return messageRepository.findRecent(conversationId, null, 50).stream()
                .filter(m -> m.kind() == Message.Kind.CHAT && m.senderParticipantId() != null)
                .map(m -> byId.get(m.senderParticipantId()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(agents.getFirst());
    }

    private void setIdle(UUID conversationId, String reason, String detail) {
        Map<String, String> state = Map.of("reason", reason, "detail", detail);
        idleStates.put(conversationId, state);
        sseHub.publish(conversationId, "idle-state", state);
        log.info("Conversation {} idle: {} — {}", conversationId, reason, detail);
    }

    private void clearIdle(UUID conversationId) {
        if (idleStates.remove(conversationId) != null) {
            sseHub.publish(conversationId, "idle-state", Map.of("reason", ""));
        }
    }

    /** The workspace path for user-chosen (external) project directories, else null. */
    private String externalWorkspacePath(Conversation conversation) {
        if (conversation.getProjectId() == null) {
            return null;
        }
        return projectRepository.findById(conversation.getProjectId())
                .filter(dev.cowork.project.Project::isExternal)
                .map(dev.cowork.project.Project::getWorkspacePath)
                .orElse(null);
    }

    private boolean hasImplementationDocs(Conversation conversation) {
        if (conversation.getProjectId() == null) {
            return false;
        }
        return projectRepository.findById(conversation.getProjectId())
                .map(p -> java.nio.file.Files.isDirectory(
                        java.nio.file.Path.of(p.getWorkspacePath()).resolve("implementation_docs")))
                .orElse(false);
    }

    private List<Participant> resolveRecipients(Conversation conversation, Message message, boolean fromUser) {
        List<Participant> active = participants.findByConversationIdAndActiveTrue(conversation.getId());
        List<Participant> agents = active.stream()
                .filter(p -> p.getKind() == Participant.Kind.AGENT)
                .filter(p -> !p.getId().equals(message.senderParticipantId()))
                .toList();
        if (message.mentions().isEmpty()) {
            // Broadcasts trigger everyone for the user, nobody for an agent.
            return fromUser ? agents : List.of();
        }
        List<Participant> mentioned = new ArrayList<>();
        for (String name : message.mentions()) {
            agents.stream()
                    .filter(p -> p.getDisplayName().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(mentioned::add);
        }
        return mentioned;
    }

    @PreDestroy
    void shutdown() {
        workers.values().forEach(Worker::stop);
    }
}
