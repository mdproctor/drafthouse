# Review Session Continuity — Sub-Agent Architecture
**Date:** 2026-06-09
**Status:** Approved
**Issue:** #26
**Deferred:** #40 (restart-from-N), #41 (threshold auto-reset + context meter)
**Supersedes (partial):** 2026-06-07 debate-channel-design.md §DebateSession (specPath reversal — see §specPath Reversal below)

---

## Problem

Multi-round LLM-to-LLM debate accumulates context across rounds. Auto-compaction summarises but loses nuance — the sense of *why* a concession was sound, or that an implementer systematically under-specifies error paths. Two failure modes:

1. **Nuance loss**: a cold agent re-reading the channel history may re-open settled points because it doesn't *feel* why they were settled, even if the record says "agreed"
2. **Fresh-perspective gap**: when a point needs a genuinely unbiased read, the main agents' accumulated context is a liability, not an asset

Both are solved by separating concerns: main agents hold accumulated judgment across rounds; sub-agents get deliberately minimal, focused input for one analytical task and terminate.

---

## Scope

Three capabilities in this issue:

| Capability | Idea from #26 |
|---|---|
| Reasoning memo per round | Idea 5 |
| Sub-agent spawning for focused analysis | Idea 6 |
| Provenance labelling in summary | Idea 7 |

