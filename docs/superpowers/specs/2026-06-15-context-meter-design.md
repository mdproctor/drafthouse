# Context Meter + Advisory Reset — Design Spec

**Issue:** #52
**Date:** 2026-06-15
**Status:** Draft (rev 3 — post-review)

## Problem

LLM agents running multi-round debate sessions accumulate context — documents, channel messages, conversation history. Context windows are finite. When context degrades, review quality degrades silently. There is no visibility into context consumption and no mechanism to advise the agent to reset.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Context awareness model | Hybrid — server tracks its own contribution, agent reports override | Server can only measure what DraftHouse contributes (documents + messages); agent knows its actual context state. Neither alone is sufficient. |
| Reset model | Advisory — DraftHouse signals, client acts | DraftHouse doesn't own agent sessions. Automatic reset requires self-bootstrapping manifest (#26 idea 9), not yet built. |
| UI element | `<drafthouse-context-gauge>` Web Component in topbar | Context usage is session-level status. Web Component follows the panel architecture pattern and survives shell replacement. |
| Measurement unit | Characters (not tokens) | Avoids tokenizer dependency. ~4x multiplier for English text is a consistent approximation. |
| Threshold mode | Advisory only | Meter turns red/amber, MCP tool response includes warning. No forced reset. |

## Data Model

### ContextTracker (api module)

A mutable, thread-safe accumulator — not a record. Concurrent dispatches from multiple agent roles (REV, IMP, sub-agents) require lock-free updates.

