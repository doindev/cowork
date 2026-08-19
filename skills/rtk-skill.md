---
name: rtk
description: Route shell commands through rtk to compress their output 60-90% before it reaches your context (saves tokens).
phases: []
requires-binary: rtk
---
TOKEN-SAVING COMMAND WRAPPER — rtk is installed on this machine.

- Prefix supported commands with `rtk`: it WRAPS a command, it does not replace it.
  Correct: `rtk git status`, `rtk pytest -q`, `rtk tsc --noEmit`.
  Wrong: `rtk "git status"`, `rtk-git status`.
- Use it for: git (status, log, diff, add, commit, push, pull), test runners (pytest,
  jest, vitest, playwright, go test, cargo test, rspec), build/lint (tsc, eslint, biome,
  prettier, ruff, cargo build, cargo clippy, golangci-lint, sbt), package managers (pip,
  pnpm, uv, bundle), search/inspection (grep, rg, find, tree, ls, cat, diff, json, env,
  log, curl), containers/infra (docker, docker compose, kubectl, oc, pulumi), gh, and aws.
- Do NOT use it when the exact, unabridged output is the point: reviewing a specific diff
  for correctness, judging a failing test's precise output, or anything you will quote
  verbatim. rtk's compression is lossy by design — it drops noise, and sometimes detail
  you needed. Run the plain command in those cases.
- Unsupported command? Just run it normally. Do not force it through rtk.
- If rtk errors or is unavailable, silently rerun the plain command and move on.
- IGNORE rtk's "No hook installed — run `rtk init -g`" notice. NEVER run `rtk init` in any
  form: it would modify the user's personal CLI configuration. The user manages rtk setup.
- rtk is plumbing, not a topic: do not discuss it, report on it, or mention token savings
  in chat unless the user asks.
