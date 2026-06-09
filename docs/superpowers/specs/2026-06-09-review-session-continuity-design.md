# Review Session Continuity — Sub-Agent Architecture
**Date:** 2026-06-09
**Status:** Approved
**Issue:** #26
**Deferred:** #40 (restart-from-N), #41 (threshold auto-reset + context meter)
**Supersedes (partial):** 2026-06-07 debate-channel-design.md §DebateSession (specPath reversal — see below)

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

**This spec establishes `EntryType.name()` as the canonical encoding for `entryType` in the META sentinel header.** All encoding is uppercase:

| Written by | Encoded string | Decoded by |
|---|---|---|
| `DebateMcpTools` | `entryType=RAISE`, `entryType=AGREE`, … | `DebateChannelProjection` |
| `ChannelAgentDispatcher` | `entryType=SUB_TASK_FINDING`, `entryType=SUB_TASK_ERROR` | `DebateChannelProjection` |

**Migration from June 7 encoding:** the June 7 debate-channel spec encoded entry types as lowercase (`raise`, `agree`, `flag-human`). This spec replaces all lowercase encoded strings with their enum name equivalents (`RAISE`, `AGREE`, `FLAG_HUMAN`). Since there are no production deployments, in-flight channel messages with old lowercase encoding will not be re-projected — acceptable.

**Files that must adopt this standard:**
- `DebateMcpTools.java` — all `entryType=raise|…` → `entryType=RAISE|…`
- `DebateChannelProjection.apply()` — string switch replaced with `EntryType.valueOf()` + enum switch
- `DebateProtocol.java` — add `parseMeta()` and `bodyContent()` as public static utilities (moved from `DebateChannelProjection` private methods; required by backends and handlers)

**Encoding rule going forward:** always use `entryType=<EntryType.name()>`. The enum name is the wire format.

---

## specPath Reversal from June 7 Design

The June 7 debate-channel spec explicitly stated: *"`specPath` is NOT stored in `DebateSession`. It is echoed back in the `start_debate` response JSON from the input parameter directly."*

**This spec reverses that decision.** `specPath` must be stored in `DebateSession`. The `ChannelAgentDispatcher` dispatches asynchronously after the tool call returns; it needs `specPath` for `VERIFY` and `DEEP_ANALYSIS` assembly. The session is the only available state at that point. `DebateMcpTools.startDebate()` must store `specPath` in the session rather than only echoing it.

---

## Architecture

```
DebateMcpTools
  ├── post_memo()         → MEMO entry → debate channel (MessageType.STATUS)
  └── request_subagent() → SUB_TASK_REQUEST entry → debate channel (MessageType.QUERY)
                                ↓
                      DebateChannelBackend.post(ChannelRef, OutboundMessage)
                      [parses META; fires ChannelAgentRequest CDI event for SUB_TASK_REQUEST]
                                ↓ event.fireAsync(ChannelAgentRequest)
                      ChannelAgentDispatcher @ObservesAsync
                                ├── find handler: @Any Instance<ChannelAgentHandler> — first handles()
                                ├── handler.prepareTask(request) — throws IAE on missing inputs
                                ├── debateAgentProvider.analyse(task)
                                ├── handler.buildResponse(channelId, senderId, llmOutput, request)
                                │     — throws AgentResultParseException on parse failure
                                └── MessageService.dispatch(result)
                                       or dispatchError / dispatchParseError on failure
                                              ↓
                                  DebateChannelProjection
                                  (renders findings with provenance label)

Handler beans (@ApplicationScoped, extend AbstractDebateSubAgentHandler):
  VerifyHandler, ArbitrateHandler, DeepAnalysisHandler,
  ConsistencyCheckHandler, NeutralSummaryHandler, CustomHandler
```

Handler beans have non-overlapping `handles()` predicates (one per `SubTaskType`). No `@Priority` ordering needed for DraftHouse — documented in #42 as a future extraction concern.

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

`DECLINED` is projection-only; no message is ever encoded with `entryType=DECLINED`.

