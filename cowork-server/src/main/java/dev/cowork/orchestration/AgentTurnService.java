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
    private final dev.cowork.skill.SkillService skills;
    private final dev.cowork.rtk.RtkService rtk;
    private final dev.cowork.project.WorkspaceLocator workspaces;
    private final Semaphore cliSemaphore;

    public AgentTurnService(CliRunnerRegistry runners, AgentDefRepository agents,
                            ParticipantRepository participants, ProjectRepository projects,
                            McpTokenService tokens, MessageService messages, SseHub sseHub,
                            CoworkProperties properties, GitService git,
                            ConversationRepository conversations, ActiveTurnRegistry activeTurns,
                            dev.cowork.skill.SkillService skills, dev.cowork.rtk.RtkService rtk,
                            dev.cowork.project.WorkspaceLocator workspaces) {
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
        this.skills = skills;
        this.rtk = rtk;
        this.workspaces = workspaces;
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
        /** The Claude subscription's usage limit is exhausted — retrying is pointless. */
        USAGE_LIMIT,
        /** The CLI failed (crash, timeout, error) — worth one retry. */
        FAILED,
        /** The CLI succeeded but returned an empty reply — worth one retry. */
        BLANK
    }

    public record TurnOutcome(Message message, TurnFailure failure, String detail) {

        static TurnOutcome ok(Message message) { return new TurnOutcome(message, TurnFailure.NONE, null); }

        static TurnOutcome of(TurnFailure failure, String detail) { return new TurnOutcome(null, failure, detail); }
    }

    /** The agent's max-session-turns option (auto session rotation); 0 = disabled. */
    public int maxSessionTurns(Participant participant) {
        AgentDef agent = agents.findById(participant.getAgentId()).orElse(null);
        if (agent == null) {
            return 0;
        }
        Object value = parseOptions(agent.getOptions()).get("max-session-turns");
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()));
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid max-session-turns option '{}'", value);
            return 0;
        }
    }

    /** Stateless CLIs (no session resume) need the full history each turn, not a delta. */
    public boolean isStateless(Participant participant) {
        return agents.findById(participant.getAgentId())
                .flatMap(a -> runners.forType(a.getCliType()))
                .map(r -> !r.supportsSessions())
                .orElse(false);
    }

    /** Optional fallback vendor config ({@code fallback-cli} / {@code fallback-model} / {@code fallback-effort}). */
    private record Fallback(dev.cowork.agent.CliType cli, String model, String effort) {
    }

    /** How long to route to the fallback when the primary's limit error names no reset time. */
    private static final Duration DEFAULT_LIMIT_WINDOW = Duration.ofMinutes(30);

    /** Participants whose primary vendor is usage-limited, and until when (in-memory). */
    private final Map<java.util.UUID, java.time.Instant> primaryLimitedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** CLI session ids of fallback runs, kept separate from the primary session (in-memory). */
    private final Map<java.util.UUID, String> fallbackSessions = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Runs the agent's turn and posts its reply. A retry attempt (after a timeout or
     * crash) gets double the configured turn timeout, since timeouts usually mean the
     * turn was genuinely long-running rather than stuck. When the primary vendor's
     * usage limit is exhausted and the agent defines a fallback, the turn reruns on
     * the fallback model, and turns route straight to it until the limit resets.
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
        Map<String, Object> options = parseOptions(agent.getOptions());
        Fallback fallback = fallbackOf(options);
        boolean viaFallback = fallback != null && isPrimaryLimited(participant.getId())
                && runnerFor(fallback.cli()) != null;
        CliAgentRunner runner = viaFallback ? runnerFor(fallback.cli()) : runners.forType(agent.getCliType()).orElse(null);
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
            return runOnce(conversation, agent, participant, prompt, round, retry, options, fallback, viaFallback);
        } finally {
            activeTurns.unregister(conversation.getId(), participant.getId());
            cliSemaphore.release();
            publishStatus(conversation, participant, "idle");
        }
    }

    private TurnOutcome runOnce(Conversation conversation, AgentDef agent, Participant participant,
                                String prompt, int round, boolean retry, Map<String, Object> options,
                                Fallback fallback, boolean viaFallback) {
        CliAgentRunner runner = viaFallback ? runnerFor(fallback.cli())
                : runners.forType(agent.getCliType()).orElse(null);
        if (runner == null || !runner.available()) {
            return TurnOutcome.of(TurnFailure.CONFIG, "CLI is not installed");
        }
        String model = viaFallback ? fallback.model() : agent.getModel();
        String sessionId = viaFallback ? fallbackSessions.get(participant.getId())
                : participant.getCliSessionId();
        Map<String, Object> effectiveOptions = options;
        String effectivePrompt = prompt;
        if (viaFallback) {
            effectiveOptions = new java.util.HashMap<>(options);
            if (fallback.effort() == null || fallback.effort().isBlank()) {
                effectiveOptions.remove("effort");
            } else {
                effectiveOptions.put("effort", fallback.effort());
            }
            if (sessionId == null) {
                effectivePrompt = prompt + "\n[COORDINATOR]\nYou are running as the FALLBACK model ("
                        + fallback.model() + ") for agent \"" + participant.getDisplayName()
                        + "\" because its primary vendor is usage-limited. This is a fresh session: if "
                        + "context seems missing, read agent-notes/" + participant.getDisplayName()
                        + ".md and use read_conversation before acting.\n";
            }
        }
        try {
            String token = tokens.issueToken(participant);
            TurnRequest request = new TurnRequest(
                    agent.getName(),
                    participant.getId().toString() + (viaFallback ? "-fallback" : ""),
                    effectivePrompt,
                    agent.getPersona(),
                    model,
                    sessionId,
                    resolveWorkDir(conversation),
                    properties.mcp().baseUrl(),
                    token,
                    conversation.getPhase() == Conversation.Phase.IMPLEMENTATION,
                    effectiveOptions,
                    turnEnvironment(conversation),
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
                            participant.getId(), participant.getDisplayName(), process, round));
                }
            };

            TurnResult result = runner.run(request, listener);

            if (viaFallback) {
                if (result.sessionId() != null) {
                    fallbackSessions.put(participant.getId(), result.sessionId());
                }
            } else if (result.sessionId() != null && !result.sessionId().equals(participant.getCliSessionId())) {
                // Re-fetch before saving: the agent may have set flags (e.g. a session
                // refresh request) mid-turn via MCP, which a stale save would erase.
                Participant current = participants.findById(participant.getId()).orElse(participant);
                current.setCliSessionId(result.sessionId());
                participants.save(current);
                participant.setCliSessionId(result.sessionId());
            }
            commitWorkspaceChanges(conversation, agent.getName());
            recordSpend(conversation, result.costUsd());
            publishRtkSavings(conversation);
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
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.toLowerCase().contains("usage limit")) {
                log.warn("Agent '{}' hit its vendor usage limit ({}): {}", agent.getName(),
                        viaFallback ? "fallback" : "primary", message);
                if (!viaFallback && fallback != null && runnerFor(fallback.cli()) != null) {
                    markPrimaryLimited(conversation, participant, agent, fallback, message);
                    activeTurns.unregister(conversation.getId(), participant.getId());
                    return runOnce(conversation, agent, participant, prompt, round, retry, options,
                            fallback, true);
                }
                return TurnOutcome.of(TurnFailure.USAGE_LIMIT, message);
            }
            log.warn("Agent '{}' turn failed: {}", agent.getName(), message);
            return TurnOutcome.of(TurnFailure.FAILED, message);
        }
    }

    /** Re-reads rtk's ledger after a turn and pushes the new totals to the UI. */
    private void publishRtkSavings(Conversation conversation) {
        if (!skills.isActive(conversation, "rtk")) {
            return;
        }
        rtk.invalidate(conversation.getId());
        var savings = rtk.savingsFor(conversation.getId(), workspaces.locate(conversation));
        if (savings.available()) {
            sseHub.publish(conversation.getId(), "rtk-savings", savings);
        }
    }

    /**
     * Environment overrides for this turn's subprocess. rtk's telemetry already defaults to
     * off; the explicit opt-out makes it independent of the machine's rtk config.
     */
    private Map<String, String> turnEnvironment(Conversation conversation) {
        if (!skills.isActive(conversation, "rtk")) {
            return Map.of();
        }
        return Map.of("RTK_TELEMETRY_DISABLED", "1");
    }

    private CliAgentRunner runnerFor(dev.cowork.agent.CliType cli) {
        return runners.forType(cli).filter(CliAgentRunner::available).orElse(null);
    }

    private static Fallback fallbackOf(Map<String, Object> options) {
        Object cli = options.get("fallback-cli");
        Object model = options.get("fallback-model");
        if (cli == null || model == null) {
            return null;
        }
        try {
            Object effort = options.get("fallback-effort");
            return new Fallback(dev.cowork.agent.CliType.valueOf(cli.toString().trim().toUpperCase()),
                    model.toString().trim(), effort == null ? null : effort.toString().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid fallback-cli option '{}'", cli);
            return null;
        }
    }

    private boolean isPrimaryLimited(java.util.UUID participantId) {
        java.time.Instant until = primaryLimitedUntil.get(participantId);
        if (until == null) {
            return false;
        }
        if (java.time.Instant.now().isAfter(until)) {
            primaryLimitedUntil.remove(participantId);
            return false;
        }
        return true;
    }

    /** Records the primary's limit window (reset time from the error, else a default) and tells the room. */
    private void markPrimaryLimited(Conversation conversation, Participant participant, AgentDef agent,
                                    Fallback fallback, String errorMessage) {
        java.time.Instant resetAt = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\|(\\d{10,13})").matcher(errorMessage);
        if (m.find()) {
            long value = Long.parseLong(m.group(1));
            resetAt = m.group(1).length() >= 13
                    ? java.time.Instant.ofEpochMilli(value) : java.time.Instant.ofEpochSecond(value);
        }
        if (resetAt == null || resetAt.isBefore(java.time.Instant.now())) {
            resetAt = java.time.Instant.now().plus(DEFAULT_LIMIT_WINDOW);
        }
        primaryLimitedUntil.put(participant.getId(), resetAt);
        String when = java.time.LocalTime.ofInstant(resetAt, java.time.ZoneId.systemDefault())
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
        messages.postSystem(conversation.getId(), Message.Kind.SYSTEM,
                "Agent '" + agent.getName() + "' hit its " + agent.getCliType()
                        + " usage limit — switching to fallback " + fallback.cli() + "/" + fallback.model()
                        + " until about " + when + ".", null);
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
                                + (commit.stat() == null ? "" : " (" + commit.stat() + ")") + ".",
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
        Path dir = workspaces.locate(conversation);
        try {
            Files.createDirectories(dir);
            // The per-agent persistent-notes folder referenced by the standing instructions.
            Files.createDirectories(dir.resolve("agent-notes"));
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
