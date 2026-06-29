# P5: Structured Conversation Protocol Extraction to casehub-blocks

**Date:** 2026-06-29
**Issue:** casehubio/drafthouse#81
**Parent epic:** casehubio/drafthouse#76 (target revised from Qhorus to casehub-blocks — blocks is the right tier for composed patterns above foundation primitives)

## Problem

DraftHouse's debate channel infrastructure — typed entries, point lifecycle,
round-based progression, projections, summary rendering, and sub-agent dispatch —
is a general interaction pattern, not a document review feature. Any CaseHub
application needing structured agent deliberation (Devtown PR reviews, Clinical
case conferences, AML investigation debates) would need the same machinery.

P1–P4 extracted the building blocks (ChannelMessageMeta, ContextTracker,
BoundedProjectionDecorator, ChannelAgentDispatcher). P5 extracts the conversation
protocol itself — the heart of structured multi-agent deliberation.

## Design decision: concrete protocol, not a framework

The grammar of structured conversations is shared across applications; only the
vocabulary varies. Every domain entry type does one of two things: **create a
point** or **respond to a point and set its status**. The handler variation is
just "which status?" — the mechanics are identical.

This means a **concrete protocol** with string-typed entry types and a two-method
subclass hook handles all known cases. No generic type parameters, no strategy
registries, no framework abstractions.

## Package

`io.casehub.blocks.conversation`

## Data model

All types are records with defensive immutability (unmodifiable collection views
in compact constructors). Same pattern as the existing `ReviewState`.

### ConversationState

The fold accumulator for a structured conversation.

```
ConversationState
├── points: Map<String, ConversationPoint>
├── humanFlags: List<FlagEntry>
├── memos: List<RoundMemo>
└── subTaskFindings: Map<String, SubTaskFinding>
```

### ConversationPoint

A topic raised in a conversation, with a thread of responses and lifecycle status.

```
ConversationPoint
├── id: String
├── classification: PointClassification
├── thread: List<ThreadEntry>
└── status: String                    ← app-defined ("OPEN", "AGREED", "APPROVED", etc.)
```

### ThreadEntry

An individual entry in a point's thread.

```
ThreadEntry
├── entryId: String                   ← correlation ID (non-null for point initiators, null for responses)
├── role: String                      ← app-defined ("REV", "reviewer", etc.)
├── round: int
├── entryType: String                 ← app-defined ("RAISE", "COMMENT", etc.)
└── content: String
```

### PointClassification

Metadata attached to a point at creation time.

```
PointClassification
├── priority: Priority                ← enum: HIGH, MEDIUM, LOW
├── scope: String                     ← nullable, app-defined ("SYSTEMIC", "ISOLATED")
└── location: String                  ← nullable, app-defined ("file.java:42", "§3.2")
```

### FlagEntry

A human escalation flag.

```
FlagEntry
├── entryId: String
├── round: int
├── role: String
└── content: String
```

### RoundMemo

Per-round agent reasoning memo.

```
RoundMemo
├── role: String
├── round: int
└── content: String
```

### SubTaskFinding

Sub-agent task lifecycle.

```
SubTaskFinding
├── subTaskId: String
├── taskType: String                  ← app-defined ("VERIFY", "ARBITRATE", etc.)
├── requestedBy: String                   ← was requestingAgent; "agent" vocabulary normalised to match protocol
├── pointId: String                   ← nullable (null = standalone finding)
├── finding: String                   ← nullable (null when PENDING or ERROR)
├── errorReason: String               ← nullable
└── status: SubTaskStatus             ← enum: PENDING, COMPLETE, ERROR
```

### Enums

```java
public enum Priority { HIGH, MEDIUM, LOW }
public enum SubTaskStatus { PENDING, COMPLETE, ERROR }
```

## Protocol

`ConversationProtocol` — final utility class. Defines the protocol vocabulary as
string constants. No encoding/decoding methods — `ChannelMessageMeta` (already in
blocks) handles that. Each app chooses its own sentinel.

### Meta key constants

