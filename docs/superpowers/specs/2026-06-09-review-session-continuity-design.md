# Review Session Continuity — Sub-Agent Architecture
**Date:** 2026-06-09
**Status:** Approved
**Issue:** #26
**Deferred:** #40 (restart-from-N), #41 (threshold auto-reset + context meter)

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

## Architecture

```
DebateMcpTools
  ├── post_memo()         → MEMO entry → debate channel
  └── request_subagent() → SUB_TASK_REQUEST entry → debate channel
                                ↓
                      DebateChannelBackend.post(ChannelRef, OutboundMessage)
                                ↓ event.fireAsync(SubAgentRequest)
                      SubAgentOrchestrator @ObservesAsync
                                ├── assemble input (task-type-driven)
                                ├── SubAgentProvider.analyse(task)
                                │     @DefaultBean: LangChain4jSubAgentProvider (ChatModel)
                                │     @ApplicationScoped: ClaudeSubAgentProvider (platform#55)
                                └── MessageService.dispatch(SUB_TASK_FINDING or SUB_TASK_ERROR)
                                              ↓
                                  DebateChannelProjection
                                  (renders findings with provenance label)
```

Sub-task requests and findings are entries on the **existing debate channel** — no new Qhorus channels. The `DebateChannelBackend` remains a thin adapter; all orchestration logic lives in `SubAgentOrchestrator`.

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

### `SubTaskType`

```java
public enum SubTaskType {
    VERIFY,             // check a claim against the spec
    ARBITRATE,          // neutral read on a disputed point — both arguments, no history
    DEEP_ANALYSIS,      // close reading of a spec section before raising points
    CONSISTENCY_CHECK,  // does a proposed resolution contradict prior agreements?
    NEUTRAL_SUMMARY,    // compact neutral summary of the current debate state
    CUSTOM              // caller provides explicit context string
}
```

### `SubTaskFinding`

```java
public record SubTaskFinding(
    String subTaskId,
    SubTaskType taskType,
    String requestingAgent,  // "REV" or "IMP" — provenance: who asked
    String pointId,          // correlationId of the related debate point; null for NEUTRAL_SUMMARY and CUSTOM
    String finding,          // null while PENDING or on ERROR
    String errorReason,      // null unless ERROR
    SubTaskStatus status
) {}

public enum SubTaskStatus { PENDING, COMPLETE, ERROR }
```

### `RoundMemo`

```java
public record RoundMemo(String agentRole, int round, String content) {}
```

### `ReviewState` additions

- `List<RoundMemo> memos` — per-round reasoning memos across all agents and rounds
- `Map<String, SubTaskFinding> subTaskFindings` — keyed by `subTaskId`

### `SubAgentProvider` SPI

```java
public interface SubAgentProvider {
    String analyse(SubAgentTask task);
}

// Field order: taskType, systemPrompt, assembledInput — matches LLM API convention (system before user)
public record SubAgentTask(
    SubTaskType taskType,
    String systemPrompt,    // per-task-type persona, assembled by orchestrator
    String assembledInput   // minimally scoped context, assembled by orchestrator
) {}
```

Blocking return — the orchestrator runs on a CDI async event thread; blocking is correct here. No streaming: the finding is posted as a complete entry once the sub-agent completes.

---

## MCP Tool Surface (`runtime` module — `DebateMcpTools`)

### `post_memo`

```
post_memo(debateSessionId, agentRole, round, content)
→ {status: "dispatched"}
```

Encoding: `META_SENTINEL + "entryType=MEMO|agent=<role>|round=<n>\n\n<content>"`
Qhorus type: `MessageType.STATUS`

Agents call this after their last raise/respond of a round to record working hypotheses, patterns noticed, and why concessions feel solid vs provisional. Memos are not citable by other entries but inform a cold agent resuming from the channel history.

### `request_subagent`

```
request_subagent(debateSessionId, agentRole, taskType, pointId?, customInput?)
→ {subTaskId: "<uuid>", status: "dispatched"}
```

- `pointId`: the debate point this task relates to. Null for `NEUTRAL_SUMMARY` and `CUSTOM`. Optional for `DEEP_ANALYSIS`.
- `customInput`: for `CUSTOM` — the full context the sub-agent receives. For `CONSISTENCY_CHECK` — the proposed resolution text to check against prior agreements. Null for all other task types.
- `subTaskId`: fresh UUID generated at tool call time; becomes `correlationId` on the channel message so the projection matches REQUEST → FINDING by this ID.