```java
public class ContextTracker {
    private final AtomicLong serverContributionChars = new AtomicLong(0);
    private final AtomicInteger messageCount = new AtomicInteger(0);
    private volatile Double agentReportedPercent;  // null until agent reports

    public void addContribution(long chars) {
        serverContributionChars.addAndGet(chars);
        messageCount.incrementAndGet();
    }

    public void addInitialContribution(long chars) {
        serverContributionChars.addAndGet(chars);
        // no message count increment — documents are not messages
    }

    public void reportAgentUsage(double percent) {
        this.agentReportedPercent = percent;
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

### ContextSnapshot (api module)

Immutable snapshot for SSE serialization.

```java
public record ContextSnapshot(
    long serverContributionChars,
    long windowSizeChars,
    Double agentReportedPercent,
    int messageCount,
    double effectivePercent,
    boolean thresholdExceeded
) {}
```

**Naming:** `serverContributionChars` — not "estimate." The server can only measure what DraftHouse contributed (spec file content + channel message text). It cannot observe the agent's system prompt, chain-of-thought reasoning, prior tool results, or conversation history. Until the agent calls `report_context`, the meter shows a floor, not an approximation of total context usage. The UI tooltip makes this explicit.

## Server-Side Tracking

`DebateSession` gains a `ContextTracker` field, initialised at `start_debate`.

**Update sites (all in DebateMcpTools):**

| Tool method | What's counted |
|-------------|---------------|
| `startDebate()` | Spec file content length via `addInitialContribution()` |
| `raisePoint()` | Message content length via `addContribution()` |
| `respondTo()` | Message content length via `addContribution()` |
| `flagHuman()` | Message content length via `addContribution()` |
| `postMemo()` | Message content length via `addContribution()` |
| `requestSubagent()` | Request content length via `addContribution()` |
| `restartFromRound()` | RESTART_CONTEXT summary content length via `addInitialContribution()` on the **new** session's tracker. The spec file is not re-read by this method — the summary stands in for it. If the agent later re-reads the spec via a file read tool, that's a separate contribution tracked at that call site. |
| `reportContext()` | Agent percentage via `reportAgentUsage()` |

**Why DebateMcpTools, not DebateChannelBackend:** `DebateChannelBackend.post()` early-returns for all message types except `SUB_TASK_REQUEST` (line 57). Regular debate messages (RAISE, AGREE, COUNTER, etc.) are invisible to it. Every debate message originates from an MCP tool method where the session is already resolved and the content length is known — tracking there is direct, explicit, and correct.

**Why not MessageObserver:** A Qhorus `MessageObserver` would decouple tracking from MCP tools but adds unnecessary indirection — channelId → DebateSession resolution inside the observer, for what is a DraftHouse-internal concern. The tracking is simple accumulation at known call sites, not a cross-cutting concern that benefits from SPI abstraction.

**After each update**, the tool method emits a context-usage SSE event (see SSE Delivery below). Each tool already has the `DebateSession` resolved and can call `session.contextTracker().snapshot(config.context().windowSizeChars(), config.context().thresholdPercent())` to produce the snapshot, then push it to `DebateEventResource`.

## SSE Delivery

Context usage events flow through the existing debate SSE stream but are **distinct from debate entries** — they are operational metadata with a different shape.

### Stream architecture

The current SSE stream sends:
- **Debate entries**: `DebateStreamEntry[]` arrays (from `messageService.pollAfter()`)
- **Heartbeats**: `{"type":"heartbeat"}` (non-array object)

Context usage is a third category: a non-array JSON object with `"type": "context-usage"`.

### Event format — initial snapshot (on connect)

Includes `windowSizeChars` (config value, does not change mid-session):

```json
{
  "type": "context-usage",
  "serverContributionChars": 142000,
  "windowSizeChars": 800000,
  "agentReportedPercent": null,
  "effectivePercent": 17.75,
  "messageCount": 23,
  "thresholdExceeded": false
}
```

### Event format — incremental updates

Omits `windowSizeChars` (already known from initial snapshot):

```json
{
  "type": "context-usage",
  "serverContributionChars": 148500,
  "agentReportedPercent": null,
  "effectivePercent": 18.56,
  "messageCount": 24,
  "thresholdExceeded": false
}
```

### Emission points

1. **SSE connect** — `DebateEventResource` injects the current `ContextSnapshot` from `DebateSession.contextTracker()` as a separate JSON object, emitted before the debate entry replay. This is in-memory session state, not a persisted Qhorus message.
2. **After each MCP tool dispatch** — the tool method pushes the snapshot to `DebateEventResource` (mechanism: a CDI event or direct method call on the resource — implementation detail).

### Browser-side: DebateEventBus subscriber contract extension

The current subscriber contract:
```javascript
subscribe({ onEntries, onReconnect })
```

Extended with `onMeta`:
```javascript
subscribe({ onEntries, onReconnect, onMeta })
```

`DebateEventBus.onmessage` routing:
- `data.type === 'heartbeat'` → ignore (existing behaviour)
- `Array.isArray(data)` → `onEntries(data)` (existing behaviour)
- Non-array object with `data.type` → `onMeta(data)` (new)

`onMeta` is optional. Debate panel and review tracker don't register it — they're unaffected. The context gauge registers `onMeta` to receive context-usage events.

## MCP Tool

### `report_context`

```java
@Tool(name = "report_context",
      description = "Report current context window usage for a debate session. "
                  + "Call periodically (e.g. every 2-3 rounds) to improve the accuracy "
                  + "of the context meter. Returns advisory warning when threshold exceeded.")
public String reportContext(
    @ToolArg(description = "Debate session ID") String debateSessionId,
    @ToolArg(description = "Context usage as percentage (0-100)") double usagePercent)