```java
public static final String ENTRY_TYPE  = "entryType";
public static final String ROLE        = "role";
public static final String ROUND       = "round";
public static final String PRIORITY    = "priority";
public static final String SCOPE       = "scope";
public static final String LOCATION    = "location";
public static final String POINT_ID    = "pointId";
public static final String SUB_TASK_ID = "subTaskId";
public static final String TASK_TYPE   = "taskType";
```

### Infrastructure entry types

```java
public static final String SUB_TASK_REQUEST  = "SUB_TASK_REQUEST";
public static final String SUB_TASK_FINDING  = "SUB_TASK_FINDING";
public static final String SUB_TASK_ERROR    = "SUB_TASK_ERROR";
public static final String MEMO              = "MEMO";
public static final String FLAG_HUMAN        = "FLAG_HUMAN";
public static final String RESTART_CONTEXT   = "RESTART_CONTEXT";
```

### Protocol-level statuses

Set by the projection base class, not by apps.

```java
public static final String STATUS_OPEN      = "OPEN";
public static final String STATUS_ESCALATED = "ESCALATED";
```

**Breaking change:** DraftHouse's `ReviewStatus.PENDING_HUMAN` becomes `ESCALATED`
at the protocol level. This is an intentional semantic consolidation — "escalated to
human" is the protocol concept; "pending human" was a DraftHouse-specific name for the
same thing. MCP tool consumers that string-match on `PENDING_HUMAN` must update to
`ESCALATED`. The compile-time migration (enum → String) forces all Java call sites to
update.

## Projection

`ConversationProjection` — abstract class implementing `ChannelProjection<ConversationState>`.

### Concrete methods (base class provides)

```java
@Override public ConversationState identity() {
    return new ConversationState(Map.of(), List.of(), List.of(), Map.of());
}
```

The identity state is domain-independent — empty maps and lists. Subclasses do NOT
override this.

### Abstract methods (app provides)

```java
protected abstract String sentinel();
// App's sentinel prefix. DraftHouse: "DHMETA:"

protected abstract boolean isPointInitiator(String entryType);
// Does this entry type create a new point?
// DraftHouse: "RAISE".equals(entryType)

protected abstract String statusAfter(String entryType);
// What status should the point have after this entry type?
// Return null for unknown types (entry appended, status unchanged).
// DraftHouse: "AGREE"→"AGREED", "COUNTER"→"ACTIVE", "DISPUTE"→"DISPUTED", etc.
```

### apply() dispatch logic

The fold must never throw — all parsing is graceful with defaults.

1. Parse metadata via `ChannelMessageMeta.parseMeta(sentinel(), content)`
2. No metadata → return state unchanged
3. Extract `entryType` from meta — if absent, return state unchanged
4. `RESTART_CONTEXT` → return state unchanged (transparent infrastructure marker)
5. Extract `role` from meta — if absent, log warning, return state unchanged
6. Extract `pointId` from `message.correlationId()` (Qhorus MessageView field,
   not a meta key)
7. **Infrastructure dispatch** (base class handles completely):
   - `MEMO` → append RoundMemo
   - `SUB_TASK_REQUEST` → create pending SubTaskFinding with `requestedBy = role`
   - `SUB_TASK_FINDING` → update finding with result, preserving `requestedBy`
     and `pointId` from the existing record
   - `SUB_TASK_ERROR` → update finding with error, preserving `requestedBy`
     and `pointId` from the existing record
   - `FLAG_HUMAN`:
     - With target point (`pointId` non-null and exists in state): append to
       point thread + set status `ESCALATED` + add FlagEntry
     - Without target point: add FlagEntry only (general escalation)
8. **Domain dispatch** (hook methods):
   - `isPointInitiator(entryType)` true:
     - If `message.correlationId()` is null → return state unchanged (cannot
       create a point with null ID)
     - Otherwise → create ConversationPoint with `id = message.correlationId()`,
       status `OPEN`, and PointClassification parsed from meta (priority defaults
       to `Priority.LOW` on absent/invalid values; scope and location are nullable
       strings from meta)
   - Otherwise (response to existing point):
     - If `message.correlationId()` is null or does not match an existing point
       → log warning, return state unchanged
     - Otherwise → append ThreadEntry to existing point
   - `statusAfter(entryType)` non-null → update point status
   - `statusAfter(entryType)` null → status unchanged (safe extension for unknown types)

