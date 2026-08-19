# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Cowork is a multi-agent collaboration chat app: a human and AI coding agents (backed by the
Claude Code, Codex, and Copilot CLIs) plan software in chat rooms, vote on structured proposals,
and implement the plan in shared git-backed workspaces. Two Maven modules: `cowork-server`
(Spring Boot 4, Java 25) and `cowork-frontend` (React 18 + Vite + TanStack Query), with the
built frontend embedded in the server jar.

## Build & run

- **Full rebuild (the reliable way):** stop both the backend (port 8090) and the Vite dev
  server (port 5173) first, then `mvn -DskipTests clean install` from the repo root (~35s).
  Incremental `mvn package` is unreliable on Windows — it can leave a stale server jar with an
  outdated embedded frontend even though the reactor reports SUCCESS, and `~/.m2` keeps a stale
  `cowork-frontend` snapshot unless `install` is run. Stopping the dev server first matters
  because it runs from `cowork-frontend/target/node/npm.cmd`, which `clean` deletes.
- **Run:** `java -jar cowork-server/target/cowork-server-0.1.0-SNAPSHOT.jar` → http://localhost:8090
  (port 8090 because 8080 is taken on this machine).
- **Frontend dev:** `cd cowork-frontend && npm run dev` — Vite on :5173, proxying `/api` and
  `/mcp` to :8090.
- **Frontend type-check without a build:** from `cowork-frontend/`, run
  `node_modules\.bin\tsc.cmd -b --force` (`npx tsc` does not work here; the Vite dev server
  does not type-check).
- **Tests:** `mvn -pl cowork-server test`; a single class:
  `mvn -pl cowork-server test -Dtest=CodexRunnerParseTest`. Tests are plain unit tests — no
  database needed.
- **Live directories gotcha:** the running server resolves `cowork.agents-dir: ../agents` (and
  `../skills`, `../workspaces`) relative to its working directory — on this machine that is
  `C:\Users\timhj\eclipse-workspace\agents` etc., siblings of the repo, NOT the repo's own
  `agents/`/`workspaces/` folders (those are the git copies). To make an agent or skill file
  change live, copy it to the sibling directory (hot-reloads in ~1s).
- **Runtime prerequisites:** JDK 25, PostgreSQL with TimescaleDB (local `postgres-timescale`
  docker container, postgres/postgres) with a `cowork` database; Flyway migrations in
  `cowork-server/src/main/resources/db/migration` run on startup. At least one agent CLI on
  PATH (`claude` is the one installed on this machine); agents whose CLI is missing post a
  system notice instead of failing.
- After UI changes, verify freshness by comparing the asset hash in
  `http://localhost:8090/index.html` with `cowork-frontend/dist/index.html` (hard-reload —
  index.html can be browser-cached), and check the result visually in Chrome.

## Architecture

The core loop: a message arrives → the orchestrator decides which agents must respond → each
agent turn shells out to its coding CLI as a subprocess → the CLI talks back to the server
through an embedded MCP server → the reply is persisted and streamed to the UI over SSE.

Server packages (`cowork-server/src/main/java/dev/cowork/`):

- **`orchestration`** — the heart. `ConversationOrchestrator` routes messages to turns: one
  serialized worker (virtual thread + queue) per conversation, conversations run in parallel.
  User messages without mentions broadcast to all active agents; `@name` mentions target
  specific ones; agents trigger each other only by explicit mention, bounded by the
  conversation's round budget. `IdleStateService` is a deterministic classifier that decides
  what to do when a chain stalls (nudge missing voters, re-prompt, wait on user).
  `AgentTurnService` executes one turn: resolves the CLI adapter, issues a fresh MCP token,
  runs the subprocess under a global semaphore (`cli.max-concurrent`), classifies the outcome
  (`TurnFailure`: retry-worthy vs. config vs. usage-limit exhaustion), and posts the reply.
  `TranscriptBuilder` builds the per-turn prompt (delta since the persisted cursor, or a recap
  when a session starts fresh).
- **`cli`** — adapter SPI (`CliAgentRunner`) with one implementation per CLI:
  `ClaudeCodeRunner` (full support, session resume), `CodexRunner` (resume via
  `codex exec resume`), `CopilotRunner` (experimental/stateless — full context each turn,
  plain-text parsing). Stateless CLIs are signaled via `supportsSessions()`.
  `ProcessExecutor` handles process-tree spawn/kill and timeouts.
- **`mcp`** — the common toolset agents use, exposed as a Spring AI MCP server (streamable
  HTTP at `/mcp`). Each turn gets a fresh per-participant bearer token
  (`McpTokenService`), and `McpIdentityFilter`/`McpCallerContext` map the token to the calling
  participant — this is how `cast_vote`, `post_message`, etc. know who is calling. Tools are
  grouped in `CoworkChatTools`, `CoworkProposalTools`, `CoworkTaskTools`, `CoworkGitTools`,
  `CoworkFileTools`.
- **`agent`** — agent definitions loaded from `agents/<name>-agent.md` files (YAML
  frontmatter: `cli`, `model`, `options` like `turn-timeout-seconds`/`effort`/
  `max-session-turns`; body = persona/system prompt). The directory is hot-reloaded (~1s).
  `AgentAssistService` powers the ✨ assistant that drafts/refines agent files.
- **`conversation` / `message` / `proposal` / `project`** — domain + REST controllers.
  Proposals (`PLAN_APPROVAL`, `TASK_ASSIGNMENT`, `CODE_CHANGE`) are voted on with DB-enforced
  one-vote-per-participant; deadlocks escalate to the user; phase moves to IMPLEMENTATION only
  with explicit user confirmation (`PhaseController`), which creates a git workspace under
  `workspaces/<project>/`. `GitService` auto-commits implementation turns under the agent's
  name; `ProposalSideEffects` applies passed proposals.
- **`stream`** — `SseHub` pushes live events (streaming replies, tool-call ticker, activity)
  to the UI.

Frontend: `src/api.ts` is the single typed API layer; `App.tsx` + `components/` consume it via
TanStack Query, with SSE for live updates.

Persistence is PostgreSQL + TimescaleDB (messages in a hypertable with full-text search) via
Spring Data JDBC. Configuration knobs live under `cowork:` in
`cowork-server/src/main/resources/application.yml` (agents dir, workspaces dir, CLI
concurrency/timeout, MCP base URL).
