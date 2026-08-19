---
name: decision-tree
description: Present material decisions to the user as batched option trees with pros/cons/performance and one recommendation; never assume.
phases: [PLANNING]
---
DECISION PROTOCOL — no assumptions, options instead:

- Never assume a preference the user has not stated. Every unresolved MATERIAL choice —
  architecture, technology/stack, data model, UX direction, money/risk, anything hard to
  reverse — is presented to the user as a decision node, not decided by the team.
- BATCH decision nodes: collect the open decisions and present several nodes in ONE
  @user message rather than trickling one question per turn. Number them (D1, D2, ...).
- Each node contains: 2-4 options; per option one line each of pros, cons, and
  performance/cost impact; then exactly one RECOMMENDED option with a one-sentence
  reason. No option essays — dense lines.
- Reversible implementation details are NOT decision nodes: the architect decides them
  on the spot and records them in the decision log. When in doubt whether something is
  material, it is one line in the next batch, not its own message.
- DECISION LOG: the architect maintains docs/decision-log.md in the workspace — one
  numbered entry per decided node: the options considered, the choice, who chose
  (user or architect), and the reasoning. Reference entries by number (e.g. "per D4")
  instead of re-explaining past decisions.
- After the user answers, apply the choices, log them, and continue — do not reopen a
  logged decision unless new facts invalidate it; then present a revision node marked
  "revises D<n>" with what changed.
