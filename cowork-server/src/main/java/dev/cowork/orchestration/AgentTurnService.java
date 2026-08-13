package dev.cowork.orchestration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

import dev.cowork.agent.AgentDef;
import dev.cowork.agent.AgentDefRepository;
import dev.cowork.cli.CliAgentRunner;
import dev.cowork.cli.CliRunnerRegistry;
import dev.cowork.cli.CliTurnException;
import dev.cowork.cli.TurnListener;
import dev.cowork.cli.TurnRequest;
import dev.cowork.cli.TurnResult;
import dev.cowork.config.CoworkProperties;
import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationRepository;
import dev.cowork.conversation.McpTokenService;
import dev.cowork.conversation.Participant;
import dev.cowork.conversation.ParticipantRepository;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import dev.cowork.project.GitService;
import dev.cowork.project.Project;
import dev.cowork.project.ProjectRepository;
import dev.cowork.stream.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Executes a single agent turn: resolves the CLI adapter, issues a fresh MCP token,
 * runs the subprocess under the global concurrency cap, and posts the reply.
 */
@Service
public class AgentTurnService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);

    private final CliRunnerRegistry runners;
    private final AgentDefRepository agents;
    private final ParticipantRepository participants;
    private final ProjectRepository projects;
    private final McpTokenService tokens;
    private final MessageService messages;
    private final SseHub sseHub;
    private final CoworkProperties properties;
    private final GitService git;
    private final ConversationRepository conversations;
    private final ActiveTurnRegistry activeTurns;
    private final Semaphore cliSemaphore;

    public AgentTurnService(CliRunnerRegistry runners, AgentDefRepository agents,
                            ParticipantRepository participants, ProjectRepository projects,
                            McpTokenService tokens, MessageService messages, SseHub sseHub,
                            CoworkProperties properties, GitService git,
                            ConversationRepository conversations, ActiveTurnRegistry activeTurns) {
        this.runners = runners;
        this.agents = agents;
        this.participants = participants;
        this.projects = projects;
        this.tokens = tokens;
        this.messages = messages;
        this.sseHub = sseHub;
        this.properties = properties;
        this.git = git;
        this.conversations = conversations;
        this.activeTurns = activeTurns;
        this.cliSemaphore = new Semaphore(properties.cli().maxConcurrent());
    }

    /** How an agent turn ended, so the orchestrator can retry or surface the outcome. */
    public enum TurnFailure {
        /** Reply posted normally. */
        NONE,
        /** Turn intentionally skipped (budget exhausted, executor interrupted). */
        SKIPPED,
        /** Agent or its CLI is unavailable — retrying cannot help. */
        CONFIG,
        /** The user cancelled the running turn. */
        CANCELLED,
        /** The CLI failed (crash, timeout, error) — worth one retry. */
        FAILED,
        /** The CLI succeeded but returned an empty reply — worth one retry. */
        BLANK
    }

    public record TurnOutcome(Message message, TurnFailure failure, String detail) {

        static TurnOutcome ok(Message message) { return new TurnOutcome(message, TurnFailure.NONE, null); }

        static TurnOutcome of(TurnFailure failure, String detail) { return new TurnOutcome(null, failure, detail); }
    }

    /** Stateless CLIs (no session resume) need the full history each turn, not a delta. */
    public boolean isStateless(Participant participant) {
        return agents.findById(participant.getAgentId())
                .flatMap(a -> runners.forType(a.getCliType()))
                .map(r -> !r.supportsSessions())
                .orElse(false);
    }

    /**
     * Runs the agent's turn and posts its reply. A retry attempt (after a timeout or
     * crash) gets double the configured turn timeout, since timeouts usually mean the
     * turn was genuinely long-running rather than stuck.
     */
    public TurnOutcome execute(Conversation conversation, Participant participant, String prompt, int round,
                               boolean retry) {
        if (conversation.isBudgetExhausted()) {
            return TurnOutcome.of(TurnFailure.SKIPPED, "budget exhausted");
        }
        AgentDef agent = agents.findById(participant.getAgentId()).orElse(null);
        if (agent == null || !agent.isEnabled()) {
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "Agent '" + participant.getDisplayName() + "' is no longer available.", null);
            return TurnOutcome.of(TurnFailure.CONFIG, "agent is no longer available");
        }
        CliAgentRunner runner = runners.forType(agent.getCliType()).orElse(null);
        if (runner == null || !runner.available()) {
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    "Agent '" + agent.getName() + "' uses CLI '" + agent.getCliType()
                            + "' which is not installed on this machine.", null);
            return TurnOutcome.of(TurnFailure.CONFIG, "CLI '" + agent.getCliType() + "' is not installed");
        }

        publishStatus(conversation, participant, "thinking");
        try {
            cliSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publishStatus(conversation, participant, "idle");
            return TurnOutcome.of(TurnFailure.SKIPPED, "interrupted");
        }
        try {
            String token = tokens.issueToken(participant);
            Map<String, Object> options = parseOptions(agent.getOptions());
            TurnRequest request = new TurnRequest(
                    agent.getName(),
                    prompt,
                    agent.getPersona(),
                    agent.getModel(),
                    participant.getCliSessionId(),
                    resolveWorkDir(conversation),
                    properties.mcp().baseUrl(),
                    token,
                    conversation.getPhase() == Conversation.Phase.IMPLEMENTATION,
                    options,
                    turnTimeout(options, retry));

            TurnListener listener = new TurnListener() {
                @Override
                public void onPartialText(String textSoFar) {
                    sseHub.publish(conversation.getId(), "partial",
                            Map.of("name", participant.getDisplayName(), "text", textSoFar));
                }

                @Override
                public void onActivity(String tool, String summary) {
                    sseHub.publish(conversation.getId(), "activity",
                            Map.of("name", participant.getDisplayName(), "tool", tool, "summary", summary));
                }

                @Override
                public void onProcessStart(Process process) {
                    activeTurns.register(conversation.getId(), new ActiveTurnRegistry.ActiveTurn(
                            participant.getId(), participant.getDisplayName(), process));
                }
            };

            TurnResult result = runner.run(request, listener);

            if (result.sessionId() != null && !result.sessionId().equals(participant.getCliSessionId())) {
                participant.setCliSessionId(result.sessionId());
                participants.save(participant);
            }
            commitWorkspaceChanges(conversation, agent.getName());
            recordSpend(conversation, result.costUsd());
            if (result.text() != null && !result.text().isBlank()) {
                return TurnOutcome.ok(messages.post(conversation.getId(), participant, result.text().trim(),
                        round, result.costUsd(), result.activity()));
            }
            return TurnOutcome.of(TurnFailure.BLANK, "returned an empty reply");
        } catch (CliTurnException e) {
            if (activeTurns.consumeCancelled(conversation.getId(), participant.getId())) {
                log.info("Agent '{}' turn cancelled by the user", agent.getName());
                return TurnOutcome.of(TurnFailure.CANCELLED, "cancelled by the user");
            }
            log.warn("Agent '{}' turn failed: {}", agent.getName(), e.getMessage());
            return TurnOutcome.of(TurnFailure.FAILED, e.getMessage());
        } finally {
            activeTurns.unregister(conversation.getId(), participant.getId());
            cliSemaphore.release();
            publishStatus(conversation, participant, "idle");
        }
    }

    /** Adds a turn's cost to the conversation's running spend and notifies the UI. */
    private void recordSpend(Conversation conversation, Double costUsd) {
        if (costUsd == null || costUsd <= 0) {
            return;
        }
        Conversation fresh = conversations.findById(conversation.getId()).orElse(conversation);
        fresh.setSpentUsd(fresh.getSpentUsd() + costUsd);
        conversations.save(fresh);
        conversation.setSpentUsd(fresh.getSpentUsd());
        sseHub.publish(conversation.getId(), "spend", Map.of(
                "spentUsd", fresh.getSpentUsd(),
                "budgetUsd", fresh.getBudgetUsd() == null ? -1 : fresh.getBudgetUsd()));
        if (fresh.isBudgetExhausted()) {
            messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                    String.format("Budget exhausted ($%.2f of $%.2f spent) — agent turns are paused. "
                            + "Raise the budget in conversation settings to continue.",
                            fresh.getSpentUsd(), fresh.getBudgetUsd()), null);
        }
    }

    /** After an implementation-phase turn, auto-commit any workspace changes as the agent. */
    private void commitWorkspaceChanges(Conversation conversation, String agentName) {
        if (conversation.getPhase() != Conversation.Phase.IMPLEMENTATION || conversation.getProjectId() == null) {
            return;
        }
        projects.findById(conversation.getProjectId()).ifPresent(project -> {
            Path workspace = Path.of(project.getWorkspacePath());
            git.commitAll(workspace, agentName, agentName + ": agent turn").ifPresent(commit -> {
                messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                        agentName + " committed " + commit.hash().substring(0, 8)
                                + (commit.stat() == null ? "" : " (" + commit.stat() + ")")
                                + " — review with get_commit_diff(\"" + commit.hash().substring(0, 8) + "\").",
                        null);
                sseHub.publish(conversation.getId(), "commit", Map.of(
                        "hash", commit.hash(),
                        "author", commit.author(),
                        "message", commit.message(),
                        "stat", commit.stat() == null ? "" : commit.stat()));
            });
        });
    }

    private Path resolveWorkDir(Conversation conversation) {
        Path base = Path.of(properties.workspacesDir()).toAbsolutePath().normalize();
        Path dir;
        if (conversation.getProjectId() != null) {
            // Any conversation with a project works in its workspace regardless of phase,
            // so agents can read uploaded implementation_docs while still planning.
            dir = projects.findById(conversation.getProjectId())
                    .map(Project::getWorkspacePath)
                    .map(Path::of)
                    .orElse(base.resolve("_planning").resolve(conversation.getId().toString()));
        } else {
            dir = base.resolve("_planning").resolve(conversation.getId().toString());
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create working directory " + dir, e);
        }
        return dir;
    }

    /**
     * The turn timeout: the agent's {@code turn-timeout-seconds} option when set,
     * else the global default. Retries get double, since a timed-out turn is usually
     * long-running rather than stuck.
     */
    private Duration turnTimeout(Map<String, Object> options, boolean retry) {
        int seconds = properties.cli().turnTimeoutSeconds();
        Object override = options.get("turn-timeout-seconds");
        if (override != null) {
            try {
                int v = Integer.parseInt(override.toString().trim());
                if (v > 0) {
                    seconds = v;
                }
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric turn-timeout-seconds option: {}", override);
            }
        }
        return Duration.ofSeconds(seconds * (retry ? 2L : 1L));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOptions(String optionsYaml) {
        if (optionsYaml == null || optionsYaml.isBlank()) {
            return Map.of();
        }
        Object parsed = new Yaml().load(optionsYaml);
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    private void publishStatus(Conversation conversation, Participant participant, String status) {
        sseHub.publish(conversation.getId(), "agent-status",
                Map.of("name", participant.getDisplayName(), "status", status));
    }
}