**Bug fix from DraftHouse:** the current `handleSubTaskFinding` and
`handleSubTaskError` overwrite `requestingAgent` with the FINDING/ERROR message's
agent instead of preserving the original requester from the REQUEST. The base
class fixes this: `requestedBy` and `pointId` are always preserved from the
existing SubTaskFinding record.

### DraftHouse subclass

```java
public class DebateChannelProjection extends ConversationProjection
        implements RenderableProjection<ConversationState> {

    private final ConversationRenderer renderer = new ConversationRenderer(DEBATE_CONFIG);

    @Override public String projectionName() { return "debate-summary"; }

    @Override public String render(ProjectionResult<ConversationState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    /** Renders raw state — used by DebateMcpTools.renderBounded(). */
    public String renderState(ConversationState state) {
        return renderer.render(state);
    }

    @Override protected String sentinel() { return "DHMETA:"; }

    @Override protected boolean isPointInitiator(String entryType) {
        return "RAISE".equals(entryType);
    }

    @Override protected String statusAfter(String entryType) {
        return switch (entryType) {
            case "AGREE"            -> "AGREED";
            case "COUNTER", "QUALIFY" -> "ACTIVE";
            case "DISPUTE"          -> "DISPUTED";
            case "DECLINED"         -> "DECLINED";
            default                 -> null;
        };
    }
}
```

`RoundBoundedProjection` stays in DraftHouse — composes
`BoundedProjectionDecorator<ConversationState>` with the DraftHouse sentinel for
round extraction.

## Renderer

`ConversationRenderer` — concrete class, configurable via `ConversationRendererConfig`.

### ConversationRendererConfig

```
ConversationRendererConfig
├── statusEmoji: Map<String, String>       ← "OPEN"→"🔴", "AGREED"→"✅", etc.
├── priorityLabel: Map<Priority, String>   ← HIGH→"P1", MEDIUM→"P2", etc.
├── entryTypeLabel: Map<String, String>    ← display labels for entry types
├── roleLabel: Map<String, String>         ← display labels for roles
├── resolvedStatuses: Set<String>          ← for grouping (e.g., {"AGREED", "DECLINED"})
└── escalatedStatuses: Set<String>         ← for grouping (e.g., {"ESCALATED"})
```

### Rendering structure (protocol-level)

**Behavioral change from current renderer:** the existing `SummaryRenderer`
iterates points in insertion order (LinkedHashMap). `ConversationRenderer`
introduces semantic grouping — this is intentional, not just extraction.

1. Points grouped by resolution: unresolved first, then escalated, then resolved.
   Within each group, insertion order is preserved (chronological narrative flow).
2. Each point header: `{emoji} **[{id}]** {priority} · {scope} · {location}` —
   strikethrough for resolved points
3. Thread entries: `> **{role}** ({entryType}): {content}`
4. Point-specific sub-task findings inline under their point
5. Standalone sub-task findings (null pointId) in a separate section
6. Human flags section
7. Agent memos grouped by round

### Defaults

Works with no config. Unknown statuses get `⬜` default emoji. Unknown roles and
entry types display their raw string value. Priority renders via `toString()`
(HIGH/MEDIUM/LOW) when no `priorityLabel` is configured.

### Pure rendering — no clock

The current `SummaryRenderer` carries a mutable `Supplier<Instant>` clock and
renders a `**Updated:**` timestamp. `ConversationRenderer` drops both — the
renderer is a pure function of `(ConversationState, ConversationRendererConfig) → String`.
Consumers know when they requested the summary; embedding the timestamp in the
output is redundant and makes the renderer non-deterministic. No `setClockForTest`
seam needed.

### DraftHouse configuration

