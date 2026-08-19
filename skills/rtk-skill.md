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
- rtk runs the real tool underneath, so it only works when that tool is installed.
{{RTK_UNAVAILABLE}}- Worth wrapping when available: git (status, log, diff), test runners (pytest, jest,
  vitest, go test, cargo test), build/lint (tsc, eslint, biome, ruff, cargo build),
  package managers (pip, pnpm, uv, bundle), containers/infra (docker, kubectl, pulumi),
  and gh/aws. Everything else: run it plain.
- Do NOT use it when the exact, unabridged output is the point: reviewing a specific diff
  for correctness, judging a failing test's precise output, or anything you will quote
  verbatim. rtk's compression is lossy by design — it drops noise, and sometimes detail
  you needed. Run the plain command in those cases.
- If a wrapped command fails with "Failed to resolve … via PATH" or "Failed to run …",
  rtk never launched the tool: immediately rerun the plain command and stop wrapping that
  tool for the rest of the session. Never let rtk block your actual work.
- IGNORE rtk's "No hook installed — run `rtk init -g`" notice. NEVER run `rtk init` in any
  form: it would modify the user's personal CLI configuration. The user manages rtk setup.
- rtk is plumbing, not a topic: do not discuss it, report on it, or mention token savings
  in chat unless the user asks.
