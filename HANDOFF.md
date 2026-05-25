# Handover — 2026-05-26

**Branch:** `main` (clean)

## Current state

Issue #6 (JVM cold-start flakiness) complete — shared JVM test infrastructure.
`global-setup.js` starts one Quarkus JVM, all 7 specs share it via env-var
forwarding, `global-teardown.js` kills it. 8 JVM launches → 1. Suite runs in
~10s, passes on first attempt. Branch merged to main, issue closed. 54 tests
passing, 2 skipped.

## What was built this session

- **Shared JVM** — `global-setup.js` imports `JavaServer`, starts Quarkus
  directly, stores port in `TEST_QUARKUS_PORT`. `helpers.js` forwards as
  `QUARKUS_PORT` to Electron env. `main.js` skips internal spawn when set.
- **Global teardown** — module-scoped server with `getServer()` accessor,
  teardown calls `killServer()` (not raw PID kill — avoids orphan on crash).
- **Code review fix** — original design used raw PID kill in teardown;
  review caught orphan-process risk from JavaServer's restart machinery.
  Refactored to module-scoped instance with proper process manager cleanup.
- **CLAUDE.md and protocol updates** — removed cold-start workaround note,
  updated `playwright-jvm-warmup` protocol to document shared JVM mechanism.

## Blog entries

- `blog/2026-05-25-mdp03-one-jvm-to-rule-them-all.md` (this session)

## What's left

- Branch `issue-11-word-level-diff` still exists locally and remotely —
  delete pending explicit permission
- Phase 2 — wire POST /api/critique to Claude API · XL · High

## References

| Context | Where |
|---|---|
| Feature backlog | `docs/FEATURES.md` |
| Shared JVM design spec | `docs/superpowers/specs/2026-05-25-shared-jvm-test-infra-design.md` |
| Playwright protocols | `docs/protocols/` (4 protocols) |
| Blog entries | `blog/` (3 entries) |
| GitHub repo | `mdproctor/md-compare` |