Encoding: `META_SENTINEL + "entryType=SUB_TASK_REQUEST|agent=<role>|taskType=<type>|subTaskId=<uuid>[|pointId=<id>]\n\n[customInput]"`
Qhorus type: `MessageType.QUERY`

The tool wraps its dispatch call in try/catch and returns `"error: ..."` on failure per `mcp-tool-exception-catch-all.md`. The orchestrator's async execution is fire-and-forget from the tool's perspective — failures surface as `SUB_TASK_ERROR` entries in the channel.

The main agent can continue raising/responding while the sub-agent runs. `get_debate_summary` shows `⏳ PENDING` until the finding arrives.

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
    String specPath      // from DebateSession — needed for VERIFY, DEEP_ANALYSIS; may be null
) {}
```

### `SUBAGENT_INSTANCE_ID` registration

`SubAgentOrchestrator` registers a shared Qhorus instance for the sub-agent sender at startup:

```java
static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

@Inject InstanceService instanceService;

@PostConstruct
void registerSubAgentInstance() {
    instanceService.register(SUBAGENT_INSTANCE_ID,
            "DraftHouse sub-agent (focused analysis)",
            List.of("document-debate-subagent"));
}
```

All `SUB_TASK_FINDING` and `SUB_TASK_ERROR` messages are dispatched with `sender = SUBAGENT_INSTANCE_ID`. Qhorus validates sender instances at dispatch time — this registration must happen before any sub-agent finding is dispatched.

### `DebateChannelBackend` change

Thin adapter — fires event and returns immediately. Correct SPI signature: `post(ChannelRef channel, OutboundMessage message)`.

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

The backend parses the META sentinel from `OutboundMessage.content()` — the same `META_SENTINEL + "key=value|..." \n\n <body>` encoding used everywhere in the debate protocol. `OutboundMessage` is the Qhorus SPI type delivered to `ChannelBackend.post()`; `MessageView` is the type used by `ChannelProjection.apply()` — these are different Qhorus types, used in different contexts.

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
    void registerSubAgentInstance() {
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                List.of("document-debate-subagent"));
    }

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
            dispatchError(event);
        }
    }
}
```

### Error dispatch — sanitization rule

**`dispatchError()` must never pass exception messages to the Qhorus ledger.** The ledger is tamper-evident and immutable — exception messages may contain stack traces, internal paths, or API keys. Log the actual exception at WARN level; dispatch a fixed sanitized category string:

```java
private void dispatchError(SubAgentRequest event) {
    // Log full detail for debuggability
    // LOG.warning(...) already called by the caller

    // Dispatch fixed string — never e.getMessage() — to the immutable Qhorus ledger
    String encoded = DebateProtocol.META_SENTINEL
            + "entryType=SUB_TASK_ERROR|subTaskId=" + event.subTaskId()
            + "|taskType=" + event.taskType()
            + "|agent=" + event.requestingAgent()
            + "\n\nSub-agent analysis failed.";
    messageService.dispatch(MessageDispatch.builder()
            .channelId(event.channelId())
            .sender(SUBAGENT_INSTANCE_ID)
            .type(MessageType.STATUS)
            .content(encoded)
            .correlationId(event.subTaskId())
            .actorType(ActorType.AGENT)
            .build());
}
```

This is a hard requirement from `qhorus-dispatch-exception-sanitization.md` — the same rule that governs all error dispatch in DraftHouse and Claudony backends.

### Input assembly by task type

**Invariant enforced in all assemblies:** no prior round entries are included unless the task type explicitly requires them. This is the architectural enforcement of "deliberately minimal context."