```java
ConversationRendererConfig.builder()
    .statusEmoji(Map.of(
        "OPEN", "🔴", "ACTIVE", "🟡", "AGREED", "✅",
        "ESCALATED", "🔵", "DECLINED", "🚫", "DISPUTED", "⚡"))
    .resolvedStatuses(Set.of("AGREED", "DECLINED"))
    .escalatedStatuses(Set.of("ESCALATED"))
    .priorityLabel(Map.of(
        Priority.HIGH, "P1", Priority.MEDIUM, "P2", Priority.LOW, "P3"))
    .entryTypeLabel(Map.of(
        "RAISE", "raised", "AGREE", "agreed", "COUNTER", "countered",
        "DISPUTE", "disputed", "QUALIFY", "qualified",
        "FLAG_HUMAN", "flag", "DECLINED", "declined"))
    .roleLabel(Map.of("REV", "REV", "IMP", "IMP"))
    .build();
```

## DraftHouse migration

### Deleted from server/api (moved to blocks)

- `ReviewState`, `ReviewPoint`, `ThreadEntry`, `PointClassification`
- `Priority`, `Scope`, `ReviewStatus` enums
- `FlagEntry`, `RoundMemo`, `SubTaskFinding`
- `SubTaskType`, `SubTaskStatus` enums
- `SummaryRenderer`

### Stays in server/api (DraftHouse-specific)

- `AgentType` enum (REV/IMP/SUPERVISOR/MODERATOR/SELECTOR)
- `EntryType` enum — kept as app-level constants for compile-time convenience
  in MCP tools
- `DebateSession`, `DebateSessionSnapshot`, `DebateSessionStore` SPI
- `DocumentEntry`, `ComparisonPair`, `ResolvedReviewer`
- `ReviewConversationRenderer` — updated to use blocks types
  (`ConversationState`, `ConversationPoint`, String status comparisons).
  Compact Q&A renderer for resolved review points; stays DraftHouse-specific.

### Stays in server/runtime (DraftHouse-specific)

- `DebateChannelProjection` — ~15-line subclass of `ConversationProjection`.
  Additionally implements `RenderableProjection<ConversationState>` to preserve
  the `projectionName()` registry entry and `render()` method.
  `render()` delegates to `ConversationRenderer` with DraftHouse config.
  `DebateMcpTools.getDebateSummary()` continues calling `debateProjection.render()`
  as before — the call chain is unchanged, only the rendering implementation moves.
  Adds a public `renderState(ConversationState)` method that delegates to the
  internal `ConversationRenderer` — used by `DebateMcpTools.renderBounded()` to
  render bounded state without constructing its own renderer or duplicating config.
- `DebateProtocol` — thins to sentinel constant + convenience wrappers
- `DebateMcpTools` — import paths change (`ReviewState` → `ConversationState`,
  `SubTaskStatus` → `io.casehub.blocks.conversation.SubTaskStatus`). Encoding
  migrates from manual string concatenation to
  `ChannelMessageMeta.encode(sentinel, meta, body)`. Wire key changes from
  `"agent"` to `"role"` to match the protocol constant. `renderBounded()` replaces
  `new SummaryRenderer().render(state)` with `debateProjection.renderState(state)`
  — `SummaryRenderer` is deleted, and `ConversationRenderer` requires a config;
  routing through the already-injected projection avoids config duplication.
- `DebateChannelBackend`, `DebateChannelBackendFactory` — unchanged
- `DebateEventResource` — unchanged
- `DebateStreamEntry` — updated: `Scope scope` field → `String scope`,
  `Priority` import → `io.casehub.blocks.conversation.Priority`,
  `parsePriority()` updated for HIGH/MEDIUM/LOW values,
  `parseScope()` replaced with direct String extraction,
  `meta.get("agent")` → `meta.get("role")`
- `RoundBoundedProjection` — composes BoundedProjectionDecorator with DraftHouse sentinel
- `ReviewChannelProjection` — updated to use blocks data types
  (`ConversationState`, `ConversationPoint`, `ThreadEntry` with `role: String`,
  `PointClassification` with `scope: String`, `Priority.HIGH/MEDIUM/LOW`).
  Implements `ChannelProjection<ConversationState>` directly — does NOT extend
  `ConversationProjection` because it dispatches on `message.type()` (Qhorus
  MessageType), not META headers. Keeps its own fold logic.
