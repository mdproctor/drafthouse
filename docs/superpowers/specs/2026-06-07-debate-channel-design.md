# DebateChannel Design
**Date:** 2026-06-07 (revised — fifth pass)
**Status:** Approved
**Issue:** #27

---

## Problem

The debate between reviewer (REV) and implementer (IMP) agents has no write path into the Qhorus channel. `DebateChannelProjection` already folds channel history into `ReviewState`, but agents have no MCP tools to post debate entries, and the projection carries two v1 heuristics that must be retired:

1. Agent classification via `actorType` (HUMAN→REV, AGENT→IMP) — broken when both agents are `ActorType.AGENT` (PP-20260607-508f7b)
2. Qualify/agree distinction via `[QUALIFY]` string prefix in message content — a protocol hack

There is also a naming collision: the existing `DebateChannelProjection` is **not** a debate projection — it projects the review Q&A channel (`ReviewerChannelBackendFactory` injects it). The class implements `message.type()`-based dispatch for QUERY/RESPONSE/DECLINE/HANDOFF. Changing this class to use `artefactRefs.entryType` dispatch would silently break the review channel (review messages have null `artefactRefs`; null entryType → all messages discarded).

---

## Goals

- MCP write path: agents post structured debate entries via `DebateMcpTools`
- Clean message encoding: Qhorus type for commitment lifecycle; `artefactRefs` for debate-domain metadata
- Split projection classes: `ReviewChannelProjection` (review Q&A) and `DebateChannelProjection` (peer-to-peer debate) — separate channels, incompatible message encoding, one class each
- Retire the `actorType` heuristic and `[QUALIFY]` prefix hack from the **debate** projection only
- Lightweight `DebateChannelBackend` as a registration fence (no LLM triggering on debate channels)
- No file-based storage — Qhorus channel IS the debate record

---

## Non-Goals