```

**Input validation:** Negative values are clamped to 0. Values over 100 are accepted — an agent reporting >100% is signalling that it has exceeded its window and is compressing, which is information worth preserving. The gauge displays the reported value as-is.

**Response:**

- Normal: `{"status": "ok", "effectivePercent": 42.0}`
- Threshold exceeded: `{"status": "warning", "effectivePercent": 87.3, "message": "Context usage at 87.3% — consider committing state and restarting session"}`

**Response format note:** Other debate MCP tools (`raise_point`, `respond_to`, etc.) return `{"status": "dispatched"}` because they trigger async message delivery. `report_context` returns `"ok"` because it's a synchronous state write with a computed response — different semantics warrant different response shapes.

## Context Gauge — Web Component

### `<drafthouse-context-gauge>`

A lightweight Web Component registered via `PanelRegistry`, placed in the topbar by the shell. Shadow DOM + `adoptedStyleSheets`, `configure(props)` contract — follows the same pattern as `<drafthouse-diff>`, `<drafthouse-debate>`, and `<drafthouse-review-tracker>`.

**Placement:** In the topbar, between `diff-legend` and `topbar-spacer`. The shell creates it via `registry.create('drafthouse-context-gauge', {})` and appends it. Hidden until the first `context-usage` event arrives.

**Visual design:**

| Range | Fill colour | Meaning |
|-------|------------|---------|
| 0–59% | `var(--accent)` | Normal |
| 60–79% | `var(--warn)` | Approaching threshold |
| 80%+ | `var(--error)` | Threshold exceeded |

Format: `Ctx: 42%` label next to an ~80px horizontal bar. Threshold-exceeded state adds a gentle CSS pulse animation on the label.

**Interactivity:** Read-only. Hover tooltip shows: `"Server contribution: 142k / 800k chars (23 messages). Agent-reported: —"` — explicitly naming the gap rather than implying total usage.

**Data flow:** Subscribes to `DebateEventBus` with `onMeta` callback. Filters for `type === 'context-usage'`; ignores other metadata event types. Caches `windowSizeChars` from the initial snapshot. Updates gauge on each `context-usage` event.

### Lifecycle behaviour

| Event | Gauge behaviour |
|-------|----------------|
| Debate session connects | Gauge appears, shows initial snapshot |
| `end_debate` called | SSE stream disconnects on next tick (session lookup fails); gauge hides and resets |
| `restart_from_round` called | Browser connects to new session; gauge resets. The RESTART_CONTEXT message content is counted as `addInitialContribution()` for the new session |
| Session switch (picker) | `DebateEventBus.connect(newSessionId)` fires; onReconnect clears state; new session's initial snapshot arrives; gauge updates |
| SSE disconnect / error | `onReconnect` fires; gauge shows last known state until new data arrives |

## Configuration

```properties
casehub.drafthouse.context.window-size-chars=800000
casehub.drafthouse.context.threshold-percent=80
```

Added to `DraftHouseConfig`:

```java
interface Context {
    @WithDefault("800000")
    long windowSizeChars();

    @WithDefault("80")
    double thresholdPercent();
}
```

## Out of Scope

- **Automatic reset** — requires self-bootstrapping manifest (#26 idea 9)
- **`reset_session` MCP tool** — deferred until auto-reset ships
- **Token counting** — character approximation is sufficient; no tokenizer dependency
- **Per-agent tracking** — one meter per debate session, not per participant
- **Usage history/trends** — no persistence across sessions

## Files Changed

| Layer | File | Change |
|-------|------|--------|
| api | `ContextTracker.java` (new) | Mutable thread-safe accumulator |
| api | `ContextSnapshot.java` (new) | Immutable snapshot record for SSE |
| api | `DebateSession.java` | Add `ContextTracker` field |
| runtime | `DraftHouseConfig.java` | Add `Context` interface |
| runtime | `DebateMcpTools.java` | Add `report_context` tool; add `addContribution()` calls after each dispatch |
| runtime | `DebateEventResource.java` | Inject context snapshot on connect; accept pushed snapshots for live delivery |
| panels | `drafthouse-context-gauge.js` (new) | `<drafthouse-context-gauge>` Web Component |
| panels | `debate-event-bus.js` | Add `onMeta` to subscriber contract |
| shell | `index.html` | Import gauge; create and place in topbar; subscribe to DebateEventBus |
| shell | `styles.css` | Gauge topbar placement styles (component styles are in the Web Component) |
| config | `application.properties` | Document new config keys |

## References

- #26 — Review loop architecture (ideas 3, 4, 9)
- #41 — Original parent issue (closed)
- #51 — Workspace UI redesign (prerequisite, complete)