- `ReviewerChannelBackend` — updated: `ChannelProjection<ReviewState>` field →
  `ChannelProjection<ConversationState>`, `ReviewState` → `ConversationState` in
  projection result usage. `ReviewConversationRenderer` import unchanged (stays
  DraftHouse-specific in server/api).
- **Handler package** (`io.casehub.drafthouse.handler`) — all 7 classes stay in
  server/runtime. Changes by class:
  - `AbstractDebateSubAgentHandler` — `abstract SubTaskType taskType()` →
    `abstract String taskType()`; `handles()` changes from
    `SubTaskType.valueOf(meta.get("taskType")) == taskType()` (enum dispatch) to
    `taskType().equals(meta.get("taskType"))` (string dispatch);
    `buildResponse()` wire key `"agent"` → `"role"`, `taskType().name()` →
    `taskType()` (already a String), encoding migrates from manual string
    concatenation to `ChannelMessageMeta.encode()`; `currentState()` return type
    `ReviewState` → `ConversationState`; `requirePoint()` return type
    `ReviewPoint` → `ConversationPoint`
  - `ArbitrateHandler` — `SubTaskType.ARBITRATE` → `"ARBITRATE"`;
    `e.type() == EntryType.DISPUTE` → `"DISPUTE".equals(e.entryType())` (and
    likewise for QUALIFY, COUNTER); `ThreadEntry` accessor changes
  - `ConsistencyCheckHandler` — `SubTaskType.CONSISTENCY_CHECK` →
    `"CONSISTENCY_CHECK"`; `p.currentStatus() == ReviewStatus.AGREED` →
    `"AGREED".equals(p.status())`
  - `CustomHandler` — `SubTaskType.CUSTOM` → `"CUSTOM"`
  - `DeepAnalysisHandler` — `SubTaskType.DEEP_ANALYSIS` → `"DEEP_ANALYSIS"`;
    `ReviewState`, `ReviewPoint` → blocks types
  - `NeutralSummaryHandler` — `SubTaskType.NEUTRAL_SUMMARY` → `"NEUTRAL_SUMMARY"`;
    `e.agent()` → `e.role()`, `e.type().name()` → `e.entryType()`
  - `VerifyHandler` — `SubTaskType.VERIFY` → `"VERIFY"`;
    `ReviewState` → `ConversationState`

### Priority wire format

DraftHouse encoding changes from `"P1"/"P2"/"P3"` to `"HIGH"/"MEDIUM"/"LOW"` to
match the blocks enum. Display labels ("P1", "P2", "P3") are handled by the
renderer config, not the wire format.

**Existing sessions:** all active debate sessions are abandoned by this migration.
Debate sessions are ephemeral (minutes to hours, not days) and DraftHouse is a dev
tool — no session data needs to survive. Replaying old channel messages with
`priority=P1` through the new projection would fail `Priority.valueOf("P1")`.
No migration script or backward-compatible parsing is needed.

### Dependency

`casehub-blocks` is already a dependency of `server/api` (from P1–P4). No new
dependency needed.

## Testing

### In casehub-blocks (~30-35 tests)

Tests use a `TestConversationProjection` with a minimal vocabulary: `OPEN_TOPIC`
(initiator), `ACCEPT`/`REJECT`/`CHALLENGE` (responses) mapping to statuses
`ACCEPTED`/`REJECTED`/`CHALLENGED`. No DraftHouse terminology in blocks tests.

**ConversationState tests:**
- Immutable record contracts — defensive copies, unmodifiable views, identity state