- Linking debate sessions to review sessions (separate lifecycles, different use cases)
- Protocol enforcement in the backend (`post()` returns void — cannot surface errors to caller)
- UI / E2E surface (debate is MCP-only in this issue)
- Retiring `[QUALIFY]` from the review channel — requires changing `DocumentReviewer @AiService`, tracked in #38
- Sub-agent architecture (→ #26)

---

## Projection Split — the Architectural Fix

Two channels carry the same fold state type (`ReviewState`) but with incompatible message encoding:

| | Review channel (`drafthouse/r-{uuid}`) | Debate channel (`drafthouse/debate/d-{uuid}`) |
|---|---|---|
| Agent classification | `message.actorType()` (HUMAN→REV, AGENT→IMP) | `artefactRefs.agent` (explicit: `REV`/`IMP`) |
| Entry type dispatch | `message.type()` (QUERY/RESPONSE/DECLINE/HANDOFF) | `artefactRefs.entryType` (raise/agree/dispute/…) |
| `artefactRefs` | null always | required |

**Fix: rename + create.**

### `ReviewChannelProjection` (renamed from `DebateChannelProjection`)

- Keeps `message.type()`-based dispatch
- Keeps `[QUALIFY]` prefix logic (`DocumentReviewer` still uses it — retiring requires a `DocumentReviewer` change, tracked in #38)
- Keeps `actorType`-based agent classification (HUMAN→REV, AGENT→IMP — correct for review Q&A)
- Implements **`ChannelProjection<ReviewState>` only** — not `RenderableProjection<ReviewState>`. `ReviewerChannelBackend` calls `conversationRenderer.render(state)` directly; `projection.render()` is never called on the review channel. Removing `RenderableProjection` eliminates dead code and makes the interface contract explicit.
- `projectionName()` is **removed** (method belongs to `RenderableProjection`, which this class no longer implements)

### `DebateChannelProjection` (new class)

- Dispatches on `artefactRefs.entryType` — unknown entry types are discarded (state unchanged, no exception)
- Uses `artefactRefs.get("agent")` for REV/IMP classification — missing `agent` throws `IllegalArgumentException`
- Implements `RenderableProjection<ReviewState>` — `render()` called by `get_debate_summary`
- `projectionName()` returns `"debate-summary"`

### Wiring changes

- `ReviewerChannelBackend`: field type stays `ChannelProjection<ReviewState>` (unchanged)
- `ReviewerChannelBackendFactory`: `@Inject ReviewChannelProjection projection` (was `DebateChannelProjection`)
- `DebateMcpTools`: `@Inject DebateChannelProjection debateProjection`

---

## Domain model changes (api module)

### `EntryType`

```java
public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED
}
```

`COUNTER` added. `qualify` and `counter` both map to RESPONSE in Qhorus (thread continues), but they mean different things in the debate: `qualify` is partial acceptance; `counter` is a new sub-argument. The domain model preserves the distinction.

### `ReviewStatus`

```java
public enum ReviewStatus {
    OPEN, ACTIVE, AGREED, PENDING_HUMAN, DECLINED, DISPUTED
}
```

`DISPUTED` added. In the review channel, `DECLINED` is terminal: the AI refused to answer; the point is closed (renders with 🚫 strikethrough). In the debate channel, a disputed point is **non-terminal**: the thread continues with counter, qualify, eventually agree or flag-human. These semantics are incompatible. `ReviewStatus.DISPUTED` carries the correct non-terminal, live-contestation meaning.

Naming follows the existing past-participle convention: `AGREE` → `AGREED`, `DISPUTE` → `DISPUTED`.

---

## `SummaryRenderer` changes (api module)

**`statusMarker` switch** — add `DISPUTED` case (compile-required — exhaustive switch):

```java
String statusMarker = switch (point.currentStatus()) {
    case OPEN          -> "🔴";
    case ACTIVE        -> "🟡";
    case AGREED        -> "✅";
    case PENDING_HUMAN -> "🔵";
    case DECLINED      -> "🚫";
    case DISPUTED      -> "⚡";  // ← new: live contestation
};
```

**Strikethrough condition** — `DISPUTED` is explicitly NOT added:

```java
boolean strikethrough = point.currentStatus() == ReviewStatus.AGREED
        || point.currentStatus() == ReviewStatus.DECLINED;
// DISPUTED is non-terminal — no strikethrough
```

**`typeLabel` switch** — add `COUNTER` case (compile-required — exhaustive switch):

```java
String typeLabel = switch (entry.type()) {
    case RAISE      -> "raise";
    case AGREE      -> "agree";
    case COUNTER    -> "counter";  // ← new
    case DISPUTE    -> "dispute";
    case QUALIFY    -> "qualify";
    case FLAG_HUMAN -> "flag";
    case DECLINED   -> "declined";
};
```

**`ReviewConversationRenderer`** — no change. It uses an if-chain (not an exhaustive switch) and processes review channel data only. Its filter (`!= AGREED && != DECLINED`) correctly excludes `DISPUTED` points — they are unresolved and have no Q&A answer to render.

---

## Channel & Session Model

### Channel naming

| Channel type | Name pattern |
|---|---|
| Review (existing) | `drafthouse/r-{uuid}` |
| Debate (new) | `drafthouse/debate/d-{uuid}` |

The `d-` prefix is required. Qhorus validates each path segment against `[a-z][a-z0-9]*(-[a-z0-9]+)*`. A raw UUID starting with a digit (~62.5% probability) fails this pattern — the same bug fixed for review channels in #35. The debate slug must be:

```java
String debateSlug = "d-" + UUID.randomUUID();
String channelName = "drafthouse/debate/" + debateSlug;
```

### `DebateSession` (api module)

```java
record DebateSession(
    UUID channelId,
    String debateSessionId,   // = channelId.toString()
    String channelName,
    String revInstanceId,     // "drafthouse-rev-{debateSessionId}"
    String impInstanceId      // "drafthouse-imp-{debateSessionId}"
)
```

`specPath` is NOT stored in `DebateSession`. It is echoed back in the `start_debate` response JSON from the input parameter directly. No subsequent tool reads it from the session.

### `DebateSessionRegistry`

Interface in `api/`, implementation in `runtime/`. Methods: `put(DebateSession)`, `find(UUID)`, `remove(UUID)`. Same pattern as `ReviewSessionRegistry` / `ReviewSessionRegistryImpl`.

Channel semantic: `ChannelSemantic.APPEND`.

---

## Shared Constants — `DraftHouseInstances`

`DraftHouseMcpTools.HUMAN_INSTANCE_ID` is consumed by `DebateMcpTools.flag_human` as the HANDOFF target. Extract to a constants class in the `runtime/` package:

```java
final class DraftHouseInstances {
    static final String HUMAN_INSTANCE_ID = "drafthouse-human";
    private DraftHouseInstances() {}
}
```

`DraftHouseMcpTools` and `DebateMcpTools` both reference `DraftHouseInstances.HUMAN_INSTANCE_ID`. The field on `DraftHouseMcpTools` is removed.

**Existing references that must be updated** (compile failures if not addressed):

| File | References | Change |
|---|---|---|
| `DraftHouseMcpTools.java` | `registerHumanInstance()`, `queryReview()` | `HUMAN_INSTANCE_ID` → `DraftHouseInstances.HUMAN_INSTANCE_ID` |
| `DraftHouseMcpToolsTest.java` | line 229 | `DraftHouseMcpTools.HUMAN_INSTANCE_ID` → `DraftHouseInstances.HUMAN_INSTANCE_ID` |
| `ReviewSessionLifecycleTest.java` | lines 100, 159, 194, 227, 236 | `DraftHouseMcpTools.HUMAN_INSTANCE_ID` → `DraftHouseInstances.HUMAN_INSTANCE_ID` |

---

## Message Encoding

> **Implementation note (discovered during #27):** `MessageDispatch.Builder.artefactRefs(String)` is a String at the API level, but the Qhorus runtime (`ArtefactRefParser`) validates the value as CSV UUIDs at dispatch time. Free-form key=value strings cause a transaction rollback. Debate metadata is therefore encoded in the `content` field using a `META:` header prefix, not in `artefactRefs`. See `#39` for a follow-up to use a safer sentinel.

Every debate message uses two fields:

- **Qhorus `MessageType`** — commitment lifecycle (what obligation does this create or close?)
- **`content`** — structured `META:` header (debate metadata) + `\n\n` separator + human-readable body text

`artefactRefs` is NOT used for debate metadata.

### `content` encoding schema

```
META:entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED|location=§3.2

The API contract is ambiguous.
```

```
META:entryType=agree|agent=IMP|round=2

Agreed — will clarify the contract in §3.2.
```

The `META:` prefix acts as a sentinel. `DebateChannelProjection.parseMeta()` splits on the first `\n\n` and parses the header as `key=value|...` pairs. `bodyContent()` returns everything after `\n\n` as the human-readable text stored in `ThreadEntry.content`.

**Known limitation:** If agent-generated debate text begins with `META:` (e.g., "META: the spec says..."), the prefix is misread as metadata. This is low-probability given agent-controlled input, but not impossible. Tracked in #39.

`entryType`, `agent`, `round` always present in header. `priority`, `scope` on `raise` entries. `location` optional; omit the key when blank or null.

### Qhorus type mapping

| `entryType` | Qhorus `MessageType` | `correlationId` | `inReplyTo` | Notes |
|---|---|---|---|---|
| `raise` | QUERY | fresh UUID (the point ID) | — | Creates obligation to respond |
| `agree` | DONE | cited point ID | message ID of cited QUERY | Closes obligation — terminal |
| `dispute` | DECLINE | cited point ID | message ID of cited QUERY | Rejects position — non-terminal (`DISPUTED` status) |
| `qualify` | RESPONSE | cited point ID | message ID of cited QUERY | Thread continues |
| `counter` | RESPONSE | cited point ID | message ID of cited QUERY | Thread continues |
| `flag-human` | HANDOFF | cited point ID | message ID of cited QUERY | `target=DraftHouseInstances.HUMAN_INSTANCE_ID` |

**On DECLINE dual-use:** In the review channel, `ReviewChannelProjection.handleDecline()` maps Qhorus DECLINE → `EntryType.DECLINED` / `ReviewStatus.DECLINED` (LLM declining an out-of-scope question — terminal). In the debate channel, `DebateChannelProjection` dispatches on `artefactRefs.entryType`; `entryType=dispute` → `handleDispute()` → `ReviewStatus.DISPUTED` (non-terminal). The two projections are isolated — no collision.

`inReplyTo` for cite-type messages resolved via `MessageService.findByCorrelationId(pointId)` (synchronous — the QUERY is already persisted before the next tool call).

---

## `DebateChannelProjection` (new class) — apply() dispatch

| `artefactRefs.entryType` | Handler | `EntryType` stored | `ReviewStatus` result |
|---|---|---|---|
| `raise` | `handleRaise` | `RAISE` | `OPEN` |
| `agree` | `handleAgree` | `AGREE` | `AGREED` |
| `dispute` | `handleDispute` | `DISPUTE` | `DISPUTED` ← non-terminal |
| `qualify` | `handleQualify` | `QUALIFY` | `ACTIVE` |
| `counter` | `handleCounter` | `COUNTER` | `ACTIVE` |
| `flag-human` | `handleFlagHuman` | `FLAG_HUMAN` | `PENDING_HUMAN` |
| unknown / null | discard | — | unchanged |

`agentType()` reads `artefactRefs.get("agent")`:
- `"REV"` → `AgentType.REV`
- `"IMP"` → `AgentType.IMP`
- null or unknown → `IllegalArgumentException`

`ThreadEntry.round` from `Integer.parseInt(artefactRefs.get("round"))`. Default 0 if absent (backward compat with pre-#27 stored messages).

`FlagEntry.round` in `handleFlagHuman` — same pattern: parse `artefactRefs.get("round")`, default 0 if absent. `FlagEntry` carries a `round` field explicitly documented in its Javadoc as "populated only via the v2 DebateChannel (drafthouse#27)". Debate channel `artefactRefs` always include `round={round}` on flag-human messages — populate it.

`parseArtefacts()` handles null `artefactRefs` via existing `message.artefactRefs() != null ? message.artefactRefs() : ""` guard — preserved.

---

## DebateChannelBackend

Stateless, `@ApplicationScoped`. `post()` is a no-op.

```java
@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {
    static final String BACKEND_ID = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}
    @Override public void post(ChannelRef channel, OutboundMessage message) {}
}
```

### DebateChannelBackendFactory

Observes `ChannelInitialisedEvent`. For channels matching `drafthouse/debate/` prefix:

```java
gateway.deregisterBackend(event.channelId(), DebateChannelBackend.BACKEND_ID); // guard against duplicate on restart
gateway.registerBackend(event.channelId(), debateBackend, DebateChannelBackend.BACKEND_TYPE);
```

Matches the pattern in `ReviewerChannelBackendFactory`. Startup recovery: `ChannelGateway` re-fires `ChannelInitialisedEvent` on `@Observes StartupEvent` for all stored channels. Sessions are in-memory and lost on restart (same as review sessions — acceptable). The backend re-registers cleanly because deregister-before-register is idempotent.

### ReviewerChannelBackendFactory — guard added

```java
void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
    if (!event.channelName().startsWith("drafthouse/")) return;
    if (event.channelName().startsWith("drafthouse/debate/")) return; // ← new guard
    if (registry.find(event.channelId()).isEmpty()) return;
    // ... existing logic
}
```

---

## DebateMcpTools

New `@ApplicationScoped` class. Error handling follows `mcp-tool-error-strings.md`: all errors returned as `"error: ..."` strings, no exceptions propagated.

### `sender` resolution

```java
private String sender(DebateSession session, String agentRole) {
    return switch (agentRole) {
        case "REV" -> session.revInstanceId();
        case "IMP" -> session.impInstanceId();
        default    -> throw new IllegalArgumentException("Unknown agentRole: " + agentRole);
    };
}
```

Switch expression (not ternary) — makes the contract explicit and catches any future agentRole addition at compile time. The ternary would silently return `impInstanceId` for unknown values even after agentRole validation, which is misleading. The `throw` is unreachable in practice (validation precedes every `sender()` call) but documents the invariant and provides a fail-fast signal if violated.

All debate dispatches set `.sender(sender(session, agentRole))`.

### Session precondition (all five tools except `start_debate`)

Every tool that takes `debateSessionId` applies the same precondition before any other logic, following the pattern established in `DraftHouseMcpTools`:

```java
UUID channelId;
try {
    channelId = UUID.fromString(debateSessionId);
} catch (IllegalArgumentException e) {
    return "error: invalid session id format: " + debateSessionId;
}
DebateSession session = registry.find(channelId).orElse(null);
if (session == null) {
    return "error: no active debate session for: " + debateSessionId;
    // Exception: end_debate returns {"debateSessionId":"...","status":"not-found"} — see below
}
```

`end_debate` uses the idempotent not-found pattern from `end_review`: when the session is absent, return `{"debateSessionId":"...", "status":"not-found"}` (JSON, not an error string) and exit without attempting deletion.

### Tool inventory

**`start_debate(specPath)`**
- Creates channel `drafthouse/debate/d-{uuid}`, semantic APPEND
- Registers `drafthouse-rev-{uuid}` and `drafthouse-imp-{uuid}` instances
- Puts `DebateSession` in registry **before** `channelGateway.initChannel()`
- Returns `{"debateSessionId":"...", "channel":"...", "specPath":"..."}`
- On failure: **removes registry entry first, then deletes channel** — matches `start_review` cleanup order (lines 108–109). Registry-first ensures any subsequent tool call gets "no active debate session" rather than a session handle pointing at a deleted channel. Note: `InstanceService` has no `deregister()` method (#33) — stale instances cleaned by Qhorus's `markStaleOlderThan()`. Accepted behavior, same as `start_review` gap.

**`raise_point(debateSessionId, agentRole, round: int, content, priority, scope, location)`**
- Precondition: session lookup (see above)
- `round`: `int` — Quarkus MCP deserializes JSON integer directly; matches `ThreadEntry.round` and `FlagEntry.round` field types. Serialized into `artefactRefs` as `round={round}` (String.valueOf).
- `agentRole`: `"REV"` or `"IMP"` — validated
- `location`: optional (nullable) — omitted from `artefactRefs` if null or blank
- Mints `pointId = UUID.randomUUID().toString()`
- Dispatches QUERY: `.sender(sender(session, agentRole))`, `.content(content)`, `.correlationId(pointId)`, `.artefactRefs("entryType=raise|agent={agentRole}|round={round}|priority={priority}|scope={scope}"` + `|location={location}` if present), `.actorType(ActorType.AGENT)`
- Returns `{"pointId":"...", "status":"dispatched"}`

**`respond_to(debateSessionId, agentRole, round: int, pointId, entryType, content)`**
- Precondition: session lookup (see above)
- `entryType`: `"agree"`, `"dispute"`, `"qualify"`, `"counter"` — validated
- Maps to Qhorus type: `agree`→DONE, `dispute`→DECLINE, `qualify`/`counter`→RESPONSE
- Resolves `inReplyTo` via `MessageService.findByCorrelationId(pointId)` — returns `"error: point not found: {pointId}"` if absent
- Dispatches: `.sender(sender(session, agentRole))`, `.content(content)`, `.correlationId(pointId)`, `.inReplyTo(inReplyTo)`, `.artefactRefs("entryType={entryType}|agent={agentRole}|round={round}")`, `.actorType(ActorType.AGENT)`
- Returns `{"status":"dispatched"}`

**`flag_human(debateSessionId, agentRole, round: int, pointId, reason)`**
- Precondition: session lookup (see above)
- Resolves `inReplyTo` via `MessageService.findByCorrelationId(pointId)` — returns `"error: point not found: {pointId}"` if absent (matches `respond_to` error behavior)
- Dispatches HANDOFF: `.sender(sender(session, agentRole))`, `.content(reason)`, `.target(DraftHouseInstances.HUMAN_INSTANCE_ID)`, `.correlationId(pointId)`, `.inReplyTo(inReplyTo)`, `.artefactRefs("entryType=flag-human|agent={agentRole}|round={round}")`, `.actorType(ActorType.AGENT)`
- Returns `{"status":"dispatched"}`

**`get_debate_summary(debateSessionId)`**
- Precondition: session lookup (see above)
- Projects via `ProjectionService.project(channelId, debateChannelProjection)`
- Returns `debateChannelProjection.render(result)` — markdown summary

**`end_debate(debateSessionId, deleteChannel)`**
- UUID parse — `"error: invalid session id format: ..."` if unparseable
- `registry.find(channelId)` — if absent: `{"debateSessionId":"...", "status":"not-found"}` (idempotent, not an error string)
- If found: removes session from registry; optionally deletes channel via `ChannelService.delete(session.channelName(), true)`
- Returns `{"debateSessionId":"...", "status":"ended", "channelDeleted": true/false}`

---

## Tests

### `ReviewChannelProjectionTest` (renamed from `DebateChannelProjectionTest`)

**14 tests are preserved unchanged.** Three tests are **deleted** because their methods no longer exist on `ReviewChannelProjection` (which implements `ChannelProjection<ReviewState>`, not `RenderableProjection`):

| Test | Reason for deletion |
|---|---|
| `projectionName_returnsDebateSummary` | `projectionName()` removed — not on `ChannelProjection` |
| `render_emptyResult_returnsNonBlankSentinel` | `render()` removed — not on `ChannelProjection` |
| `render_nonEmptyResult_returnsNonBlankString` | `render()` removed — not on `ChannelProjection` |

All remaining tests (`identity_*`, `apply_query_*`, `apply_response_agree_*`, `apply_response_qualify_*`, `apply_decline_*`, `apply_handoff_*`, `apply_event_*`, `apply_doesNotMutateInputState`, `agentType_nullActorType_throwsIAE`, `agentType_systemActorType_throwsIAE`) stay valid and unchanged — `ReviewChannelProjection` retains all corresponding logic.

### `SummaryRendererTest` (additions)

Two new tests cover the new cases:
- `rendersDisputedPoint_withLightningMarker_noStrikethrough` — `ReviewStatus.DISPUTED` → ⚡, no `~~`
- `rendersCounterEntryType_withCounterLabel` — `EntryType.COUNTER` → `"counter"` label in thread output

### `DebateChannelProjectionTest` (new)

Tests the new `DebateChannelProjection` with `artefactRefs`-based dispatch. All helpers use `artefactRefs` instead of `actorType`:

- `raise` via QUERY + `entryType=raise|agent=REV|round=1` → status OPEN, `AgentType.REV`, round=1 in thread
- `agree` via DONE + `entryType=agree|agent=IMP|round=2` → status AGREED
- `dispute` via DECLINE + `entryType=dispute` → status **DISPUTED** (not DECLINED)
- `qualify` via RESPONSE + `entryType=qualify` → status ACTIVE, `EntryType.QUALIFY`
- `counter` via RESPONSE + `entryType=counter` → status ACTIVE, `EntryType.COUNTER` (distinct from QUALIFY)
- `flag-human` via HANDOFF + `entryType=flag-human|agent=REV|round=3` → status PENDING_HUMAN, `humanFlags[0].round == 3` (verifies FlagEntry.round populated from artefactRefs)
- Unknown `entryType` → state unchanged (silent discard)
- Missing `agent` in artefactRefs → `IllegalArgumentException`
- `null actorType` on message → no exception (projection uses artefactRefs.agent, not actorType)
- `apply_doesNotMutateInputState` — apply raise then respond_to; assert the intermediate state after raise is unchanged after the respond_to call. Verifies the pure left-fold contract (same test as `ReviewChannelProjectionTest`).
- `projectionName()` returns `"debate-summary"`

### `DebateMcpToolsTest` (new, unit — no `@QuarkusTest`)

Follows the `DraftHouseMcpToolsTest` pattern: all Qhorus dependencies mocked with Mockito, no Quarkus container. Covers individual tool behavior:

- `startDebate_registryPutBeforeInitChannel` — `inOrder(registry, channelGateway)` verifies `registry.put()` before `channelGateway.initChannel()`. Critical ordering invariant; same pattern as `DraftHouseMcpToolsTest.startReview_registryPutBeforeInitChannel`.
- `startDebate_happyPath` → valid JSON; `ArgumentCaptor<DebateSession>` verifies all session fields: `channelId` matches the created channel's UUID, `debateSessionId` equals `channelId.toString()`, `channelName` starts with `"drafthouse/debate/d-"`, `revInstanceId` equals `"drafthouse-rev-{debateSessionId}"`, `impInstanceId` equals `"drafthouse-imp-{debateSessionId}"`. Mirrors `DraftHouseMcpToolsTest.startReview_happyPath_returnsSessionIdAndCreatesSession`.
- `raisePoint_invalidSessionIdFormat` → `"error: invalid session id format: ..."` (tests UUID parse precondition on a downstream tool; `start_debate` has no debateSessionId parameter and no UUID parse)
- `raise_point_unknownSession` → `"error: no active debate session for: ..."`
- `raise_point` → QUERY dispatched, correct sender/correlationId/artefactRefs/**content**
- `respond_to agree/dispute/qualify/counter` → correct Qhorus type, sender, `inReplyTo` resolved, **content present**
- `respond_to` with unknown pointId → `"error: point not found: {pointId}"`
- `flag_human` → HANDOFF with correct sender, target, and **content(reason)**
- `flag_human` with unknown pointId → `"error: point not found: {pointId}"`
- `get_debate_summary` → delegates to projection and returns rendered string
- `end_debate` → session removed, returns `"ended"` JSON
- `end_debate_unknownSession` → `{"status":"not-found"}` JSON (not error string)

### `DebateSessionLifecycleTest` (new, `@QuarkusTest`)

Real Qhorus runtime on H2 in-memory. Covers full-stack round-trip:

- `raise_point` → `respond_to` → `get_debate_summary` shows both entries in correct state
- Dispute renders as ⚡ DISPUTED (non-terminal, not struck through) in summary

**No Awaitility required.** `DebateChannelBackend.post()` is a no-op — `ChannelGateway.fanOut()` triggers no virtual thread work for debate channels. All debate operations (`raise_point`, `respond_to`, `get_debate_summary`) are synchronous end-to-end. This contrasts with `ReviewSessionLifecycleTest`, which requires Awaitility because `ReviewerChannelBackend.post()` executes on virtual threads via `fanOut()`.

**`@AfterEach` cleanup** follows the `ReviewSessionLifecycleTest` pattern: track `activeDebateSessionId` in the test instance, call `tools.endDebate(activeDebateSessionId, false)` in `@AfterEach` if non-null. Ensures H2 data is cleaned up between tests and exercises `end_debate` on the happy path.

### `DebateChannelBackendFactoryTest` (new, unit)

- Debate channel (`drafthouse/debate/d-...`) → `DebateChannelBackend` registered; `ReviewerChannelBackend` NOT registered
- Review channel (`drafthouse/r-...`) → `ReviewerChannelBackend` registered; `DebateChannelBackend` NOT registered

---

## Deferred

- #38 — Retire `[QUALIFY]` from `ReviewChannelProjection` (requires `DocumentReviewer` change)

---

## Platform coherence

- Correct repo: application-tier domain logic on Qhorus SPI — no foundation changes
- `ChannelBackend` SPI used correctly: per-channel, stateless for debate
- Single enforcement gate: all writes via `MessageService.dispatch(MessageDispatch)` — no bypasses
- `actorType=ActorType.AGENT` on all debate dispatches (both REV and IMP are LLM agents)
- Protocols: PP-20260607-508f7b (agent classification via artefactRefs in new `DebateChannelProjection`; actorType retained in `ReviewChannelProjection`), mcp-tool-error-strings.md (error string prefix)
- No new Flyway migrations — Qhorus owns the schema
- UUID slug uses `d-` prefix to satisfy Qhorus segment validator (mirrors `r-` fix from #35)
