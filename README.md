# Cowork

A multi-agent collaboration chat application: a human user and a group of AI coding agents
(backed by the Claude Code, OpenAI Codex, and GitHub Copilot CLIs) plan software together in
chat rooms, vote on decisions, and then implement the plan as a team in a shared workspace.

## How it works

- **Conversations** are chat rooms. Each has a phase (`PLANNING` → `IMPLEMENTATION`), a vote
  mode (`MAJORITY` or `UNANIMOUS`), and a set of participants: the user plus any agents you
  select. Multiple conversations run simultaneously and independently.
- **Agents** are defined by `agents/<name>-agent.md` files (YAML frontmatter + persona).
  The directory is hot-reloaded — edit a file and the registry updates within a second.
- **Addressing**: prefix a message with `@name` (or `@a,@b`) to address specific
  participants. A user message with no prefix broadcasts to every agent. Agents only trigger
  each other by explicit mention, and agent-to-agent hand-offs stop after the conversation's
  round budget (default 4) until the user speaks again.
- **Decisions are structured**: agents raise proposals (`PLAN_APPROVAL`, `TASK_ASSIGNMENT`,
  `CODE_CHANGE`) and vote through MCP tools. The database enforces one vote per participant.
  Deadlocks go to the user; the user can also override any open proposal. Moving to
  IMPLEMENTATION always requires the user's explicit confirmation, even after agents approve.
- **The common toolset** is an embedded MCP server (streamable HTTP at `/mcp`). Every agent
  turn gets a fresh per-participant bearer token, so the server always knows who is calling:
  `post_message`, `read_conversation`, `search_messages`, `list_participants`,
  `create_proposal`, `cast_vote`, `list_proposals`, `create_task`, `list_tasks`,
  `update_task_status`, `list_commits`, `get_commit_diff`. During IMPLEMENTATION, agents
  also get the `chrome-devtools-mcp` server so they can open the UI they are building,
  take screenshots, and read the console.
- **Git-backed review**: every project workspace is a git repository. Implementation turns
  that change files are auto-committed under the agent's name; the Changes panel lists
  commits and opens colored unified diffs, and CODE_CHANGE proposals can reference the
  commit they critique.
- **Costs & budgets**: each turn's cost is recorded on its message (chip in the UI) and
  totaled per conversation in the header. Set a budget in settings — when spend reaches it,
  agent turns pause until you raise it.
- **Live turns**: agents' replies stream in as they think, with a live tool-call ticker,
  an expandable "N tool calls" activity transcript on every reply, and a Cancel button
  that kills the running CLI process tree.
- **Reference files**: upload specs, mockups, and screenshots from the Files panel — they
  land in the workspace's `implementation_docs/` directory where agents read them directly.
- **Round-limit continue**: when agent-to-agent hand-offs hit the round budget, a banner
  lets you grant more rounds with one click.
- **✨ Agent assistant**: the (full-screen) agent manager has a chat assistant that helps
  you write or refine `*-agent.md` files and applies its rewrites to the editor as an
  unsaved draft for your review.
- **Persistence** is PostgreSQL + TimescaleDB; chat messages live in a hypertable with
  full-text search. Everything (conversations, messages, proposals, votes, tasks, commits,
  uploads) survives restarts.

## Prerequisites

- JDK 25, Maven 3.9+
- A running PostgreSQL with the TimescaleDB extension, and a `cowork` database:
  `docker exec postgres-timescale psql -U postgres -c "CREATE DATABASE cowork"`
  (connection settings in `cowork-server/src/main/resources/application.yml`)
- At least one agent CLI installed and authenticated:
  - **Claude Code** (`claude`) — fully supported
  - **Codex CLI** (`codex`) — supported (session resume via `codex exec resume`)
  - **Copilot CLI** (`copilot`) — EXPERIMENTAL (no JSON output/session capture; the adapter
    sends full context each turn and parses plain text)
  Agents whose CLI is missing simply post a system notice instead of failing.

## Build & run

```bash
mvn clean package -DskipTests     # builds the React UI into the server jar
java -jar cowork-server/target/cowork-server-0.1.0-SNAPSHOT.jar
```

Open http://localhost:8090. For frontend development: `cd cowork-frontend && npm run dev`
(Vite on :5173, proxying `/api` and `/mcp` to :8090).

## Defining agents

`agents/mydesigner-agent.md`:

```markdown
---
name: mydesigner
cli: claude          # claude | codex | copilot
model: claude-sonnet-4-5   # optional
options: { permission-mode: acceptEdits }   # optional, CLI-specific
description: UX-focused designer
---
You are "mydesigner", a UX-focused designer... (persona / system prompt)
```

## Typical flow

1. Create a conversation, pick agents, choose majority or unanimous voting.
2. Discuss the goal; agents debate, create tasks, and raise a `PLAN_APPROVAL` proposal.
3. When it passes (and you're happy), confirm the switch to IMPLEMENTATION in the phase
   banner — a project workspace is created under `workspaces/<project>/`.
4. Agents propose `TASK_ASSIGNMENT`s, vote on who implements what, and the winners code in
   the workspace. Review suggestions become `CODE_CHANGE` proposals, voted on before being
   applied. Steer at any time by chatting or overriding votes.

## Configuration knobs (`application.yml` → `cowork:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `agents-dir` | `../agents` | Where `*-agent.md` files live |
| `workspaces-dir` | `../workspaces` | Where project workspaces are created |
| `cli.max-concurrent` | 4 | Global cap on simultaneous CLI subprocesses |
| `cli.turn-timeout-seconds` | 300 | Hard per-turn timeout (process tree is killed) |
| `mcp.base-url` | `http://localhost:8090/mcp` | URL handed to agent CLIs |
