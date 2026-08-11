package dev.cowork.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;

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
 *   <li>Agent message → only agents it explicitly mentions (its broadcasts trigger nobody).</li>
 *   <li>Agent-to-agent hops beyond the conversation's round budget are dropped with a SYSTEM notice.</li>
 * </ul>
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private record TurnJob(UUID participantId, int round) {
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
            if (conversation == null || participant == null || !participant.isActive()) {
                return;
            }
            boolean stateless = turnService.isStateless(participant);
            Instant since = stateless ? Instant.EPOCH : lastSeen.getOrDefault(participant.getId(), Instant.EPOCH);
            List<Message> delta = messageRepository.findSince(conversationId, since, 200);
            if (!stateless) {
                lastSeen.put(participant.getId(), delta.isEmpty() ? since : delta.getLast().createdAt());
            }

            List<Participant> roster = participants.findByConversationId(conversationId);
            int roundsRemaining = conversation.getMaxAgentRounds() - job.round();
            String prompt = transcriptBuilder.build(conversation, participant, roster, delta, roundsRemaining,
                    hasImplementationDocs(conversation));
            turnService.execute(conversation, participant, prompt, job.round());
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
    private final Map<UUID, Worker> workers = new ConcurrentHashMap<>();
    private final Map<UUID, List<TurnJob>> droppedByBudget = new ConcurrentHashMap<>();

    public ConversationOrchestrator(ConversationRepository conversations, ParticipantRepository participants,
                                    MessageRepository messageRepository, MessageService messages,
                                    TranscriptBuilder transcriptBuilder, AgentTurnService turnService,
                                    dev.cowork.stream.SseHub sseHub,
                                    dev.cowork.project.ProjectRepository projectRepository) {
        this.conversations = conversations;
        this.participants = participants;
        this.messageRepository = messageRepository;
        this.messages = messages;
        this.transcriptBuilder = transcriptBuilder;
        this.turnService = turnService;
        this.sseHub = sseHub;
        this.projectRepository = projectRepository;
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

        boolean fromUser = event.senderKind() == Participant.Kind.USER;
        int nextRound = fromUser ? 0 : message.round() + 1;

        List<Participant> recipients = resolveRecipients(conversation, message, fromUser);
        if (recipients.isEmpty()) {
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
        if (!fromUser && nextRound > conversation.getMaxAgentRounds()) {
            droppedByBudget.computeIfAbsent(conversation.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .addAll(recipients.stream().map(p -> new TurnJob(p.getId(), 0)).toList());
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "Agent hand-off round limit (" + conversation.getMaxAgentRounds()
                            + ") reached — waiting for the user.", null);
            sseHub.publish(conversation.getId(), "round-limit",
                    Map.of("dropped", recipients.stream().map(Participant::getDisplayName).toList()));
            return;
        }

        Worker worker = workers.computeIfAbsent(conversation.getId(), Worker::new);
        for (Participant recipient : recipients) {
            worker.enqueue(new TurnJob(recipient.getId(), nextRound));
        }
    }

    /** Directly enqueues an agent turn (used for approved-task side effects). Round 0 = fresh budget. */
    public void triggerTurn(UUID conversationId, UUID participantId) {
        workers.computeIfAbsent(conversationId, Worker::new).enqueue(new TurnJob(participantId, 0));
    }

    /**
     * User grant after a round-limit stop: re-enqueues the turns that were dropped,
     * with a fresh round budget. Returns the number of turns resumed.
     */
    public int continueRounds(UUID conversationId) {
        List<TurnJob> dropped = droppedByBudget.remove(conversationId);
        if (dropped == null || dropped.isEmpty()) {
            return 0;
        }
        messages.postSystem(conversationId, Message.Kind.SYSTEM,
                "The user granted more agent rounds — resuming.", null);
        Worker worker = workers.computeIfAbsent(conversationId, Worker::new);
        dropped.forEach(worker::enqueue);
        return dropped.size();
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