Deferred: restart-from-N (#40), threshold auto-reset + context meter (#41), per-round fresh context (rejected — discards accumulated expertise), "run until" integration (future).

---

## Cross-Cutting Encoding Standard

**This spec establishes `EntryType.name()` as the canonical encoding for `entryType` in the META sentinel header.** This standardises all encoding to uppercase:

| Written by | Encoded string | Decoded by |
|---|---|---|
| `DebateMcpTools` | `entryType=RAISE`, `entryType=AGREE`, … | `DebateChannelProjection` |
| `SubAgentOrchestrator` | `entryType=SUB_TASK_FINDING`, `entryType=SUB_TASK_ERROR` | `DebateChannelProjection` |

**Migration:** The June 7 debate-channel spec encoded entry types as lowercase strings (`raise`, `agree`, `flag-human`). This spec replaces all lowercase encoded strings with their enum name equivalents (`RAISE`, `AGREE`, `FLAG_HUMAN`). The `flag-human` encoding is replaced with `FLAG_HUMAN` (hyphen → underscore). Since there are no production deployments, in-flight channel messages with old lowercase encoding will not be re-projected — acceptable.

**Files that must change to adopt this standard:**
- `DebateMcpTools.java` — all `entryType=raise|…` strings → `entryType=RAISE|…`
- `DebateChannelProjection.apply()` — string switch replaced with `EntryType.valueOf()` + enum switch (see Projection section)

**Encoding rule going forward:** always use `entryType=<EntryType.name()>`. Never hand-code lowercase strings. The enum name is the wire format.

---

## specPath Reversal from June 7 Design

The June 7 debate-channel spec (2026-06-07) explicitly stated: *"`specPath` is NOT stored in `DebateSession`. It is echoed back in the `start_debate` response JSON from the input parameter directly. No subsequent tool reads it from the session."*

**This spec reverses that decision.** `specPath` must be stored in `DebateSession`.

**Rationale:** `SubAgentOrchestrator` assembles input for `VERIFY` and `DEEP_ANALYSIS` tasks at dispatch time, which is asynchronous and occurs after the tool call has returned. The session is the only available state at that point. The spec path cannot be re-derived from the Qhorus channel history. Echoing it in the JSON response was sufficient for v1 (no orchestrator), but is insufficient when an async component needs it.

**Required change:** `DebateMcpTools.startDebate()` must store the `specPath` parameter in the `DebateSession` record rather than only echoing it in the JSON response.

---

## Architecture

```
DebateMcpTools
  ├── post_memo()         → MEMO entry → debate channel (MessageType.STATUS)
  └── request_subagent() → SUB_TASK_REQUEST entry → debate channel (MessageType.QUERY)
                                ↓
                      DebateChannelBackend.post(ChannelRef, OutboundMessage)
                      [evolved from pure fence — see §Backend Evolution]
                                ↓ event.fireAsync(SubAgentRequest)
                      SubAgentOrchestrator @ObservesAsync
                                ├── assembleTask() — task-type-driven, may throw IAE
                                ├── SubAgentProvider.analyse(task)
                                │     @DefaultBean: LangChain4jSubAgentProvider (ChatModel)
                                │     @ApplicationScoped: ClaudeSubAgentProvider (platform#55)
                                └── MessageService.dispatch(SUB_TASK_FINDING or SUB_TASK_ERROR)
                                              ↓
                                  DebateChannelProjection
                                  (renders findings with provenance label in SummaryRenderer)
```

Sub-task requests and findings live on the **existing debate channel** — no new Qhorus channels. `SubAgentOrchestrator` has no shared mutable state; concurrent invocations are safe by design (see §Concurrency).

---

## Domain Model (`api` module)

### `EntryType` — four new values

```java
public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED,
    MEMO,              // per-round reasoning memo from a main agent
    SUB_TASK_REQUEST,  // request for focused sub-agent analysis
    SUB_TASK_FINDING,  // sub-agent result (provenance: fresh context)
    SUB_TASK_ERROR     // sub-agent execution failure
}
```

Note: `DECLINED` is a projection-only status (set when the review channel AI refuses). No message is ever encoded with `entryType=DECLINED`. The enum value must still be handled in all exhaustive switches (see §SummaryRenderer).

### `SubTaskType`

```java
public enum SubTaskType {
    VERIFY,             // check a claim against the spec
    ARBITRATE,          // neutral read on a disputed point — both arguments, no prior history
    DEEP_ANALYSIS,      // close reading of a spec section before raising points
    CONSISTENCY_CHECK,  // does a proposed resolution contradict prior agreements?
    NEUTRAL_SUMMARY,    // compact neutral summary of the full current debate state (v1 approximation)
    CUSTOM              // caller provides explicit context string
}
```

`SubTaskType` is DraftHouse vocabulary. The extracted `ChannelAgentDispatcher` pattern (→ #42) carries no task-type enum; each application defines its own.

### `SubTaskFinding`

```java
public record SubTaskFinding(
    String subTaskId,
    SubTaskType taskType,
    String requestingAgent,  // "REV" or "IMP" — provenance: who asked
    String pointId,          // correlationId of related debate point; null for NEUTRAL_SUMMARY and CUSTOM
    String finding,          // null while PENDING or on ERROR
    String errorReason,      // always a fixed sanitized category string — never e.getMessage()
    SubTaskStatus status
) {}

public enum SubTaskStatus { PENDING, COMPLETE, ERROR }
```

### `RoundMemo`

```java
public record RoundMemo(String agentRole, int round, String content) {}
```

### `ReviewState` — explicit constructor change

Add two fields. The compact constructor must perform defensive copies for all four fields:

```java
public record ReviewState(
    Map<String, ReviewPoint> points,
    List<FlagEntry> humanFlags,
    List<RoundMemo> memos,
    Map<String, SubTaskFinding> subTaskFindings
) {
    public ReviewState {
        points           = Collections.unmodifiableMap(new LinkedHashMap<>(points));
        humanFlags       = Collections.unmodifiableList(List.copyOf(humanFlags));
        memos            = Collections.unmodifiableList(List.copyOf(memos));
        subTaskFindings  = Collections.unmodifiableMap(new LinkedHashMap<>(subTaskFindings));
    }
}
```

`LinkedHashMap` preserves insertion order for both `points` and `subTaskFindings`. Every `apply()` call that returns a new `ReviewState` must pass all four fields, carrying unchanged collections forward alongside any new entries.

### `SubAgentProvider` SPI

```java
public interface SubAgentProvider {
    /**
     * Invoke an LLM sub-agent and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread.
     * CDI async observer thread is correct; this method should never be called from Vert.x.
     */
    String analyse(SubAgentTask task);
}

// Field order: taskType, systemPrompt, assembledInput — consistent with LLM API convention (system before user)
public record SubAgentTask(
    SubTaskType taskType,
    String systemPrompt,    // per-task-type persona, enforces fresh-context invariant
    String assembledInput   // minimally scoped context, assembled by SubAgentOrchestrator
) {}
```

### `DebateSession` — add `specPath`

```java
public record DebateSession(
    UUID channelId,
    String debateSessionId,
    String channelName,
    String revInstanceId,
    String impInstanceId,
    String specPath           // absolute path to the spec being debated; may be null if not provided
) {}
```

### `SummaryRenderer` — exhaustiveness fix (compile-required)

Adding four values to `EntryType` breaks the exhaustive `typeLabel` switch in `SummaryRenderer.render()`. These new types never appear in `ThreadEntry.thread()` (they go into `memos` and `subTaskFindings` instead), but the compiler requires coverage. Add explicit cases with meaningful labels that document the invariant:

```java
String typeLabel = switch (entry.type()) {
    case RAISE           -> "raise";
    case AGREE           -> "agree";
    case COUNTER         -> "counter";
    case DISPUTE         -> "dispute";
    case QUALIFY         -> "qualify";
    case FLAG_HUMAN      -> "flag";
    case DECLINED        -> "declined";
    // These entry types are never stored in ThreadEntry.thread() —
    // they go into ReviewState.memos() and ReviewState.subTaskFindings() respectively.
    // These cases exist only for compiler exhaustiveness.
    case MEMO            -> "memo";
    case SUB_TASK_REQUEST -> "sub-task-request";
    case SUB_TASK_FINDING -> "sub-task-finding";
    case SUB_TASK_ERROR   -> "sub-task-error";
};
```

---

## MCP Tool Surface (`runtime` module — `DebateMcpTools`)

### `post_memo`

```
post_memo(debateSessionId, agentRole, round, content)
→ {status: "dispatched"}
```

Encoding: `META_SENTINEL + "entryType=MEMO|agent=<role>|round=<n>\n\n<content>"`
Qhorus type: `MessageType.STATUS`

**Why STATUS:** STATUS is the correct Qhorus type for informational entries that create no obligation, expect no reply, and do not close a commitment thread. QUERY would imply a pending response. RESPONSE requires `inReplyTo`. DONE/DECLINE/HANDOFF close or redirect commitments. STATUS is the catch-all for audit-trail entries that are neither commands nor responses — the correct type for a reasoning memo.

Agents call this after their last raise/respond of a round to externalise their working model: patterns noticed, why concessions feel solid vs provisional, concerns not yet formally raised. Memos are not citable by other entries but inform a cold agent resuming from the channel history.

### `request_subagent`

```
request_subagent(debateSessionId, agentRole, taskType, pointId?, customInput?)
→ {subTaskId: "<uuid>", status: "dispatched"}
```

- `pointId`: the debate point this task relates to. Null for `NEUTRAL_SUMMARY` and `CUSTOM`. Optional for `DEEP_ANALYSIS`.
- `customInput`: for `CUSTOM` — the full context the sub-agent receives. For `CONSISTENCY_CHECK` — the proposed resolution text to check against prior agreements. Null for all other task types.
- `subTaskId`: fresh UUID generated at tool call time; becomes `correlationId` on the channel message so the projection matches REQUEST → FINDING by this ID.

Encoding: `META_SENTINEL + "entryType=SUB_TASK_REQUEST|agent=<role>|taskType=<EnumName>|subTaskId=<uuid>[|pointId=<id>]\n\n[customInput or empty]"`
Qhorus type: `MessageType.QUERY`

Both tools wrap their dispatch call in try/catch and return `"error: ..."` on failure per `mcp-tool-exception-catch-all.md`. The orchestrator's async execution is fire-and-forget from the tool's perspective — failures surface as `SUB_TASK_ERROR` entries in the channel. The main agent can continue raising/responding while the sub-agent runs. `get_debate_summary` shows `⏳ PENDING` until the finding arrives.

---

## SubAgentOrchestrator (`runtime` module)

### `SubAgentRequest` CDI event record

```java
public record SubAgentRequest(
    UUID channelId,
    String subTaskId,
    SubTaskType taskType,
    String requestingAgent,
    String pointId,      // null for NEUTRAL_SUMMARY, CUSTOM; optional for DEEP_ANALYSIS
    String customInput,  // null unless CUSTOM or CONSISTENCY_CHECK
    String specPath      // from DebateSession; may be null if not provided at session start
) {}
```

### `SUBAGENT_INSTANCE_ID` registration

`SubAgentOrchestrator` registers a shared Qhorus sender instance at startup. `InstanceService.register()` is an **upsert** — confirmed from source: it calls `findByInstanceId()`, creates if absent, updates description and status if present. Calling `@PostConstruct` again on restart is safe with no prior deregister required. This is the same pattern used by `DraftHouseMcpTools.registerHumanInstance()`.

```java
static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

@PostConstruct
void registerSubAgentInstance() {
    instanceService.register(SUBAGENT_INSTANCE_ID,
            "DraftHouse sub-agent (focused analysis)",
            List.of("document-debate-subagent"));
}
```

All `SUB_TASK_FINDING` and `SUB_TASK_ERROR` messages are dispatched with `sender = SUBAGENT_INSTANCE_ID`. Qhorus validates sender instances at dispatch time — this registration must complete before any finding is dispatched.

### §Backend Evolution — DebateChannelBackend

The June 7 design described `DebateChannelBackend` as a "registration fence" whose `post()` was a no-op. This spec evolves that contract:

**Retained:** the fence role — the backend's presence still prevents `ReviewerChannelBackendFactory` from attaching an LLM backend to debate channels.

**Added:** `post()` now parses the META sentinel from `OutboundMessage.content()` and fires a `SubAgentRequest` CDI event when `entryType == SUB_TASK_REQUEST`. All other entry types are discarded (no-op, same as before).

**Architectural choice:** the CDI event is fired from `ChannelBackend.post()` rather than directly from `DebateMcpTools.request_subagent()`. This keeps orchestration in the Qhorus message-arrival layer — consistent with how `ReviewerChannelBackend` reacts to QUERY messages — and ensures that any `SUB_TASK_REQUEST` entry in the channel (regardless of origin) triggers dispatch. The alternative (firing from the MCP tool) would couple orchestration to the tool layer and bypass the channel as the integration contract.

The backend's SPI signature is `post(ChannelRef channel, OutboundMessage message)` — `OutboundMessage` is the Qhorus type delivered to backends; `MessageView` is the type used by channel projections. These are different Qhorus types serving different purposes.

```java
@Override
public void post(ChannelRef channel, OutboundMessage message) {
    Map<String, String> meta = parseMeta(message.content());
    if (!"SUB_TASK_REQUEST".equals(meta.get("entryType"))) return;

    DebateSession session = registry.find(channel.id()).orElse(null);
    if (session == null) {
        LOG.warning("SUB_TASK_REQUEST on channel " + channel.id() + " — no session, dropped");
        return;
    }
    subAgentEvent.fireAsync(buildSubAgentRequest(channel, message, meta, session));
}
```

### `SubAgentOrchestrator`

```java
@ApplicationScoped
public class SubAgentOrchestrator {

    @Inject SubAgentProvider subAgentProvider;
    @Inject MessageService messageService;
    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DebateSessionRegistry registry;
    @Inject InstanceService instanceService;

    @PostConstruct
    void registerSubAgentInstance() { /* see above */ }

    @ObservesAsync
    public void onSubAgentRequest(SubAgentRequest event) {
        DebateSession session = registry.find(event.channelId()).orElse(null);
        if (session == null) {
            LOG.warning("SubAgentOrchestrator: no session for " + event.channelId() + " — dropped");
            return;
        }
        try {
            SubAgentTask task = assembleTask(event, session);
            String finding = subAgentProvider.analyse(task);
            dispatchFinding(event, finding);
        } catch (Exception e) {
            LOG.warning("SubAgentOrchestrator: sub-agent failed [subTaskId=" + event.subTaskId()
                    + ", type=" + event.taskType() + "]: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
            dispatchError(event);  // sanitized — never passes e.getMessage() to the ledger
        }
    }
}
```

### §Concurrency

Multiple `request_subagent` calls may be in flight concurrently on the same session. Each gets a distinct `subTaskId`; `onSubAgentRequest()` executes concurrently on the CDI async thread pool. `SubAgentOrchestrator` has **no shared mutable state** — all fields are immutable injected collaborators. `ProjectionService.project()` produces a snapshot of state at the time of the call; each invocation reads its own snapshot independently. The Qhorus channel serialises `apply()` calls within the projection fold. No synchronization is required in `SubAgentOrchestrator`. This is safe by design.

### Input assembly — `assembleTask()`

**Invariant enforced across all task types:** no prior round entries are included unless the task type explicitly requires them. This is the architectural enforcement of "deliberately minimal context."

**Failure modes:** if required inputs are absent, `assembleTask()` throws `IllegalArgumentException` with a descriptive message. The outer `try/catch` in `onSubAgentRequest()` catches this and routes to `dispatchError()` — the SUB_TASK_ERROR entry documents the failure in the channel.

| Task type | Required inputs | Throws if missing | Assembled input |
|---|---|---|---|
| `VERIFY` | `pointId` (must exist in projected state); `specPath` (must not be null) | IAE on null pointId, missing point, or null specPath | Claim text (first thread entry of the point) + full spec content |
| `ARBITRATE` | `pointId` (must exist in projected state); point must have at least one response entry | IAE on null pointId or missing point | Original raise content + last DISPUTE/QUALIFY/COUNTER entry (see below) |
| `DEEP_ANALYSIS` | `specPath` (must not be null) | IAE on null specPath | Full spec content + location hint from point's classification (or "(no section indicated)" if pointId is null or location is absent — not an error) |
| `CONSISTENCY_CHECK` | `customInput` (proposed resolution text) | IAE on null customInput | Compact list of AGREED points + customInput |
| `NEUTRAL_SUMMARY` | None | Never throws | All points and thread entries from the current projected state |
| `CUSTOM` | `customInput` | IAE on null customInput | `customInput` verbatim |

**ARBITRATE — precise "most recent response" definition:**

"The most recent response" is the **last `ThreadEntry` in `point.thread()` whose type is one of `{DISPUTE, QUALIFY, COUNTER}`**. Not `thread.getLast()` (which may be FLAG_HUMAN); a filter-then-reduce is required:

```java
String latestResponse = point.thread().stream()
    .filter(e -> e.type() == EntryType.DISPUTE
             || e.type() == EntryType.QUALIFY
             || e.type() == EntryType.COUNTER)
    .reduce((a, b) -> b)  // last matching entry
    .map(ThreadEntry::content)
    .orElse("(no response yet)");
```

**CONSISTENCY_CHECK — compact list format:**

One numbered item per AGREED point, containing only `pointId` and the raise entry content (first `ThreadEntry` in the thread). No thread history, no classification metadata:

```
1. [pt-1] The API contract is ambiguous — no timeout stated.
2. [pt-3] Error handling absent in §4.1.
```

Agreed points are identified by `point.currentStatus() == ReviewStatus.AGREED` in the projected state.

**System prompts by task type:**

| Task type | System prompt |
|---|---|
| `VERIFY` | "You are a spec verifier. You have no knowledge of this debate's prior rounds. Determine only whether this claim is supported by the spec. Be precise." |
| `ARBITRATE` | "You are a neutral arbitrator. You have not seen this debate before. Assess these two positions on their merits only. Do not favour either side." |
| `DEEP_ANALYSIS` | "You are a spec analyst reading this spec with fresh eyes. Focus on the indicated section. Identify issues." |
| `CONSISTENCY_CHECK` | "You have no memory of this debate. Determine only whether the proposed resolution contradicts any of these prior agreements." |
| `NEUTRAL_SUMMARY` | "Summarise this debate neutrally. You have not participated in it." |
| `CUSTOM` | "You are a focused analyst. Answer only the question posed. You have no knowledge of the broader debate." |

### Error dispatch — sanitization rule

**`dispatchError()` takes no `reason` parameter.** It always dispatches a fixed, sanitized category string. The actual exception is logged (with class name + message) at WARN level before calling `dispatchError()`. Exception messages must never reach `MessageService.dispatch()` content — the Qhorus ledger is tamper-evident and immutable. See `qhorus-dispatch-exception-sanitization.md`.

```java
private void dispatchError(SubAgentRequest event) {
    Long inReplyTo = messageService.findByCorrelationId(event.subTaskId())
            .map(m -> m.id).orElse(null);
    String encoded = DebateProtocol.META_SENTINEL
            + "entryType=SUB_TASK_ERROR|subTaskId=" + event.subTaskId()
            + "|taskType=" + event.taskType().name()
            + "|agent=" + event.requestingAgent()
            + "\n\nSub-agent analysis failed.";
    messageService.dispatch(MessageDispatch.builder()
            .channelId(event.channelId())
            .sender(SUBAGENT_INSTANCE_ID)
            .type(MessageType.STATUS)
            .content(encoded)
            .correlationId(event.subTaskId())
            .inReplyTo(inReplyTo)
            .actorType(ActorType.AGENT)
            .build());
}
```

Note `event.taskType().name()` — enum name, consistent with the encoding standard.

### Finding dispatch

`SUB_TASK_FINDING` message:
```
META_SENTINEL + "entryType=SUB_TASK_FINDING|subTaskId=<id>|taskType=<EnumName>|agent=<requestingAgent>[|pointId=<id>]\n\n<finding>"
```
`MessageType.RESPONSE`, `correlationId = subTaskId`, `inReplyTo = id of the SUB_TASK_REQUEST message` (via `messageService.findByCorrelationId(subTaskId)`).

---

## `SubAgentProvider` Implementations

### `LangChain4jSubAgentProvider` (`runtime`, `@DefaultBean @ApplicationScoped`)

Injects `ChatModel` (LangChain4j, already present via `DocumentReviewer`). Builds `SystemMessage` + `UserMessage` from the task fields and calls `chatModel.generate(messages)`. Returns the response text. CI-friendly — no Claude CLI required.

### `ClaudeSubAgentProvider` (`claude-agent`, `@ApplicationScoped`)

Injects `AgentProvider` (platform SPI). Displaces LangChain4j default by classpath presence. **Gated on platform#55** — stub in `claude-agent/` until that ships.

CDI priority: `LangChain4jSubAgentProvider @DefaultBean` < `ClaudeSubAgentProvider @ApplicationScoped`. Consistent with `ai-agent-provider-cdi-priority.md`.

DraftHouse sub-agents return free-text findings. A structured `parseResult()` step (for typed result objects) is a concern for the extracted `ChannelAgentDispatcher` pattern (→ #42), not this implementation.

---

## Projection — `DebateChannelProjection` (`runtime` module)

### Encoding standard applied to `apply()`

Replace the string switch with `EntryType.valueOf()` + enum switch. This is type-safe and compiler-exhaustive:

```java
@Override
public ReviewState apply(ReviewState state, MessageView message) {
    Map<String, String> meta = parseMeta(message.content());
    String entryTypeStr = meta.get("entryType");
    if (entryTypeStr == null) return state;

    EntryType entryType;
    try {
        entryType = EntryType.valueOf(entryTypeStr);
    } catch (IllegalArgumentException e) {
        LOG.warning("DebateChannelProjection: unknown entryType '" + entryTypeStr + "' — discarded");
        return state;
    }

    return switch (entryType) {
        case RAISE            -> handleRaise(state, message, meta);
        case AGREE            -> handleAgree(state, message, meta);
        case COUNTER          -> handleCounter(state, message, meta);
        case DISPUTE          -> handleDispute(state, message, meta);
        case QUALIFY          -> handleQualify(state, message, meta);
        case FLAG_HUMAN       -> handleFlagHuman(state, message, meta);
        case DECLINED         -> state;  // projection-only status; never encoded in a message
        case MEMO             -> handleMemo(state, message, meta);
        case SUB_TASK_REQUEST -> handleSubTaskRequest(state, message, meta);
        case SUB_TASK_FINDING -> handleSubTaskFinding(state, message, meta);
        case SUB_TASK_ERROR   -> handleSubTaskError(state, message, meta);
    };
}
```

### `identity()`

```java
@Override
public ReviewState identity() {
    return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
}
```

### New dispatch cases (conceptual — `ReviewState` is immutable; handlers build new instances)

```
MEMO             → add RoundMemo(agentRole, round, bodyContent) to memos; all other fields carried forward
SUB_TASK_REQUEST → add SubTaskFinding(subTaskId, taskType, requestingAgent, pointId, null, null, PENDING)
SUB_TASK_FINDING → update SubTaskFinding(subTaskId) → COMPLETE with finding text
SUB_TASK_ERROR   → update SubTaskFinding(subTaskId) → ERROR with errorReason = "Sub-agent analysis failed."
```

For `SUB_TASK_FINDING` and `SUB_TASK_ERROR`: if a finding with that `subTaskId` does not yet exist in state (finding arrived before request was projected), create it at COMPLETE/ERROR status directly. The projection is a left-fold over an ordered message stream; out-of-order arrival is possible if the sub-agent completes before the request message is projected.

---

## SummaryRenderer Rendering Algorithm (`api` module)

`SummaryRenderer.render(ReviewState state)` must be updated to iterate the new state fields. The rendering algorithm in full:

```
1. Header: "# Review Summary\n**Updated:** <instant>\n\n---\n\n"

2. For each ReviewPoint in state.points().values():
   a. Status marker + point header (unchanged from existing logic)
   b. For each ThreadEntry in point.thread():
      render "> **{agent} ({typeLabel}):** {content}"
   c. For each SubTaskFinding in state.subTaskFindings().values()
      where finding.pointId() != null && finding.pointId().equals(point.id()):
        render finding with provenance marker (PENDING / COMPLETE / ERROR)
   d. Render "\n---\n\n" separator

3. If state.humanFlags() not empty:
   render "⚑ **Human review needed:**" section (unchanged)

4. If any SubTaskFinding has null pointId (NEUTRAL_SUMMARY or CUSTOM):
   render "\n---\n\n**Sub-task findings**\n\n"
   For each such finding in subTaskId insertion order:
     render finding with provenance marker

5. If state.memos() not empty:
   render "\n---\n\n**Agent Memos**\n\n"
   For each RoundMemo in insertion order:
     render "**{agentRole} memo — Round {round}:** {content}\n\n"
```

**Finding provenance rendering:**

```
PENDING:  "  ⏳ **{taskType}** pending...\n"
COMPLETE: "  ⊕ **{taskType}** _(fresh context — no prior round knowledge)_\n  {finding}\n"
ERROR:    "  ✗ **{taskType}** failed: {errorReason}\n"
```

The `⊕` marker and `_(fresh context — no prior round knowledge)_` caption make provenance visible to the human observer. Main-agent thread entries carry no marker — implicitly accumulated-context.

---

## Testing

### §SubAgentOrchestratorTest — unit test, no CDI container

`@ObservesAsync` is a CDI registration marker, not a runtime guard. Tests create `SubAgentOrchestrator` via a package-private constructor and call `onSubAgentRequest()` **directly as a Java method**, bypassing CDI entirely. This makes tests synchronous, fast, and container-free. No event firing, no Quarkus test harness.

**Tests to write:**

- **VERIFY — throws on null specPath:** `assembleTask()` throws `IllegalArgumentException`; `dispatchError()` is called; dispatched content is `"Sub-agent analysis failed."`, never the exception message.
- **VERIFY — throws on missing pointId in state:** same error path.
- **ARBITRATE — most-recent filter:** thread has RAISE → QUALIFY → FLAG_HUMAN; assembled input uses the QUALIFY content, not FLAG_HUMAN.
- **ARBITRATE — multiple responses:** thread has RAISE → DISPUTE → COUNTER → QUALIFY; assembled input uses QUALIFY (the last of DISPUTE/QUALIFY/COUNTER).
- **CONSISTENCY_CHECK — includes only AGREED points:** state has one AGREED and one OPEN point; only AGREED appears in input; OPEN does not.
- **CUSTOM — null customInput throws:** `assembleTask()` throws `IllegalArgumentException`.
- **Session not found mid-flight:** registry returns empty; neither `subAgentProvider` nor `messageService` is called (silent drop).
- **Error body is fixed string:** provider throws; dispatched content body is exactly `"Sub-agent analysis failed."` and does not contain the exception message.

### `DebateChannelProjectionTest` additions

- MEMO entry → appears in `state.memos()`; `state.points()` unchanged
- SUB_TASK_REQUEST → `state.subTaskFindings()` contains entry with status PENDING
- SUB_TASK_FINDING for same subTaskId → status COMPLETE, `finding` populated
- SUB_TASK_ERROR → status ERROR, `errorReason` = `"Sub-agent analysis failed."`
- Finding with null pointId (NEUTRAL_SUMMARY) → `finding.pointId()` is null
- Out-of-order: FINDING before REQUEST → finding created at COMPLETE status directly
- Existing apply() tests still pass — encoding change from lowercase strings to `EntryType.valueOf()` does not break test helpers that already use the `msg(type, correlationId, metaHeader, body)` pattern, since `parseMeta()` is string-key-based

**Note on test helper compatibility:** `DebateChannelProjectionTest.msg()` encodes metadata as `"entryType=raise|agent=REV|round=1|..."` strings in the META header. After the encoding change, all test helper calls must use uppercase: `"entryType=RAISE|agent=REV|round=1|..."`. This is a required update to the test class.

### `DebateChannelBackendTest` additions

- `SUB_TASK_REQUEST` message with valid session → `SubAgentRequest` CDI event fired with correct fields
- `SUB_TASK_REQUEST` message with no session → event not fired; log warning; no exception
- `RAISE` message → no event fired
- Any non-SUB_TASK_REQUEST entry type → no event fired

### E2E — `SubAgentE2ETest`

`MockSubAgentProvider @Alternative @Priority(1)` returns deterministic strings. Full lifecycle:
- Start debate, raise point, respond, `request_subagent(ARBITRATE, pointId)` → `get_debate_summary` shows `⏳ PENDING`; after mock returns, shows `⊕` finding
- `post_memo` → memo appears in summary
- `CUSTOM` sub-task → finding in standalone Sub-task findings section
- Concurrent sub-tasks: two `request_subagent` calls before either finding arrives → both appear in summary

---

## Module Summary

| Module | Changes |
|---|---|
| `api/` | `EntryType`: add MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR. New types: `SubTaskType`, `SubTaskStatus`, `SubTaskFinding` (with `pointId`), `RoundMemo`, `SubAgentProvider` SPI, `SubAgentTask` (field order: taskType, systemPrompt, assembledInput). `ReviewState`: add `memos` and `subTaskFindings` fields; update compact constructor to defensive-copy all four fields. `SummaryRenderer`: exhaustiveness fix for new EntryType values; new rendering logic for memos and sub-task findings per §Rendering Algorithm. `DebateSession`: add `specPath` field (reversal from June 7 — see §specPath Reversal). |
| `runtime/` | `DebateMcpTools`: update all `entryType=lowercase` encodings to `EntryType.name()` (RAISE, AGREE, DISPUTE, QUALIFY, COUNTER, FLAG_HUMAN); add `post_memo`, `request_subagent` tools; update `startDebate()` to store `specPath` in session. `DebateChannelBackend`: evolve `post()` from no-op to CDI event dispatch for SUB_TASK_REQUEST; inject `Event<SubAgentRequest>` and `DebateSessionRegistry`. `DebateChannelProjection`: replace string switch with `EntryType.valueOf()` + enum switch; update `identity()` to 4-field ReviewState; add MEMO/SUB_TASK_* handlers; update all `new ReviewState(...)` calls to 4-field form. New: `SubAgentRequest` CDI event record, `SubAgentOrchestrator` (with `@PostConstruct` instance registration, `assembleTask()`, sanitized `dispatchError()`), `LangChain4jSubAgentProvider @DefaultBean`. |
| `claude-agent/` | `ClaudeSubAgentProvider @ApplicationScoped` stub (gated on platform#55). |
| Tests | `DebateChannelProjectionTest`: update all test helper `entryType` strings to uppercase; add new cases. `DebateChannelBackendTest` (was `DebateChannelBackendFactoryTest`): add SUB_TASK_REQUEST dispatch tests. `SubAgentOrchestratorTest`: new (unit, no container). `SubAgentE2ETest`: new, with `MockSubAgentProvider`. |

---

## Deferred Issues

| Issue | What |
|---|---|
| #40 | Restart-from-round-N semantics — branching the channel history and what is actually lost |
| #41 | Threshold-based auto-reset safety valve + context meter UI |
| #42 | Channel-Reactive Agent pattern — extraction to patterns repo (see devtown feedback for extraction concerns) |