**ConversationProjection tests:**
- Point initiation: initiator type → creates point with OPEN status
- Point response: non-initiator → appends to thread, sets status from statusAfter()
- Unknown domain type: statusAfter() returns null → appends entry, status unchanged
- Infrastructure dispatch: MEMO, SUB_TASK_REQUEST/FINDING/ERROR, FLAG_HUMAN
- SUB_TASK_FINDING preserves requestedBy from original REQUEST (bug fix)
- SUB_TASK_ERROR preserves requestedBy from original REQUEST (bug fix)
- FLAG_HUMAN with target point → thread update + ESCALATED + FlagEntry
- FLAG_HUMAN without target point → FlagEntry only (general escalation)
- RESTART_CONTEXT → state unchanged (transparent)
- Point initiation uses message.correlationId() as point ID
- Missing metadata → state unchanged with log
- Missing role → graceful handling (never throw from apply)
- Null correlationId on point initiation → state unchanged (no point created)
- Response targeting null correlationId → state unchanged with warning
- Response targeting non-existent point → state unchanged with warning
- Round numbers flow through to ThreadEntry, RoundMemo
- Multi-point accumulation: interleaved threads across points

**ConversationRenderer tests:**
- Empty state → minimal output
- Points grouped by resolution status (unresolved first)
- Strikethrough on resolved points
- Emoji mapping: configured status → configured emoji, unknown → default
- Role/entry type/priority labels from config
- Sub-task findings: point-specific inline, standalone separate
- Human flags section
- Memos grouped by round
- Default config (no customisation) → raw strings with default emoji

**ConversationRendererConfig tests:**
- Builder validation, defaults

### In DraftHouse (adapted existing tests)

- `DebateChannelProjection` — slim tests confirming the three hooks map correctly
  (RAISE→initiator, AGREE→AGREED, etc.). Heavy fold/infrastructure tests move to
  blocks. New test for `renderState()` delegation.
- Handler tests (6 tests: `ArbitrateHandlerTest`, `ConsistencyCheckHandlerTest`,
  `CustomHandlerTest`, `DeepAnalysisHandlerTest`, `NeutralSummaryHandlerTest`,
  `VerifyHandlerTest`) — adapted: construct blocks types (`ConversationState`,
  `ConversationPoint`, `Priority.HIGH/MEDIUM/LOW`, String status/scope),
  `SubTaskType` enum references → string constants, `ThreadEntry` constructors use
  `role: String` and `entryType: String`, `ReviewStatus` enum comparisons → string
  comparisons.
- `ReviewerChannelBackendTest` — adapted: `ReviewState` → `ConversationState`,
  `ReviewPoint` → `ConversationPoint`, `PointClassification` with `scope: String`,
  `Priority.HIGH/MEDIUM/LOW`, `ReviewStatus` → String, `EntryType` → String,
  `Scope` → String, `ThreadEntry` with string role/entryType.
- `DebateMcpToolsTest` — adapted: `ReviewState` → `ConversationState`,
  `SubTaskFinding` with `taskType: String` and `requestedBy: String`,
  `SubTaskType` references → string constants.
- Existing E2E tests — unchanged, exercise full stack through MCP tools.

## Type mapping summary

| DraftHouse | blocks.conversation | Change |
|---|---|---|
| ReviewState | ConversationState | Domain-neutral name |
| ReviewPoint | ConversationPoint | Domain-neutral name |
| ThreadEntry | ThreadEntry | `agent: AgentType` → `role: String` |
| PointClassification | PointClassification | `scope: Scope` → `scope: String` |
| Priority (P1/P2/P3) | Priority (HIGH/MEDIUM/LOW) | Values renamed, wire format changes |
| Scope enum | *(removed)* | String field on PointClassification |
| ReviewStatus enum | *(removed)* | Status is String on ConversationPoint |
| EntryType enum | *(removed from blocks)* | Strings + app constants |
| AgentType enum | *(stays in DraftHouse)* | Role is String at protocol level |
| SubTaskType enum | *(removed)* | Task types are strings |
| SubTaskStatus | SubTaskStatus | Unchanged |
| FlagEntry | FlagEntry | `agent: AgentType` → `role: String` |
| RoundMemo | RoundMemo | `agentRole: String` → `role: String` |
| SubTaskFinding | SubTaskFinding | `taskType: SubTaskType` → `taskType: String`, `requestingAgent` → `requestedBy` |
| SummaryRenderer | ConversationRenderer | Configurable via ConversationRendererConfig |
| DebateProtocol | ConversationProtocol | Constants only, no encoding methods |
| DebateChannelProjection | ConversationProjection | Abstract base + 3 hook methods |
