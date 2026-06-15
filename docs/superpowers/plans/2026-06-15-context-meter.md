# Context Meter + Advisory Reset — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a hybrid context-usage tracking system to DraftHouse debate sessions — server counts its own character contribution, agent reports override, a topbar Web Component gauge shows effective usage, and the `report_context` MCP tool returns advisory warnings when the threshold is exceeded.

**Architecture:** `ContextTracker` (mutable, thread-safe accumulator on `DebateSession`) → `ContextSnapshot` (immutable record) → SSE via `DebateEventResource` metadata events → `<drafthouse-context-gauge>` Web Component in topbar. MCP tools in `DebateMcpTools` are the update sites. `DebateEventBus` gains an `onMeta` subscriber callback for non-entry metadata events.

**Tech Stack:** Java 17 (api module), Quarkus 3.34.3 (runtime module), vanilla JS Web Components (panels)

**Spec:** `docs/superpowers/specs/2026-06-15-context-meter-design.md`

---

### Task 1: ContextTracker and ContextSnapshot — api module

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/ContextTracker.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/ContextSnapshot.java`
- Create: `server/api/src/test/java/io/casehub/drafthouse/ContextTrackerTest.java`

- [ ] **Step 1: Write ContextTracker tests**

```java
package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ContextTrackerTest {

    @Test
    void newTracker_startsAtZero() {
        var tracker = new ContextTracker();
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isZero();
        assertThat(snap.messageCount()).isZero();
        assertThat(snap.agentReportedPercent()).isNull();
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
        assertThat(snap.thresholdExceeded()).isFalse();
    }

    @Test
    void addContribution_incrementsCharsAndMessageCount() {
        var tracker = new ContextTracker();
        tracker.addContribution(1000);
        tracker.addContribution(2000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(3000);
        assertThat(snap.messageCount()).isEqualTo(2);
    }

    @Test
    void addInitialContribution_incrementsCharsButNotMessageCount() {
        var tracker = new ContextTracker();
        tracker.addInitialContribution(50_000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(50_000);
        assertThat(snap.messageCount()).isZero();
    }

    @Test
    void effectivePercent_usesServerContributionWhenNoAgentReport() {
        var tracker = new ContextTracker();
        tracker.addContribution(400_000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.effectivePercent()).isCloseTo(50.0, within(0.01));
        assertThat(snap.agentReportedPercent()).isNull();
    }

    @Test
    void effectivePercent_usesAgentReportWhenPresent() {
        var tracker = new ContextTracker();
        tracker.addContribution(400_000);
        tracker.reportAgentUsage(75.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.effectivePercent()).isEqualTo(75.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(75.0);
    }

    @Test
    void thresholdExceeded_trueWhenEffectivePercentExceedsThreshold() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(85.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.thresholdExceeded()).isTrue();
    }

    @Test
    void thresholdExceeded_falseAtExactThreshold_trueAbove() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(80.0);
        assertThat(tracker.snapshot(800_000, 80.0).thresholdExceeded()).isTrue();

        var tracker2 = new ContextTracker();
        tracker2.reportAgentUsage(79.99);
        assertThat(tracker2.snapshot(800_000, 80.0).thresholdExceeded()).isFalse();
    }

    @Test
    void reportAgentUsage_negativeClampedToZero() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(-5.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(0.0);
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
    }

    @Test
    void reportAgentUsage_over100Accepted() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(120.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(120.0);
        assertThat(snap.effectivePercent()).isEqualTo(120.0);
        assertThat(snap.thresholdExceeded()).isTrue();
    }

    @Test
    void snapshot_withZeroWindowSize_effectivePercentIsZero() {
        var tracker = new ContextTracker();
        tracker.addContribution(1000);
        var snap = tracker.snapshot(0, 80.0);
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
    }

    @Test
    void snapshot_includesWindowSizeChars() {
        var tracker = new ContextTracker();
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.windowSizeChars()).isEqualTo(800_000);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ContextTrackerTest`
Expected: Compilation failure — `ContextTracker` and `ContextSnapshot` not found.

- [ ] **Step 3: Write ContextSnapshot record**

```java
package io.casehub.drafthouse;

public record ContextSnapshot(
        long serverContributionChars,
        long windowSizeChars,
        Double agentReportedPercent,
        int messageCount,
        double effectivePercent,
        boolean thresholdExceeded
) {}
```

- [ ] **Step 4: Write ContextTracker class**

```java
package io.casehub.drafthouse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ContextTracker {

    private final AtomicLong serverContributionChars = new AtomicLong(0);
    private final AtomicInteger messageCount = new AtomicInteger(0);
    private volatile Double agentReportedPercent;

    public void addContribution(long chars) {
        serverContributionChars.addAndGet(chars);
        messageCount.incrementAndGet();
    }

    public void addInitialContribution(long chars) {
        serverContributionChars.addAndGet(chars);
    }

    public void reportAgentUsage(double percent) {
        this.agentReportedPercent = percent < 0 ? 0.0 : percent;
    }

    public ContextSnapshot snapshot(long windowSizeChars, double thresholdPercent) {
        long contribution = serverContributionChars.get();
        Double agentPct = agentReportedPercent;
        double effective = agentPct != null ? agentPct
                : (windowSizeChars > 0 ? (double) contribution / windowSizeChars * 100.0 : 0.0);
        return new ContextSnapshot(
                contribution, windowSizeChars, agentPct,
                messageCount.get(), effective,
                effective >= thresholdPercent
        );
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ContextTrackerTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/ContextTracker.java \
       server/api/src/main/java/io/casehub/drafthouse/ContextSnapshot.java \
       server/api/src/test/java/io/casehub/drafthouse/ContextTrackerTest.java
git commit -m "feat: ContextTracker + ContextSnapshot — thread-safe context accumulator

Refs #52"
```

---

### Task 2: Add ContextTracker to DebateSession

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java`

- [ ] **Step 1: Write the failing test**

Add to `DebateSessionTest.java`:

```java
// ── contextTracker() ──────────────────────────────────────────────────

@Test
void contextTracker_isInitializedOnConstruction() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md");
    assertThat(session.contextTracker()).isNotNull();
    var snap = session.contextTracker().snapshot(800_000, 80.0);
    assertThat(snap.serverContributionChars()).isZero();
    assertThat(snap.messageCount()).isZero();
}

@Test
void contextTracker_accumulatesAcrossMultipleCalls() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md");
    session.contextTracker().addContribution(1000);
    session.contextTracker().addContribution(2000);
    var snap = session.contextTracker().snapshot(800_000, 80.0);
    assertThat(snap.serverContributionChars()).isEqualTo(3000);
    assertThat(snap.messageCount()).isEqualTo(2);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionTest`
Expected: Compilation failure — `contextTracker()` method not found.

- [ ] **Step 3: Add contextTracker field to DebateSession**

In `DebateSession.java`, add a field after the `specPath` field (line 26):

```java
private final ContextTracker contextTracker = new ContextTracker();
```

And add the accessor after `specPath()` (after line 71):

```java
public ContextTracker contextTracker() { return contextTracker; }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/DebateSession.java \
       server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java
git commit -m "feat: add ContextTracker to DebateSession

Refs #52"
```

---

### Task 3: DraftHouseConfig — context section

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java`

- [ ] **Step 1: Add Context interface to DraftHouseConfig**

After the `Storage` interface in `DraftHouseConfig.java` (line 31), add:

```java
Context context();

interface Context {

    @WithDefault("800000")
    long windowSizeChars();

    @WithDefault("80")
    double thresholdPercent();
}
```

- [ ] **Step 2: Build to verify config mapping compiles**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java
git commit -m "feat: DraftHouseConfig context section — window size + threshold

Refs #52"
```

---

### Task 4: DebateEventResource — context snapshot SSE delivery

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`

- [ ] **Step 1: Read the existing DebateEventResourceTest**

Read `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java` to understand the existing test patterns.

- [ ] **Step 2: Write the failing test for context snapshot push delivery**

Add a test verifying that `pushContextSnapshot()` makes the snapshot available on the next SSE tick. The exact test depends on the existing test patterns discovered in step 1 — the test should verify that:
1. `pushContextSnapshot(channelId, snapshot)` stores the snapshot
2. The next SSE poll tick includes the context-usage JSON alongside any debate entries
3. The snapshot is sent as a non-array JSON object with `"type": "context-usage"`

- [ ] **Step 3: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`
Expected: Compilation failure — `pushContextSnapshot` not found.

- [ ] **Step 4: Implement context snapshot delivery in DebateEventResource**

Add to `DebateEventResource.java`:

1. A `ConcurrentHashMap<UUID, ContextSnapshot>` field for pending snapshots
2. A `pushContextSnapshot(UUID channelId, ContextSnapshot snapshot)` method
3. In the `events()` method — inject the initial context snapshot from `DebateSession.contextTracker()` before the catch-up replay (requires `DraftHouseConfig` injection for window size and threshold)
4. In the poll loop — after serializing debate entries, check for a pending snapshot and append it as a separate SSE event

The context snapshot is serialized as a JSON object with `"type": "context-usage"`. The initial snapshot includes `windowSizeChars`; incremental updates omit it (the field is present in `ContextSnapshot` but can be set to 0 for incrementals — the UI caches the initial value).

For SSE delivery: the existing `events()` method returns `Multi<String>`. Each tick currently returns either a heartbeat or a serialized `DebateStreamEntry[]`. To interleave context events, the tick handler should return the context snapshot JSON (if pending) followed by the debate entries, separated by newlines — or emit them as separate SSE events. The simplest correct approach: if a pending snapshot exists, emit it as its own SSE data frame before the debate entries.

Implementation detail: `Multi.createBy().concatenating()` the snapshot (if pending) with the entry batch on each tick. Use `Multi.createFrom().items()` to emit zero or one snapshot strings before the entry string.

- [ ] **Step 5: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java \
       server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java
git commit -m "feat: SSE delivery for context-usage snapshots

DebateEventResource injects initial context snapshot on connect
and delivers pushed snapshots as metadata events on the SSE stream.

Refs #52"
```

---

### Task 5: DebateMcpTools — addContribution at every dispatch site + report_context tool

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Read the existing DebateMcpToolsTest**

Read `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java` to understand mock setup, session creation patterns, and assertion styles.

- [ ] **Step 2: Write failing tests for context tracking at dispatch sites**

Add tests verifying:
1. `startDebate()` counts the spec file content as initial contribution
2. `raisePoint()` increments contribution and message count
3. `respondTo()` increments contribution and message count
4. `postMemo()` increments contribution and message count
5. `flagHuman()` increments contribution and message count
6. `requestSubagent()` increments contribution and message count
7. `restartFromRound()` counts the RESTART_CONTEXT summary on the new session's tracker

- [ ] **Step 3: Write failing tests for report_context tool**

Add tests verifying:
1. `reportContext()` with valid session and percentage returns `{"status": "ok", ...}`
2. `reportContext()` with threshold exceeded returns `{"status": "warning", ...}` with message
3. `reportContext()` with negative percentage clamps to 0
4. `reportContext()` with >100 percentage is accepted
5. `reportContext()` with invalid session ID returns error
6. `reportContext()` pushes a context snapshot to `DebateEventResource`

- [ ] **Step 4: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest`
Expected: Compilation failures.

- [ ] **Step 5: Inject DraftHouseConfig and DebateEventResource into DebateMcpTools**

Add to `DebateMcpTools.java` fields section:

```java
@Inject DraftHouseConfig config;
@Inject DebateEventResource debateEventResource;
```

Note: `DraftHouseConfig` is already injected in `DraftHouseMcpTools` but not in `DebateMcpTools`. Add the injection.

- [ ] **Step 6: Add context tracking helper method**

Add a private helper in `DebateMcpTools`:

```java
private void trackAndPush(DebateSession session, long contentChars) {
    session.contextTracker().addContribution(contentChars);
    debateEventResource.pushContextSnapshot(session.channelId(),
            session.contextTracker().snapshot(
                    config.context().windowSizeChars(),
                    config.context().thresholdPercent()));
}
```

- [ ] **Step 7: Add tracking calls to each dispatch site**

After each `messageService.dispatch()` call in the following methods, add `trackAndPush(session, encodedContent.length())`:

- `raisePoint()` — after the dispatch, before the return
- `respondTo()` — after the dispatch, before the return
- `flagHuman()` — after the dispatch, before the return
- `postMemo()` — after the dispatch inside the try block, before the return
- `requestSubagent()` — after the dispatch inside the try block, before the return

For `startDebate()` — after registry.put(session) and before the return, add:

```java
String specContent = readFile(specPath);
if (specContent != null) {
    session.contextTracker().addInitialContribution(specContent.length());
}
```

Note: `startDebate()` currently does not read the spec file (it only stores the path). The spec content should be measured but a full file read just for tracking is wasteful. Instead, use `java.nio.file.Files.size(Path.of(specPath))` for a byte count (close enough to char count for UTF-8 text). Add a try-catch — if the file can't be sized, log and skip. No push needed at start — the first MCP tool call from the agent will push.

For `restartFromRound()` — after the `messageService.dispatch()` of the RESTART_CONTEXT marker, add:

```java
newSession.contextTracker().addInitialContribution(markerContent.length());
debateEventResource.pushContextSnapshot(newSession.channelId(),
        newSession.contextTracker().snapshot(
                config.context().windowSizeChars(),
                config.context().thresholdPercent()));
```

- [ ] **Step 8: Implement report_context tool**

Add to `DebateMcpTools.java`:

```java
@Tool(name = "report_context",
      description = "Report current context window usage for a debate session. "
                  + "Call periodically (e.g. every 2-3 rounds) to improve the accuracy "
                  + "of the context meter. Returns advisory warning when threshold exceeded.")
public String reportContext(
        @ToolArg(description = "Debate session ID") String debateSessionId,
        @ToolArg(description = "Context usage as percentage (0-100)") double usagePercent) {
    try {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        session.contextTracker().reportAgentUsage(usagePercent);
        ContextSnapshot snap = session.contextTracker().snapshot(
                config.context().windowSizeChars(),
                config.context().thresholdPercent());
        debateEventResource.pushContextSnapshot(session.channelId(), snap);

        if (snap.thresholdExceeded()) {
            return "{\"status\":\"warning\",\"effectivePercent\":" + snap.effectivePercent()
                    + ",\"message\":\"Context usage at " + String.format("%.1f", snap.effectivePercent())
                    + "% — consider committing state and restarting session\"}";
        }
        return "{\"status\":\"ok\",\"effectivePercent\":" + snap.effectivePercent() + "}";
    } catch (Exception e) {
        LOG.warning("report_context failed: " + e.getMessage());
        return "error: " + e.getMessage();
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest`
Expected: All tests PASS.

- [ ] **Step 10: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All existing tests still pass — no regressions.

- [ ] **Step 11: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java \
       server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "feat: context tracking at all dispatch sites + report_context MCP tool

Every debate MCP tool now tracks its character contribution via
ContextTracker and pushes snapshots to the SSE stream. report_context
lets agents override the server-side floor with actual usage.

Refs #52"
```

---

### Task 6: DebateEventBus — onMeta subscriber callback

**Files:**
- Modify: `panels/debate-event-bus.js`

- [ ] **Step 1: Extend the subscriber contract**

In `debate-event-bus.js`, modify the `subscribe` method to accept `onMeta`:

```javascript
subscribe({ onEntries, onReconnect, onMeta }) {
    if (typeof onEntries !== 'function') {
      throw new Error('subscribe() requires onEntries callback');
    }
    const sub = { onEntries, onReconnect: onReconnect || null, onMeta: onMeta || null };
    this.#subscribers.add(sub);
    return () => { this.#subscribers.delete(sub); };
}
```

- [ ] **Step 2: Update onmessage routing**

In the `connect()` method, replace the `this.#eventSource.onmessage` handler:

```javascript
this.#eventSource.onmessage = (e) => {
  let data;
  try { data = JSON.parse(e.data); } catch { return; }
  if (data.type === 'heartbeat') return;

  if (Array.isArray(data)) {
    for (const sub of this.#subscribers) {
      try { sub.onEntries(data); } catch (err) {
        console.error('DebateEventBus subscriber error:', err);
      }
    }
  } else if (data.type) {
    for (const sub of this.#subscribers) {
      try { if (sub.onMeta) sub.onMeta(data); } catch (err) {
        console.error('DebateEventBus onMeta error:', err);
      }
    }
  }
};
```

- [ ] **Step 3: Verify existing panels still work**

Build and run: `java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar`

Open browser, confirm the debate panel and review tracker still render correctly with a live session. The `onMeta` callback is optional — existing subscribers don't register it, so they're unaffected.

- [ ] **Step 4: Commit**

```
git add panels/debate-event-bus.js
git commit -m "feat: DebateEventBus onMeta callback for metadata events

Non-array, non-heartbeat objects with a type field now route to
onMeta subscribers. Existing onEntries-only subscribers are unaffected.

Refs #52"
```

---

### Task 7: `<drafthouse-context-gauge>` Web Component

**Files:**
- Create: `panels/drafthouse-context-gauge.js`
- Modify: `index.html`
- Modify: `styles.css`

- [ ] **Step 1: Create the Web Component**

Create `panels/drafthouse-context-gauge.js`:

```javascript
import { registry } from './panel-registry.js';
import { debateEventBus } from './debate-event-bus.js';

const styles = new CSSStyleSheet();
styles.replaceSync(`
  :host {
    display: none;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    color: var(--sepia);
  }

  :host(.visible) {
    display: flex;
  }

  .gauge-label {
    white-space: nowrap;
    font-weight: 600;
  }

  .gauge-bar {
    width: 80px;
    height: 8px;
    background: var(--border-light);
    border-radius: 2px;
    overflow: hidden;
  }

  .gauge-fill {
    height: 100%;
    border-radius: 2px;
    transition: width 0.3s ease, background-color 0.3s ease;
  }

  .fill-normal { background: var(--accent); }
  .fill-warn { background: var(--warn); }
  .fill-error { background: var(--error); }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.6; }
  }

  .threshold-exceeded .gauge-label {
    animation: pulse 2s ease-in-out infinite;
    color: var(--error);
  }
`);

class DraftHouseContextGauge extends HTMLElement {
  #shadow = null;
  #unsubscribe = null;
  #windowSizeChars = null;
  #label = null;
  #fill = null;
  #wrapper = null;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(_props) {}

  connectedCallback() {
    this.#render();
    this.#unsubscribe = debateEventBus.subscribe({
      onEntries: () => {},
      onReconnect: () => this.#reset(),
      onMeta: (data) => this.#handleMeta(data)
    });
  }

  disconnectedCallback() {
    if (this.#unsubscribe) {
      this.#unsubscribe();
      this.#unsubscribe = null;
    }
  }

  #render() {
    const wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:contents';

    const label = document.createElement('span');
    label.className = 'gauge-label';
    label.textContent = 'Ctx: —';
    wrapper.appendChild(label);

    const bar = document.createElement('div');
    bar.className = 'gauge-bar';
    const fill = document.createElement('div');
    fill.className = 'gauge-fill fill-normal';
    fill.style.width = '0%';
    bar.appendChild(fill);
    wrapper.appendChild(bar);

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(wrapper);
    this.#label = label;
    this.#fill = fill;
    this.#wrapper = wrapper;
  }

  #handleMeta(data) {
    if (data.type !== 'context-usage') return;

    if (data.windowSizeChars != null) {
      this.#windowSizeChars = data.windowSizeChars;
    }

    this.classList.add('visible');

    const pct = data.effectivePercent;
    const clamped = Math.min(pct, 100);

    this.#label.textContent = `Ctx: ${Math.round(pct)}%`;
    this.#fill.style.width = clamped + '%';

    this.#fill.className = 'gauge-fill ' + (
      pct >= 80 ? 'fill-error' :
      pct >= 60 ? 'fill-warn' : 'fill-normal'
    );

    if (data.thresholdExceeded) {
      this.#wrapper.classList.add('threshold-exceeded');
    } else {
      this.#wrapper.classList.remove('threshold-exceeded');
    }

    const contribK = Math.round((data.serverContributionChars || 0) / 1000);
    const windowK = this.#windowSizeChars ? Math.round(this.#windowSizeChars / 1000) : '?';
    const agentStr = data.agentReportedPercent != null
      ? Math.round(data.agentReportedPercent) + '%'
      : '—';
    this.title = `Server contribution: ${contribK}k / ${windowK}k chars (${data.messageCount || 0} messages). Agent-reported: ${agentStr}`;
  }

  #reset() {
    this.classList.remove('visible');
    this.#windowSizeChars = null;
    if (this.#label) this.#label.textContent = 'Ctx: —';
    if (this.#fill) {
      this.#fill.style.width = '0%';
      this.#fill.className = 'gauge-fill fill-normal';
    }
    if (this.#wrapper) this.#wrapper.classList.remove('threshold-exceeded');
  }
}

registry.register({
  type: 'drafthouse-context-gauge',
  component: DraftHouseContextGauge,
  label: 'Context Gauge',
  icon: '📊',
  propsSchema: {}
});
```

- [ ] **Step 2: Add gauge to index.html**

In `index.html`, add the script import after the review tracker import (line 29):

```html
<script type="module" src="panels/drafthouse-context-gauge.js"></script>
```

In the shell module script, after the review panel creation (after line 86), add:

```javascript
const contextGauge = registry.create('drafthouse-context-gauge', {});
```

Insert the gauge into the topbar. After `$('diff-legend')` (which is the `<span id="diff-legend">` element), insert the gauge before `$('topbar-spacer')`:

```javascript
$('topbar').insertBefore(contextGauge, $('topbar-spacer'));
```

- [ ] **Step 3: Build, run, and verify**

Build: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests`
Run: `java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar`

Open browser. Verify:
1. Gauge is not visible initially (no debate session)
2. Start a debate session via MCP tool — gauge should appear after the first message dispatch
3. Gauge shows correct percentage and updates after each tool call
4. Hover shows tooltip with server contribution breakdown

- [ ] **Step 4: Commit**

```
git add panels/drafthouse-context-gauge.js index.html
git commit -m "feat: <drafthouse-context-gauge> Web Component — topbar context meter

Shadow DOM, adoptedStyleSheets, PanelRegistry contract. Subscribes
to DebateEventBus onMeta for context-usage events. Shows percentage
bar with colour-coded threshold bands and pulse animation at 80%+.

Refs #52"
```

---

### Task 8: Full integration test + final verification

**Files:**
- No new files — verification task

- [ ] **Step 1: Run the full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass with no regressions.

- [ ] **Step 2: Manual integration test**

Build and run:
```
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar
```

Open browser at `http://localhost:9001/?a=sample-a.md&b=sample-b.md`. Exercise the full flow:
1. Start a debate session via MCP
2. Verify gauge appears after first dispatch
3. Post several messages, verify gauge increments
4. Call `report_context` with a percentage, verify gauge updates to agent-reported value
5. Call `report_context` with >80%, verify warning response and red gauge
6. End debate, verify gauge hides

- [ ] **Step 3: Commit any final adjustments**

If any test or integration issues were found, fix and commit.