| Task type | Assembled input | System prompt persona |
|---|---|---|
| `VERIFY` | Claim text (from `pointId` point's first thread entry) + full spec content (`specPath`) | "You are a spec verifier. You have no knowledge of this debate's prior rounds. Determine only whether this claim is supported by the spec." |
| `ARBITRATE` | Original `raise` content + most recent `dispute`/`qualify`/`counter` on that point — nothing else | "You are a neutral arbitrator. You have not seen this debate before. Assess these two positions on their merits only." |
| `DEEP_ANALYSIS` | Full spec content + `location` field from the `pointId` message as a focus hint (or "(no section indicated)" if null) | "You are a spec analyst. Read this spec with fresh eyes. Focus on the indicated section. Identify issues." |
| `CONSISTENCY_CHECK` | All `AGREED` points from `ProjectionService.project()` as a compact list + `customInput` (proposed resolution text) | "You have no memory of this debate. Determine only whether the proposed resolution contradicts any of these prior agreements." |
| `NEUTRAL_SUMMARY` | All points and their thread entries from the current projected `ReviewState` — a v1 approximation summarising the full debate state rather than a single round | "Summarise this debate neutrally. You have not participated in it." |
| `CUSTOM` | `customInput` verbatim | "You are a focused analyst. Answer only the question posed. You have no knowledge of the broader debate." |

**Note on `NEUTRAL_SUMMARY` round scoping:** v1 summarises the full projected state as an approximation. Exact per-round filtering (extracting only entries from a specific round) requires carrying `round` through `SubAgentRequest` and filtering `ThreadEntry.round()` — deferred to a future improvement.

Context assembly reads from the projected `ReviewState` via `ProjectionService.project(channelId, debateProjection)` — not from raw channel messages. This gives structured, pre-parsed point/thread data without re-parsing META headers.

### Finding dispatch

**Finding** — `SUB_TASK_FINDING` message:
```
META_SENTINEL + "entryType=SUB_TASK_FINDING|subTaskId=<id>|taskType=<type>|agent=<requestingAgent>[|pointId=<id>]\n\n<finding>"
```
`MessageType.RESPONSE`, `correlationId = subTaskId`, `inReplyTo = id of the SUB_TASK_REQUEST message` (resolved via `messageService.findByCorrelationId(subTaskId)`).

**Error** — `SUB_TASK_ERROR` message: `MessageType.STATUS`, fixed body `"Sub-agent analysis failed."` — never the exception message (see sanitization rule above).

---

## `SubAgentProvider` Implementations (`runtime` + `claude-agent` modules)

### `LangChain4jSubAgentProvider` (`runtime`, `@DefaultBean @ApplicationScoped`)

Injects `@Inject ChatModel` (already a LangChain4j dependency via `DocumentReviewer`). Builds a `SystemMessage` from `task.systemPrompt()` and a `UserMessage` from `task.assembledInput()`. Calls `chatModel.generate(messages)` and returns the response text. CI-friendly — no Claude CLI required.

### `ClaudeSubAgentProvider` (`claude-agent`, `@ApplicationScoped`)

Injects `@Inject AgentProvider` (platform SPI from `casehub-platform-agent-claude`). Builds `AgentSessionConfig` from the task. Calls `agentProvider.run(config).collect().asList().await().indefinitely()`, extracts `TextDelta` events into a string. Displaces the LangChain4j default by classpath presence. **Gated on platform#55** — the stub in `claude-agent/` is the placeholder until that ships.

CDI priority: `LangChain4jSubAgentProvider @DefaultBean` < `ClaudeSubAgentProvider @ApplicationScoped`. Consistent with `ai-agent-provider-cdi-priority.md`.

---

## Projection and Provenance Rendering

### `DebateChannelProjection` — new dispatch cases

The following is conceptual — `ReviewState` is an immutable record; the actual implementation creates new `ReviewState` instances on each `apply()` call, carrying existing collections forward and adding the new entry:

```
MEMO            → add RoundMemo(agentRole, round, content) to memos list
SUB_TASK_REQUEST → add SubTaskFinding(subTaskId, taskType, requestingAgent, pointId, null, null, PENDING)
SUB_TASK_FINDING → update existing SubTaskFinding(subTaskId) to COMPLETE with finding text
SUB_TASK_ERROR   → update existing SubTaskFinding(subTaskId) to ERROR with fixed reason "Sub-agent analysis failed."
```

`identity()` returns `new ReviewState(Map.of(), List.of(), List.of(), Map.of())`.

### `render()` output

**Memos** appear after all entries for their round, clearly separated:
```markdown
**REV memo — Round 2:** §4 feels under-specified across the board...
```

**Sub-task findings** appear beneath the point they relate to with provenance marker:
```markdown
**[R2-IMP-001]** `dispute` · → [R1-REV-002]
Retry is caller responsibility per MCP contract. Silence is intentional.
→ Status: 🟡 Active

  ⊕ **ARBITRATE** _(fresh context — no prior round knowledge)_
  "Both positions have merit. The implementer's argument holds for standard
   MCP consumers, but the reviewer's concern about spec ambiguity is valid —
   callers cannot distinguish intentional silence from oversight without
   documentation."
```

**In-flight:**
```
  ⏳ **VERIFY** pending...
```

**Failed:**
```
  ✗ **VERIFY** failed: Sub-agent analysis failed.
```

`NEUTRAL_SUMMARY` and `CUSTOM` findings (no `pointId`) render in a dedicated **Sub-task findings** section at the end of the summary.

**Provenance invariant:** every `SUB_TASK_FINDING` carries `requestingAgent` (who asked) and the system prompt enforces fresh context. The `⊕` marker + `_(fresh context — no prior round knowledge)_` makes this visible. Main-agent responses carry no provenance marker — implicitly accumulated-context.

---

## Testing

### Unit tests

**`SubAgentOrchestratorTest`** — fire `SubAgentRequest` CDI events directly:
- Each `SubTaskType`: assert assembled input contains exactly the right slices and no extraneous context
- `VERIFY`: assert spec content present, prior round entries absent
- `ARBITRATE`: assert only raise + most recent response for the point, nothing else
- `CONSISTENCY_CHECK`: assert only agreed points + customInput, not full thread
- Sub-agent failure: assert `SUB_TASK_ERROR` dispatched with fixed body `"Sub-agent analysis failed."` — never `e.getMessage()`
- Session not found mid-flight: assert silent drop (no exception, no dispatch)

**`DebateChannelProjectionTest`** — fold new entry types, assert render:
- `MEMO` → appears in render at correct round position
- `SUB_TASK_REQUEST` → renders as `⏳ PENDING`
- `SUB_TASK_FINDING` for same subTaskId → renders with `⊕` provenance marker
- `SUB_TASK_ERROR` → renders with `✗` and fixed reason string
- `NEUTRAL_SUMMARY` finding (null pointId) → renders in standalone section, not beneath a point
- Ordering: findings beneath their point, memos after all entries for their round

**`DebateChannelBackendTest`**:
- `SUB_TASK_REQUEST` message → assert `SubAgentRequest` CDI event fired with correct fields
- All other entry types → assert no event fired

### E2E

**`SubAgentE2ETest`** with mock `SubAgentProvider @Alternative @Priority(1)`:
- Full session: raise point, respond, `request_subagent(ARBITRATE)` → `get_debate_summary` shows `⏳ PENDING` immediately, then COMPLETE with `⊕` finding once mock returns
- `post_memo` → verify memo appears in summary at correct round
- `CUSTOM` sub-task → finding in standalone section

---

## Module Summary

| Module | Changes |
|---|---|
| `api/` | Add `MEMO`/`SUB_TASK_REQUEST`/`SUB_TASK_FINDING`/`SUB_TASK_ERROR` to `EntryType`; add `SubTaskType`, `SubTaskStatus`, `SubTaskFinding` (with `pointId`), `RoundMemo` records; add `SubAgentProvider` SPI + `SubAgentTask` record (field order: taskType, systemPrompt, assembledInput); add `specPath` field to `DebateSession` |
| `runtime/` | Add `SubAgentOrchestrator` (with `@PostConstruct` instance registration + sanitized error dispatch), `SubAgentRequest` CDI event record, `LangChain4jSubAgentProvider @DefaultBean`; update `DebateChannelBackend.post(ChannelRef, OutboundMessage)` (fire CDI event for SUB_TASK_REQUEST); update `DebateChannelProjection` (new dispatch cases + updated `identity()`); update `ReviewState` (add memos, subTaskFindings); add `post_memo` and `request_subagent` to `DebateMcpTools` |
| `claude-agent/` | Add `ClaudeSubAgentProvider @ApplicationScoped` (gated on platform#55 — stub until then) |

---

## Deferred Issues

| Issue | What |
|---|---|
| #40 | Restart-from-round-N semantics — branching the channel history and what is actually lost |
| #41 | Threshold-based auto-reset safety valve + context meter UI |
