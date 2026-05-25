# Shared JVM Test Infrastructure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate JVM cold-start flakiness by starting Quarkus once in global-setup and sharing it across all Playwright spec files.

**Architecture:** `global-setup.js` starts a single Quarkus JVM via `JavaServer` and passes the port/PID through env vars. `main.js` checks for a `QUARKUS_PORT` env var and skips its own `JavaServer` spawn when present. `launchApp()` in `helpers.js` forwards the port to Electron's environment. `global-teardown.js` kills the Quarkus process by PID. Spec files are unchanged.

**Tech Stack:** Node.js, Playwright, Electron, Quarkus (Java)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `java-server.js` | Modify | Add `getPid()` accessor |
| `main.js` | Modify | Skip `JavaServer` when `QUARKUS_PORT` env set |
| `electron-tests/e2e/helpers.js` | Modify | Forward `QUARKUS_PORT` to Electron env |
| `electron-tests/e2e/global-setup.js` | Modify | Start Quarkus directly, store port+PID in env |
| `electron-tests/e2e/global-teardown.js` | Create | Kill Quarkus by PID |
| `playwright.config.js` | Modify | Add `globalTeardown` |
| `docs/protocols/playwright-jvm-warmup.md` | Modify | Update mechanism description |

---

### Task 1: Add `getPid()` to `JavaServer`

**Files:**
- Modify: `java-server.js:67` (after `getLogs()`)

- [ ] **Step 1: Add the accessor**

In `java-server.js`, add `getPid()` after the existing `getLogs()` method:

```javascript
  getPid()   { return this._process ? this._process.pid : null; }
```

The line to add it after is:

```javascript
  getLogs()  { return [...this._logs]; }
```

So the result is:

```javascript
  getPort()  { return this._port; }
  getLogs()  { return [...this._logs]; }
  getPid()   { return this._process ? this._process.pid : null; }
```

- [ ] **Step 2: Commit**

```bash
git add java-server.js
git commit -m "feat(java-server): add getPid() accessor

Refs #6"
```

---

### Task 2: Make `main.js` skip JVM spawn when `QUARKUS_PORT` is set

**Files:**
- Modify: `main.js:51-59`

- [ ] **Step 1: Write the failing test**

There are no unit tests for `main.js` — it's an Electron entry point. The integration test comes in Task 6 when the full suite runs. For now, verify the change manually by reading the code path.

- [ ] **Step 2: Modify the `app.whenReady()` handler**

Replace the current startup block in `main.js`:

```javascript
app.whenReady().then(async () => {
  server.on('fatal', () => showErrorWindow('The md-compare server crashed and could not restart.'));
  try {
    const port = await findFreePort();
    await server.spawnServer(port);
    await createMainWindow(port);
  } catch (err) {
    showErrorWindow(err.message);
  }
});
```

With:

```javascript
app.whenReady().then(async () => {
  try {
    let port;
    if (process.env.QUARKUS_PORT) {
      port = parseInt(process.env.QUARKUS_PORT, 10);
    } else {
      server.on('fatal', () => showErrorWindow('The md-compare server crashed and could not restart.'));
      port = await findFreePort();
      await server.spawnServer(port);
    }
    await createMainWindow(port);
  } catch (err) {
    showErrorWindow(err.message);
  }
});
```

Note: the `fatal` listener is moved inside the `else` branch — when using an external Quarkus, the local `server` instance is never started so its crash events are irrelevant.

- [ ] **Step 3: Guard the `before-quit` handler**

Replace the current `before-quit` handler:

```javascript
app.on('before-quit', async (event) => {
  event.preventDefault();
  await server.killServer();
  app.exit(0);
});
```

With:

```javascript
app.on('before-quit', async (event) => {
  event.preventDefault();
  if (!process.env.QUARKUS_PORT) await server.killServer();
  app.exit(0);
});
```

- [ ] **Step 4: Commit**

