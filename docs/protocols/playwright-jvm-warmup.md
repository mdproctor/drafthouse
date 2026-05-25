---
id: PP-20260522-ef1eab
title: "global-setup.js starts a shared Quarkus JVM; specs reuse it via QUARKUS_PORT env var"
type: rule
scope: repo
applies_to: "electron-tests/e2e/global-setup.js, electron-tests/e2e/global-teardown.js, main.js"
severity: important
refs:
  - electron-tests/e2e/global-setup.js
  - electron-tests/e2e/global-teardown.js
  - electron-tests/e2e/helpers.js
  - main.js
  - CLAUDE.md#testing
violation_hint: "global-setup.js does not start JavaServer before tests, or main.js does not check QUARKUS_PORT — each spec spawns its own JVM and cold-start timeouts return"
created: 2026-05-22
updated: 2026-05-25
---

All Playwright specs share a single Quarkus JVM started in `global-setup.js`. The JVM is killed in `global-teardown.js` by PID. Each spec still gets its own Electron window for DOM isolation, but no spec spawns a JVM.

The mechanism: `global-setup.js` imports `JavaServer` from `java-server.js`, calls `findFreePort()` + `spawnServer(port)`, and stores the port in `process.env.TEST_QUARKUS_PORT` and the PID in `process.env.TEST_QUARKUS_PID`. `launchApp()` in `helpers.js` forwards `TEST_QUARKUS_PORT` as `QUARKUS_PORT` in the Electron process environment. `main.js` checks for `QUARKUS_PORT` and skips its internal `JavaServer` spawn when present.

Do not revert to per-spec JVM spawning. The Quarkus server is fully stateless (`FileResource` is a pure read, `WatchResource` is connection-scoped, `PingResource` returns a constant, `UiResource` serves static files). Multiple Electron windows sharing one Quarkus port do not interfere.

Previously, `global-setup.js` launched the full Electron+Quarkus stack once as a warmup to prime the OS page cache. That approach mitigated but did not eliminate cold-start timeouts — each spec still started its own JVM. The current design eliminates cold starts entirely: 1 JVM launch (global-setup) instead of 8 (1 warmup + 7 specs).
