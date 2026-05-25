# Shared JVM Test Infrastructure — Design Spec

**Issue:** #6 — JVM cold-start flakiness  
**Branch:** `issue-6-jvm-cold-start-fix`  
**Date:** 2026-05-25

## Problem

Each Playwright spec file launches its own Electron instance, which spawns a
Quarkus JVM. With 7 spec files, the full suite performs 7 JVM cold starts
(plus 1 warmup in global-setup = 8 total). On a resource-constrained machine,
the 3rd+ consecutive cold start can exceed the 180s `beforeAll` timeout.

The current mitigation (global-setup warmup) primes the OS page cache but
doesn't prevent JVM startup overhead. The problem worsens as specs are added.

## Approach

**Shared Quarkus JVM, per-spec Electron window.** Start Quarkus once in
`global-setup.js`, share it across all specs, kill it in `global-teardown.js`.
Each spec still gets its own fresh Electron window for full DOM isolation.

### Why this works

The Quarkus server is fully stateless:

- `FileResource` — pure read via `Files.readString()`, no caching
- `WatchResource` — SSE stream per-connection, connection-scoped `WatchService`,
  no shared state between connections
- `PingResource` — returns `{"ok": true}`
- `UiResource` — serves static files from disk
- `CritiqueResource` — 501 stub

Multiple Electron windows pointing at the same Quarkus port do not interfere.

## Design

### 1. `global-setup.js`

Currently: writes fixture files → launches Electron+Quarkus → waits for render
→ closes everything.

New: writes fixture files → imports `JavaServer` + `findFreePort` from
`java-server.js` → starts Quarkus directly → stores port in
`process.env.TEST_QUARKUS_PORT` and child process PID (via new
`JavaServer.getPid()` accessor) in `process.env.TEST_QUARKUS_PID`.

The Electron warmup launch is removed. `spawnServer()` already calls
`pollUntilReady()` which confirms the JVM is responsive before returning.

### 2. `global-teardown.js` (new file)

Reads `process.env.TEST_QUARKUS_PID`, sends `SIGTERM`. If the process is
already dead, the kill is a no-op.

Registered in `playwright.config.js` via `globalTeardown`.

### 3. `main.js`

New env var check: if `process.env.QUARKUS_PORT` is set, skip `JavaServer`
spawn entirely — use that port directly for `createMainWindow()`.

```
if (process.env.QUARKUS_PORT) {
  port = parseInt(process.env.QUARKUS_PORT);
} else {
  port = await findFreePort();
  await server.spawnServer(port);
}
```

The `before-quit` handler guards against calling `killServer()` when no server
was spawned (external Quarkus mode).

### 4. `helpers.js` — `launchApp()`

Passes `QUARKUS_PORT` through to the Electron process environment when
`TEST_QUARKUS_PORT` is set:

```
const env = { ...process.env };
if (process.env.TEST_QUARKUS_PORT) {
  env.QUARKUS_PORT = process.env.TEST_QUARKUS_PORT;
}
const app = await electron.launch({ executablePath: ELECTRON_BIN, args, env });
```

### 5. Spec files

No changes. Each spec keeps its `beforeAll` → `launchApp()` →
`afterAll` → `app.close()` pattern. The difference is that `launchApp()` now
spawns a lightweight Electron process (no JVM), so `beforeAll` completes in
seconds.

### 6. `playwright.config.js`

Add `globalTeardown: './electron-tests/e2e/global-teardown.js'`.

`workers: 1` stays — specs are sequential. `timeout: 180_000` stays as a
ceiling.

### 7. Protocol updates

`playwright-jvm-warmup` — update to reflect the new mechanism (direct Quarkus
start in global-setup, no Electron warmup launch). Core rule unchanged: JVM
must be running before specs begin.

Other protocols (one-describe-per-spec, kill-stale-processes,
jsErrors-in-afterAll) remain valid as-is.

## JVM launches

| Before | After |
|--------|-------|
| 8 (1 warmup + 7 specs) | 1 (global-setup) |

## Files changed

| File | Change |
|------|--------|
| `electron-tests/e2e/global-setup.js` | Replace Electron warmup with direct Quarkus start |
| `electron-tests/e2e/global-teardown.js` | New — kills Quarkus by PID |
| `electron-tests/e2e/helpers.js` | Pass `QUARKUS_PORT` env to Electron |
| `main.js` | Check `QUARKUS_PORT` env, skip `JavaServer` if set |
| `playwright.config.js` | Add `globalTeardown` |
| `docs/protocols/playwright-jvm-warmup.md` | Update mechanism description |

## Files NOT changed

All 7 spec files — no modifications needed.

## Risk

If Quarkus crashes mid-suite, all remaining specs fail. This is acceptable:
the server is simple and stable, and a crash indicates a real problem worth
investigating rather than masking with per-spec restarts.

## Out of scope

- Native Quarkus build for tests (production path, different issue)
- Parallel spec execution (`workers > 1` — would require Electron window
  isolation verification)