```bash
git add main.js
git commit -m "feat(main): skip JavaServer spawn when QUARKUS_PORT env is set

Refs #6"
```

---

### Task 3: Forward `QUARKUS_PORT` in `launchApp()`

**Files:**
- Modify: `electron-tests/e2e/helpers.js:20-21`

- [ ] **Step 1: Modify `launchApp` to pass the env var**

In `helpers.js`, replace the `electron.launch` call:

```javascript
  const app    = await electron.launch({ executablePath: ELECTRON_BIN, args });
```

With:

```javascript
  const env = { ...process.env };
  if (process.env.TEST_QUARKUS_PORT) {
    env.QUARKUS_PORT = process.env.TEST_QUARKUS_PORT;
  }
  const app    = await electron.launch({ executablePath: ELECTRON_BIN, args, env });
```

- [ ] **Step 2: Commit**

```bash
git add electron-tests/e2e/helpers.js
git commit -m "feat(helpers): forward TEST_QUARKUS_PORT to Electron env

Refs #6"
```

---

### Task 4: Rewrite `global-setup.js` to start Quarkus directly

**Files:**
- Modify: `electron-tests/e2e/global-setup.js`

- [ ] **Step 1: Rewrite global-setup**

Replace the entire contents of `global-setup.js` with:

```javascript
// electron-tests/e2e/global-setup.js
'use strict';
const fs   = require('fs');
const path = require('path');
const os   = require('os');
const { JavaServer, findFreePort } = require('../../java-server');

module.exports = async function globalSetup() {
  const fileA = path.join(os.tmpdir(), 'mdcompare-test-a.md');
  const fileB = path.join(os.tmpdir(), 'mdcompare-test-b.md');

  fs.writeFileSync(fileA, [
    '# Rule Engines',
    '',
    'A rule engine evaluates business rules against a set of facts.',
    '',
    '## How It Works',
    '',
    'Rules have a condition and an action.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## Limitations',
    '',
    'Rule engines add operational complexity and require careful tuning.',
  ].join('\n'));

  fs.writeFileSync(fileB, [
    '# Rule Engines',
    '',
    'A rule engine runs your business rules so developers do not have to hard-code them.',
    '',
    '## How It Works',
    '',
    'Each rule has a condition and an action. When the condition matches, the action fires.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## When Not to Use One',
    '',
    'Do not reach for a rule engine to replace five if/else statements.',
  ].join('\n'));

  process.env.TEST_FILE_A = fileA;
  process.env.TEST_FILE_B = fileB;

  console.log('[global-setup] starting shared Quarkus JVM...');
  const server = new JavaServer();
  const port = await findFreePort();
  await server.spawnServer(port);
  process.env.TEST_QUARKUS_PORT = String(port);
  process.env.TEST_QUARKUS_PID  = String(server.getPid());
  console.log(`[global-setup] Quarkus ready on port ${port} (pid ${server.getPid()})`);
};
```

Key changes from the original:
- Removed `playwright` and `electron` imports
- Removed `ELECTRON_BIN` and `APP_PATH` imports from helpers
- Added `JavaServer` and `findFreePort` imports from `java-server.js`
- Replaced the Electron warmup launch with direct `JavaServer.spawnServer()`
- Stored port and PID in env vars for specs and teardown

- [ ] **Step 2: Commit**

```bash
git add electron-tests/e2e/global-setup.js
git commit -m "feat(global-setup): start Quarkus directly, remove Electron warmup

Refs #6"
```

---

### Task 5: Create `global-teardown.js`

**Files:**
- Create: `electron-tests/e2e/global-teardown.js`

- [ ] **Step 1: Write the teardown file**

Create `electron-tests/e2e/global-teardown.js`:

```javascript
// electron-tests/e2e/global-teardown.js
'use strict';

module.exports = async function globalTeardown() {
  const pid = parseInt(process.env.TEST_QUARKUS_PID, 10);
  if (!pid || isNaN(pid)) return;
  try {
    process.kill(pid, 'SIGTERM');
  } catch (_) {
    // Process already exited — nothing to clean up
  }
};
```

- [ ] **Step 2: Register teardown in playwright.config.js**

In `playwright.config.js`, add the `globalTeardown` line after `globalSetup`:

```javascript
module.exports = defineConfig({
  testDir:         './electron-tests/e2e',
  globalSetup:     './electron-tests/e2e/global-setup.js',
  globalTeardown:  './electron-tests/e2e/global-teardown.js',
  timeout:         180_000,
  retries:         0,
  workers:         1,
  use: { headless: false },
});
```

- [ ] **Step 3: Commit**

```bash
git add electron-tests/e2e/global-teardown.js playwright.config.js
git commit -m "feat: add global-teardown to kill shared Quarkus on suite exit

Refs #6"
```

---

### Task 6: Run the full test suite

**Files:** None — verification only.

- [ ] **Step 1: Kill stale processes**

```bash
pkill -9 -f "Electron|mdcompare|quarkus" 2>/dev/null; echo "clean"
```

- [ ] **Step 2: Run the full Playwright suite**

```bash
./node_modules/.bin/playwright test --reporter=list
```

Expected: all 54 tests pass, 2 scroll-sync tests skip (viewport too small for fixture content). The `[global-setup]` output should show `starting shared Quarkus JVM...` followed by `Quarkus ready on port NNNNN (pid NNNNN)` — one JVM start, not seven.

- [ ] **Step 3: Verify JVM count**

During the run (or from the output), confirm there is only one `java` process spawned. The console output should show a single `[global-setup]` JVM start message with no per-spec JVM startup messages.

- [ ] **Step 4: Run twice to confirm no flakiness**

```bash
pkill -9 -f "Electron|mdcompare|quarkus" 2>/dev/null
./node_modules/.bin/playwright test --reporter=list
```

Both runs should pass on the first attempt — the cold-start problem is eliminated.

---

### Task 7: Update the `playwright-jvm-warmup` protocol

**Files:**
- Modify: `docs/protocols/playwright-jvm-warmup.md`

- [ ] **Step 1: Rewrite the protocol**

Replace the entire contents of `docs/protocols/playwright-jvm-warmup.md` with:

```markdown
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
```

- [ ] **Step 2: Update protocol index**

In `docs/protocols/INDEX.md`, update the `playwright-jvm-warmup` row:

Replace:

```markdown
| [playwright-jvm-warmup.md](playwright-jvm-warmup.md) | global-setup.js must warm the Quarkus JVM before tests | electron-tests/e2e/global-setup.js |
```

With:

```markdown
| [playwright-jvm-warmup.md](playwright-jvm-warmup.md) | global-setup.js starts a shared Quarkus JVM; specs reuse it via QUARKUS_PORT | global-setup.js, global-teardown.js, main.js, helpers.js |
```

- [ ] **Step 3: Commit**

```bash
git add docs/protocols/playwright-jvm-warmup.md docs/protocols/INDEX.md
git commit -m "docs(protocol): update JVM warmup protocol to shared JVM mechanism

Refs #6"
```

---

### Task 8: Final verification and suite timing

**Files:** None — verification only.

- [ ] **Step 1: Clean run from cold state**

```bash
pkill -9 -f "Electron|mdcompare|quarkus" 2>/dev/null
./node_modules/.bin/playwright test --reporter=list
```

- [ ] **Step 2: Record timing**

Note the total suite time. With the shared JVM, the suite should complete in under 30 seconds (down from 2+ minutes on warm runs, or timeout failures on cold runs).

- [ ] **Step 3: Verify no spec file was modified**

```bash
git diff --name-only main -- electron-tests/e2e/*.spec.js
```

Expected: empty output — no spec files were changed.