### `SubTaskType`

```java
public enum SubTaskType {
    VERIFY, ARBITRATE, DEEP_ANALYSIS, CONSISTENCY_CHECK, NEUTRAL_SUMMARY, CUSTOM
}
```

DraftHouse vocabulary — not part of the extracted `ChannelAgentHandler` pattern (see #42).

### `AgentTask`

```java
// Field order: systemPrompt before assembledInput — consistent with LLM API convention
public record AgentTask(
    String systemPrompt,    // sub-agent persona, enforces fresh-context invariant
    String assembledInput   // minimally scoped context assembled by handler.prepareTask()
) {}
```

Used by `DebateAgentProvider.analyse()`. Named `AgentTask` (pattern vocabulary) rather than `SubAgentTask` (DraftHouse-specific). Lives in `api/` so the `claude-agent/` optional module can depend on it without pulling in `runtime/`.

### `DebateAgentProvider` SPI

```java
// api/ — no Qhorus or Quarkus dependency
public interface DebateAgentProvider {
    /**
     * Invoke an LLM and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread (CDI async observer is correct).
     */
    String analyse(AgentTask task);
}
```

Named `DebateAgentProvider` per the ARC42 architecture record. Implementations:
- `LangChain4jDebateAgentProvider @DefaultBean @ApplicationScoped` in `runtime/`
- `ClaudeAgentSdkDebateAgentProvider @ApplicationScoped` in `claude-agent/` (gated on platform#55)

### `SubTaskFinding`

```java
public record SubTaskFinding(
    String subTaskId,
    SubTaskType taskType,
    String requestingAgent,  // "REV" or "IMP"
    String pointId,          // null for NEUTRAL_SUMMARY and CUSTOM
    String finding,          // null while PENDING or on ERROR
    String errorReason,      // fixed sanitized string — never e.getMessage()
    SubTaskStatus status
) {}

public enum SubTaskStatus { PENDING, COMPLETE, ERROR }
```

### `RoundMemo`

```java
public record RoundMemo(String agentRole, int round, String content) {}
```

### `ReviewState` — explicit constructor change

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

### `DebateSession` — add `specPath`

```java
public record DebateSession(
    UUID channelId, String debateSessionId, String channelName,
    String revInstanceId, String impInstanceId,
    String specPath   // absolute path to spec; null if not provided at session start
) {}
```

### `SummaryRenderer` — exhaustiveness fix

Adding four values to `EntryType` breaks the exhaustive `typeLabel` switch. These types never appear in `ThreadEntry.thread()` but the compiler requires coverage:

```java
case MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR -> // invariant: never in thread
    throw new IllegalStateException("entry type " + entry.type() + " must not appear in ThreadEntry");
```

Using `throw` rather than a silent `""` makes the invariant machine-checked: if a bug causes these types to appear in a thread, it fails loudly rather than silently rendering an empty label.

---

## Pattern Types (`runtime` module — extract to patterns repo when devtown adopts)

### `ChannelAgentRequest`

```java
public record ChannelAgentRequest(
    UUID channelId,
    String correlationId,     // subTaskId — ID of the triggering message
    OutboundMessage message   // the full trigger message; handlers parse META from content
) {}
```

The CDI event type fired by `DebateChannelBackend.post()` and observed by `ChannelAgentDispatcher`.

### `AgentResultParseException`

```java
public class AgentResultParseException extends RuntimeException {
    public AgentResultParseException(String message) { super(message); }
    public AgentResultParseException(String message, Throwable cause) { super(message, cause); }
}
```

Thrown by `handler.buildResponse()` when LLM output cannot be parsed into the expected format. Caught separately from `RuntimeException` by `ChannelAgentDispatcher` — routes to a distinct fixed error string.

### `ChannelAgentHandler`

```java
// SPI — one implementation per task type / capability.
// Handler beans must have non-overlapping handles() predicates; first-match routing.
public interface ChannelAgentHandler {

    /** Return true if this handler should process the request. Predicates must not overlap. */
    boolean handles(ChannelAgentRequest request);

    /**
     * Assemble focused LLM input. Must be deliberately minimal — no extraneous context.
     * @throws IllegalArgumentException if required inputs are absent (missing specPath,
     *   unresolvable pointId, null customInput for task types that require it, etc.).
     *   The dispatcher routes this to the error path.
     */
    AgentTask prepareTask(ChannelAgentRequest request);

    /**
     * Build the Qhorus MessageDispatch from the LLM output.
     * Handles structured result parsing where needed.
     * @throws AgentResultParseException if LLM output cannot be parsed into the expected format.
     *   The dispatcher routes this to a distinct parse-error path.
     */
    MessageDispatch buildResponse(UUID channelId, String senderId,
                                  String llmOutput, ChannelAgentRequest trigger)
            throws AgentResultParseException;
}
```

### `ChannelAgentDispatcher`

```java
static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

@ApplicationScoped
public class ChannelAgentDispatcher {

    @Inject @Any Instance<ChannelAgentHandler> handlers;
    @Inject DebateAgentProvider debateAgentProvider;
    @Inject MessageService messageService;
    @Inject InstanceService instanceService;

    @PostConstruct
    void registerSenderInstance() {
        // InstanceService.register() is an upsert — idempotent on restart, no prior deregister needed.
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                List.of("document-debate-subagent"));
    }

    @ObservesAsync
    public void onChannelAgentRequest(ChannelAgentRequest request) {
        ChannelAgentHandler handler = handlers.stream()
                .filter(h -> h.handles(request))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            LOG.warning("ChannelAgentDispatcher: no handler for request on channel "
                    + request.channelId() + " — dropped");
            dispatchError(request, "No handler matched this sub-task request.");
            return;
        }

        try {
            AgentTask task = handler.prepareTask(request);
            String llmOutput = debateAgentProvider.analyse(task);
            try {
                MessageDispatch response = handler.buildResponse(
                        request.channelId(), SUBAGENT_INSTANCE_ID, llmOutput, request);
                messageService.dispatch(response);
            } catch (AgentResultParseException e) {
                LOG.warning("ChannelAgentDispatcher: parse failure [" + request.correlationId()
                        + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                dispatchParseError(request);
            }
        } catch (Exception e) {
            LOG.warning("ChannelAgentDispatcher: sub-agent failed [" + request.correlationId()
                    + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            dispatchError(request, "Sub-agent analysis failed.");
        }
    }

    private void dispatchError(ChannelAgentRequest request, String fixedReason) {
        // Never pass e.getMessage() here — see qhorus-dispatch-exception-sanitization.md
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_ERROR|subTaskId=" + request.correlationId()
                + "|taskType=" + meta.getOrDefault("taskType", "UNKNOWN")
                + "|agent=" + meta.getOrDefault("agent", "UNKNOWN")
                + "\n\n" + fixedReason;
        Long inReplyTo = messageService.findByCorrelationId(request.correlationId())
                .map(m -> m.id).orElse(null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(request.channelId())
                .sender(SUBAGENT_INSTANCE_ID)
                .type(MessageType.STATUS)
                .content(encoded)
                .correlationId(request.correlationId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());
    }

    private void dispatchParseError(ChannelAgentRequest request) {
        dispatchError(request, "Sub-agent returned an unreadable result.");
    }
}
```

Two distinct error paths, two distinct fixed strings: "Sub-agent analysis failed." (LLM invocation or input assembly failure) vs "Sub-agent returned an unreadable result." (parse failure). Both are fixed strings — never exception messages.

---

## DraftHouse Handler Hierarchy (`runtime` module)

### `AbstractDebateSubAgentHandler`

Abstract base class for all six DraftHouse debate handlers. Implements `handles()` and `buildResponse()` — both are identical across all handlers; only `taskType()` and `prepareTask()` vary.

```java
abstract class AbstractDebateSubAgentHandler implements ChannelAgentHandler {

    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;

    abstract SubTaskType taskType();

    @Override
    public final boolean handles(ChannelAgentRequest request) {
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        try {
            return SubTaskType.valueOf(meta.getOrDefault("taskType", "")) == taskType();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public final MessageDispatch buildResponse(UUID channelId, String senderId,
                                               String llmOutput, ChannelAgentRequest trigger)
            throws AgentResultParseException {
        Map<String, String> meta = DebateProtocol.parseMeta(trigger.message().content());
        String subTaskId = meta.getOrDefault("subTaskId", trigger.correlationId());
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        String pointId = meta.get("pointId");
        Long inReplyTo = messageService.findByCorrelationId(subTaskId).map(m -> m.id).orElse(null);
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_FINDING|subTaskId=" + subTaskId
                + "|taskType=" + taskType().name()
                + "|agent=" + agent
                + (pointId != null ? "|pointId=" + pointId : "")
                + "\n\n" + llmOutput;
        return MessageDispatch.builder()
                .channelId(channelId).sender(senderId)
                .type(MessageType.RESPONSE).content(encoded)
                .correlationId(subTaskId).inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT).build();
    }

    // Shared helpers available to all handler subclasses

    protected ReviewState currentState(UUID channelId) {
        return projectionService.project(channelId, debateProjection).state();
    }

    protected DebateSession requireSession(UUID channelId) {
        return registry.find(channelId).orElseThrow(() ->
            new IllegalArgumentException("No active debate session for channel " + channelId));
    }

    protected String requireSpecPath(DebateSession session, SubTaskType type) {
        if (session.specPath() == null || session.specPath().isBlank())
            throw new IllegalArgumentException(type + " requires specPath — start_debate must receive a spec path");
        return session.specPath();
    }

    protected String requirePointRaiseContent(ReviewState state, String pointId, SubTaskType type) {
        if (pointId == null) throw new IllegalArgumentException(type + " requires a pointId");
        ReviewPoint p = state.points().get(pointId);
        if (p == null) throw new IllegalArgumentException(type + ": pointId " + pointId + " not found in projected state");
        return p.thread().get(0).content();
    }

    protected String readSpec(String specPath) {
        try { return Files.readString(Path.of(specPath)); }
        catch (IOException e) {
            LOG.warning("Could not read spec at " + specPath + ": " + e.getMessage());
            return "(spec file could not be read)";
        }
    }
}
```

### Concrete handler specifications

Each handler is `@ApplicationScoped` and extends `AbstractDebateSubAgentHandler`. It implements only `taskType()` and `prepareTask()`.

| Handler | `taskType()` | `prepareTask()` — assembled input | Failure conditions |
|---|---|---|---|
| `VerifyHandler` | `VERIFY` | Claim text (first thread entry of pointId's point) + full spec content | IAE if pointId null, point not in state, or specPath null |
| `ArbitrateHandler` | `ARBITRATE` | Original raise content + **last entry** in thread with type ∈ {DISPUTE, QUALIFY, COUNTER} | IAE if pointId null or point not in state |
| `DeepAnalysisHandler` | `DEEP_ANALYSIS` | Full spec content + location hint from point classification (or "(no section indicated)" if pointId absent — not an error) | IAE if specPath null |
| `ConsistencyCheckHandler` | `CONSISTENCY_CHECK` | Compact numbered list of AGREED points (pointId + raise content only, no thread history) + customInput | IAE if customInput null |
| `NeutralSummaryHandler` | `NEUTRAL_SUMMARY` | All points and thread entries from projected `ReviewState` (v1: full state, not per-round filtered) | Never throws |
| `CustomHandler` | `CUSTOM` | `customInput` verbatim | IAE if customInput null |

**`ArbitrateHandler` — precise "last response" definition:**

```java
String lastResponse = point.thread().stream()
    .filter(e -> e.type() == EntryType.DISPUTE
             || e.type() == EntryType.QUALIFY
             || e.type() == EntryType.COUNTER)
    .reduce((a, b) -> b)   // last matching entry, not thread.getLast()
    .map(ThreadEntry::content)
    .orElse("(no response yet)");
```

**`ConsistencyCheckHandler` — compact list format:**

One numbered item per AGREED point (identified by `ReviewStatus.AGREED`), containing only `pointId` and first thread entry content. No thread history, no classification metadata:

```
1. [pt-1] The API contract is ambiguous — no timeout stated.
2. [pt-3] Error handling absent in §4.1.
```

**System prompts by task type:**

| Handler | System prompt |
|---|---|
| `VerifyHandler` | "You are a spec verifier. You have no knowledge of this debate's prior rounds. Determine only whether this claim is supported by the spec. Be precise." |
| `ArbitrateHandler` | "You are a neutral arbitrator. You have not seen this debate before. Assess these two positions on their merits only. Do not favour either side." |
| `DeepAnalysisHandler` | "You are a spec analyst reading this spec with fresh eyes. Focus on the indicated section. Identify issues." |
| `ConsistencyCheckHandler` | "You have no memory of this debate. Determine only whether the proposed resolution contradicts any of these prior agreements." |
| `NeutralSummaryHandler` | "Summarise this debate neutrally. You have not participated in it." |
| `CustomHandler` | "You are a focused analyst. Answer only the question posed. You have no knowledge of the broader debate." |

---

## MCP Tool Surface (`runtime` module — `DebateMcpTools`)

### `post_memo`

```
post_memo(debateSessionId, agentRole, round, content)
→ {status: "dispatched"}
```

Encoding: `META_SENTINEL + "entryType=MEMO|agent=<role>|round=<n>\n\n<content>"`
Qhorus type: `MessageType.STATUS`

**Why STATUS:** the correct type for informational entries that create no obligation, expect no reply, and do not close a commitment thread. QUERY implies a pending response; RESPONSE requires `inReplyTo`; DONE/DECLINE/HANDOFF close commitments. STATUS is the catch-all for audit-trail entries that are neither commands nor responses.

### `request_subagent`

```
request_subagent(debateSessionId, agentRole, taskType, pointId?, customInput?)
→ {subTaskId: "<uuid>", status: "dispatched"}
```

- `customInput`: for `CUSTOM` — the full context. For `CONSISTENCY_CHECK` — the proposed resolution text. Null for all other types.
- `subTaskId`: fresh UUID at tool call time; `correlationId` on the channel message so projection matches REQUEST → FINDING.

Encoding: `META_SENTINEL + "entryType=SUB_TASK_REQUEST|agent=<role>|taskType=<EnumName>|subTaskId=<uuid>[|pointId=<id>]\n\n[customInput]"`
Qhorus type: `MessageType.QUERY`

Both tools catch all exceptions and return `"error: ..."` per `mcp-tool-exception-catch-all.md`.

---

## `DebateAgentProvider` Implementations

### `LangChain4jDebateAgentProvider` (`runtime`, `@DefaultBean @ApplicationScoped`)

Injects `ChatModel`. Builds `SystemMessage` + `UserMessage` from `AgentTask` fields. Returns response text. CI-friendly.

### `ClaudeAgentSdkDebateAgentProvider` (`claude-agent`, `@ApplicationScoped`)

Named per ARC42STORIES.MD. Injects `AgentProvider` (platform SPI). Displaces LangChain4j default by classpath presence. **Gated on platform#55** — stub until that ships.

CDI priority: `LangChain4jDebateAgentProvider @DefaultBean` < `ClaudeAgentSdkDebateAgentProvider @ApplicationScoped`. Consistent with `ai-agent-provider-cdi-priority.md`.

DraftHouse handlers return free-text findings. A `parseResult()` intermediate step for structured result typing belongs in the extracted pattern design (→ #42), not this implementation.

---

## §Backend Evolution

The June 7 design described `DebateChannelBackend` as a "registration fence" with a no-op `post()`. This spec evolves that contract:

**Retained:** the fence role — prevents `ReviewerChannelBackendFactory` from attaching an LLM backend to debate channels.

**Added:** `post()` parses the META sentinel from `OutboundMessage.content()`, constructs a `ChannelAgentRequest`, and fires it as a CDI event when `entryType == SUB_TASK_REQUEST`. All other entry types remain no-ops.

```java
@Inject Event<ChannelAgentRequest> channelAgentEvent;
@Inject DebateSessionRegistry registry;

@Override
public void post(ChannelRef channel, OutboundMessage message) {
    Map<String, String> meta = DebateProtocol.parseMeta(message.content());
    if (!"SUB_TASK_REQUEST".equals(meta.get("entryType"))) return;

    DebateSession session = registry.find(channel.id()).orElse(null);
    if (session == null) {
        LOG.warning("SUB_TASK_REQUEST on " + channel.id() + " — no session, dropped");
        return;
    }
    String correlationId = message.correlationId() != null
            ? message.correlationId().toString() : UUID.randomUUID().toString();
    channelAgentEvent.fireAsync(new ChannelAgentRequest(channel.id(), correlationId, message));
}
```

**Architectural choice:** the event is fired from `ChannelBackend.post()` rather than directly from `DebateMcpTools.request_subagent()`. This keeps orchestration in the Qhorus message-arrival layer — the same integration point as `ReviewerChannelBackend` — and means any `SUB_TASK_REQUEST` in the channel triggers dispatch regardless of origin.

---

## Projection — `DebateChannelProjection` (`runtime` module)

### Encoding standard applied to `apply()`

```java
@Override
public ReviewState apply(ReviewState state, MessageView message) {
    Map<String, String> meta = DebateProtocol.parseMeta(message.content());
    String entryTypeStr = meta.get("entryType");
    if (entryTypeStr == null) return state;

    EntryType entryType;
    try {
        entryType = EntryType.valueOf(entryTypeStr);
    } catch (IllegalArgumentException e) {
        LOG.warning("Unknown entryType '" + entryTypeStr + "' — discarded");
        return state;
    }

    return switch (entryType) {
        case RAISE            -> handleRaise(state, message, meta);
        case AGREE            -> handleAgree(state, message, meta);
        case COUNTER          -> handleCounter(state, message, meta);
        case DISPUTE          -> handleDispute(state, message, meta);
        case QUALIFY          -> handleQualify(state, message, meta);
        case FLAG_HUMAN       -> handleFlagHuman(state, message, meta);
        case DECLINED         -> state;   // projection-only status; never encoded in a message
        case MEMO             -> handleMemo(state, message, meta);
        case SUB_TASK_REQUEST -> handleSubTaskRequest(state, message, meta);
        case SUB_TASK_FINDING -> handleSubTaskFinding(state, message, meta);
        case SUB_TASK_ERROR   -> handleSubTaskError(state, message, meta);
    };
}

@Override
public ReviewState identity() {
    return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
}
```

### New dispatch cases (conceptual — ReviewState is immutable; all handlers build new instances)

```
MEMO             → add RoundMemo(agentRole, round, bodyContent) to memos
SUB_TASK_REQUEST → add SubTaskFinding(subTaskId, taskType, requestingAgent, pointId, null, null, PENDING)
SUB_TASK_FINDING → update SubTaskFinding(subTaskId) → COMPLETE with finding text
SUB_TASK_ERROR   → update SubTaskFinding(subTaskId) → ERROR with fixed errorReason
```

Out-of-order arrival (FINDING before REQUEST): create SubTaskFinding at COMPLETE/ERROR status directly. The fold is over an ordered message stream; order inversion is possible if the sub-agent completes very fast.

---

## SummaryRenderer Rendering Algorithm (`api` module)

`SummaryRenderer.render(ReviewState state)` algorithm:

```
1. Header: "# Review Summary\n**Updated:** <instant>\n\n---\n\n"

2. For each ReviewPoint in state.points().values():
   a. Status marker + point header line
   b. For each ThreadEntry in point.thread():
        render "> **{agent} ({typeLabel}):** {content}"
   c. For each SubTaskFinding in state.subTaskFindings().values()
        where finding.pointId() != null && finding.pointId().equals(point.id()):
          render finding with provenance marker (in subTaskId insertion order)
   d. "\n---\n\n"

3. If state.humanFlags() not empty:
     "⚑ **Human review needed:**" + flag list

4. If any SubTaskFinding has null pointId (NEUTRAL_SUMMARY or CUSTOM):
     "\n---\n\n**Sub-task findings**\n\n"
     For each such finding in subTaskId insertion order: render finding

5. If state.memos() not empty:
     "\n---\n\n**Agent Memos**\n\n"
     For each RoundMemo in insertion order: "**{agentRole} memo — Round {round}:** {content}\n\n"
```

**Finding provenance rendering:**

```
PENDING:  "  ⏳ **{taskType}** pending...\n"
COMPLETE: "  ⊕ **{taskType}** _(fresh context — no prior round knowledge)_\n  {finding}\n"
ERROR:    "  ✗ **{taskType}** failed: {errorReason}\n"
```

---

## §Concurrency

Multiple `request_subagent` calls may be in flight concurrently on the same session. Each gets a distinct `subTaskId`. `ChannelAgentDispatcher.onChannelAgentRequest()` executes concurrently on the CDI async thread pool. `ChannelAgentDispatcher` has no shared mutable state. Handler beans inject only immutable collaborators and read-only services. `ProjectionService.project()` produces a point-in-time snapshot; each invocation is independent. The Qhorus channel serialises `apply()` calls within the projection fold. No synchronization required. Safe by design.

---

## Testing

### `ChannelAgentDispatcherTest` — unit, no CDI container

`onChannelAgentRequest()` is called directly as a Java method. No container, no event firing. Synchronous.

Tests:
- Handler found and succeeds → `messageService.dispatch(SUB_TASK_FINDING)` called
- Handler throws `IllegalArgumentException` from `prepareTask` → `dispatchError("Sub-agent analysis failed.")`
- Handler throws `AgentResultParseException` from `buildResponse` → `dispatchError("Sub-agent returned an unreadable result.")`
- No handler matches → `dispatchError(...)` for the no-handler case
- Error body is a fixed string — never contains the exception message

### Handler unit tests (one test class per handler)

Each handler test creates the handler directly (no CDI), mocks `ProjectionService`, `DebateSessionRegistry`, and (where applicable) filesystem reads.

`VerifyHandlerTest`:
- Missing specPath → IAE thrown
- Missing pointId → IAE thrown
- pointId not in projected state → IAE thrown
- All inputs present → `AgentTask.assembledInput()` contains spec content and claim; does not contain any other thread entry

`ArbitrateHandlerTest`:
- Last entry filter: thread is RAISE → QUALIFY → FLAG_HUMAN; `assembledInput` uses QUALIFY content, not FLAG_HUMAN
- Multiple responses: RAISE → DISPUTE → COUNTER → QUALIFY; `assembledInput` uses QUALIFY (last of {DISPUTE, QUALIFY, COUNTER})
- No response yet: thread is RAISE only; `assembledInput` contains "(no response yet)"

`ConsistencyCheckHandlerTest`:
- Null customInput → IAE thrown
- AGREED and OPEN points in state: only AGREED appears in input; OPEN excluded

`CustomHandlerTest`:
- Null customInput → IAE thrown

`NeutralSummaryHandlerTest`:
- Empty state: assembledInput contains "(no debate entries)"
- Never throws regardless of inputs

### `DebateChannelProjectionTest` additions

- MEMO → `state.memos()` size increases; `state.points()` unchanged
- SUB_TASK_REQUEST → `state.subTaskFindings()` contains PENDING entry
- SUB_TASK_FINDING for same subTaskId → COMPLETE with finding text
- SUB_TASK_ERROR → ERROR with fixed reason string
- Out-of-order (FINDING before REQUEST) → finding created at COMPLETE directly
- Test helper `msg()` updated: all `entryType` strings use uppercase (`RAISE`, `AGREE`, etc.)

### `DebateChannelBackendTest` additions

- `SUB_TASK_REQUEST` message with active session → `ChannelAgentRequest` CDI event fired
- `SUB_TASK_REQUEST` with no session → event not fired; warning logged
- `RAISE`, `AGREE`, any other entryType → no event fired

### E2E — `SubAgentE2ETest`

`MockDebateAgentProvider @Alternative @Priority(1)` returns deterministic strings. `MockHandler @Alternative @Priority(1)` for targeted handler testing.

Full lifecycle:
- `request_subagent(ARBITRATE)` → summary shows `⏳`, then `⊕` after mock returns
- `post_memo` → memo appears in summary
- `CUSTOM` → finding in standalone "Sub-task findings" section
- Two concurrent `request_subagent` calls → both appear in summary

---

## Module Summary

| Module | Changes |
|---|---|
| `api/` | `EntryType`: add MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR. New: `SubTaskType`, `SubTaskStatus`, `SubTaskFinding` (with `pointId`), `RoundMemo`. **Rename:** `SubAgentTask` → `AgentTask`; `SubAgentProvider` → `DebateAgentProvider`. `ReviewState`: add `memos`, `subTaskFindings`; 4-field compact constructor with defensive copies. `SummaryRenderer`: exhaustiveness fix (throw for new types) + full rendering algorithm. `DebateSession`: add `specPath`. |
| `runtime/` — pattern types | New: `ChannelAgentHandler` (SPI), `ChannelAgentRequest` (CDI event record), `AgentResultParseException`, `ChannelAgentDispatcher` (registers sender, `@ObservesAsync`, finds handler, invokes provider, sanitized error dispatch). |
| `runtime/` — DraftHouse handlers | New: `AbstractDebateSubAgentHandler` (implements `handles()` + `buildResponse()`, shared helpers). New: `VerifyHandler`, `ArbitrateHandler`, `DeepAnalysisHandler`, `ConsistencyCheckHandler`, `NeutralSummaryHandler`, `CustomHandler` (each `@ApplicationScoped`, implements `taskType()` + `prepareTask()`). |
| `runtime/` — existing changes | `DebateProtocol`: add `parseMeta()` + `bodyContent()` as public static methods (moved from `DebateChannelProjection`). `DebateChannelBackend`: evolve `post()` to fire `ChannelAgentRequest`; inject `Event<ChannelAgentRequest>` + `DebateSessionRegistry`. `DebateChannelProjection`: `EntryType.valueOf()` switch; `identity()` 4-field; new handlers; all `new ReviewState(...)` → 4-field. `DebateMcpTools`: uppercase encoding; store `specPath`; add `post_memo`, `request_subagent`. **Remove:** `SubAgentRequest`, `SubAgentOrchestrator`. `LangChain4jDebateAgentProvider @DefaultBean` (renamed from `LangChain4jSubAgentProvider`). |
| `claude-agent/` | `ClaudeAgentSdkDebateAgentProvider @ApplicationScoped` (aligned with ARC42; stub pending platform#55). |
| Tests | New: `ChannelAgentDispatcherTest`, `VerifyHandlerTest`, `ArbitrateHandlerTest`, `ConsistencyCheckHandlerTest`, `CustomHandlerTest`, `NeutralSummaryHandlerTest`, `SubAgentE2ETest`. Update: `DebateChannelProjectionTest` (uppercase META headers), `DebateChannelBackendTest` (event type → `ChannelAgentRequest`), `DebateMcpToolsTest` (new tools + encoding). |

---

## Deferred Issues

| Issue | What |
|---|---|
| #40 | Restart-from-round-N semantics |
| #41 | Threshold-based auto-reset + context meter UI |
| #42 | Channel-Reactive Agent — extraction to patterns repo; devtown extraction concerns documented |
