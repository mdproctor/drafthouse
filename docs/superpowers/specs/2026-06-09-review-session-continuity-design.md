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
                      DebateChannelBackend.post()
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
    NEUTRAL_SUMMARY,    // compact neutral summary of the current round
    CUSTOM              // caller provides explicit context string
}
```

### `SubTaskFinding`

```java
public record SubTaskFinding(
    String subTaskId,
    SubTaskType taskType,
    String requestingAgent,  // REV or IMP — provenance: who asked
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

public record SubAgentTask(
    SubTaskType taskType,
    String assembledInput,  // assembled by SubAgentOrchestrator
    String systemPrompt     // per-task-type persona, assembled by orchestrator
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

- `pointId`: the debate point this task relates to. Null for `NEUTRAL_SUMMARY` and `CUSTOM`.
- `customInput`: only for `CUSTOM` task type — the full context the sub-agent receives.
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
    String pointId,      // null for NEUTRAL_SUMMARY, CUSTOM
    String customInput,  // null unless CUSTOM
    String specPath      // from DebateSession — needed for VERIFY, DEEP_ANALYSIS
) {}
```

### `DebateChannelBackend` change

Thin adapter — fires event and returns immediately:

```java
@Override
public void post(MessageView message) {
    EntryType entryType = DebateProtocol.extractEntryType(message);
    if (entryType == EntryType.SUB_TASK_REQUEST) {
        subAgentEvent.fireAsync(DebateProtocol.toSubAgentRequest(message, session));
    }
    // all other entry types: no backend action
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

    @ObservesAsync
    public void onSubAgentRequest(SubAgentRequest event) {
        DebateSession session = registry.find(event.channelId()).orElse(null);
        if (session == null) return; // session ended mid-flight — silent drop

        try {
            SubAgentTask task = assembleTask(event);
            String finding = subAgentProvider.analyse(task);
            dispatchFinding(event, session, finding);
        } catch (Exception e) {
            dispatchError(event, session, e.getMessage());
        }
    }
}
```

### Input assembly by task type

**Invariant enforced in all assemblies:** no prior round entries are included unless the task type explicitly requires them. This is the architectural enforcement of "deliberately minimal context."

| Task type | Assembled input | System prompt persona |
|---|---|---|
| `VERIFY` | Claim text (from `pointId` message) + full spec content (`specPath`) | "You are a spec verifier. You have no knowledge of this debate's prior rounds. Determine only whether this claim is supported by the spec." |
| `ARBITRATE` | Original `raise` content + most recent `dispute`/`qualify`/`counter` on that point — nothing else | "You are a neutral arbitrator. You have not seen this debate before. Assess these two positions on their merits only." |
| `DEEP_ANALYSIS` | Full spec content + `location` field from the `pointId` message as a focus hint | "You are a spec analyst. Read this spec with fresh eyes. Focus on the indicated section. Identify issues." |
| `CONSISTENCY_CHECK` | All `AGREED` points from `ProjectionService.project()` as a compact list + `customInput` (proposed resolution) | "You have no memory of this debate. Determine only whether the proposed resolution contradicts any of these prior agreements." |
| `NEUTRAL_SUMMARY` | All entries whose `round` metadata matches the round number in the `SUB_TASK_REQUEST` message, extracted from projected `ReviewState` | "Summarise this debate round neutrally. You have not participated in this debate." |
| `CUSTOM` | `customInput` verbatim | "You are a focused analyst. Answer only the question posed. You have no knowledge of the broader debate." |

### Finding and error dispatch

**Finding** — `SUB_TASK_FINDING` message:
```
META_SENTINEL + "entryType=SUB_TASK_FINDING|subTaskId=<id>|taskType=<type>|agent=<requestingAgent>\n\n<finding>"
```
`MessageType.RESPONSE`, `correlationId = subTaskId`, `inReplyTo = id of the SUB_TASK_REQUEST message`.

**Error** — `SUB_TASK_ERROR` message: `MessageType.STATUS`, same encoding with `entryType=SUB_TASK_ERROR|reason=<reason>`.

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

```java
case MEMO            -> state.addMemo(new RoundMemo(agent, round, content));
case SUB_TASK_REQUEST -> state.addSubTask(new SubTaskFinding(subTaskId, taskType, requestingAgent, null, null, PENDING));
case SUB_TASK_FINDING -> state.completeSubTask(subTaskId, finding);
case SUB_TASK_ERROR   -> state.errorSubTask(subTaskId, errorReason);
```

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
  ✗ **VERIFY** failed: timeout after 30s
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
- Sub-agent failure: assert `SUB_TASK_ERROR` dispatched with reason
- Session not found mid-flight: assert silent drop (no exception, no dispatch)

**`DebateChannelProjectionTest`** — fold new entry types, assert render:
- `MEMO` → appears in render at correct round position
- `SUB_TASK_REQUEST` → renders as `⏳ PENDING`
- `SUB_TASK_FINDING` for same subTaskId → renders with `⊕` provenance marker
- `SUB_TASK_ERROR` → renders with `✗` and reason
- `NEUTRAL_SUMMARY` finding → renders in standalone section, not beneath a point
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
| `api/` | Add `MEMO`/`SUB_TASK_REQUEST`/`SUB_TASK_FINDING`/`SUB_TASK_ERROR` to `EntryType`; add `SubTaskType`, `SubTaskStatus`, `SubTaskFinding`, `RoundMemo` records; add `SubAgentProvider` SPI + `SubAgentTask` record; add `specPath` field to `DebateSession` (required for VERIFY/DEEP_ANALYSIS input assembly — `start_debate` already receives it, just not stored) |
| `runtime/` | Add `SubAgentOrchestrator`, `SubAgentRequest` CDI event record, `LangChain4jSubAgentProvider @DefaultBean`; update `DebateChannelBackend.post()` (fire CDI event for SUB_TASK_REQUEST); update `DebateChannelProjection` (new dispatch cases + render); update `ReviewState` (add memos, subTaskFindings); add `post_memo` and `request_subagent` to `DebateMcpTools` |
| `claude-agent/` | Add `ClaudeSubAgentProvider @ApplicationScoped` (gated on platform#55 — stub until then) |

---

## Deferred Issues

| Issue | What |
|---|---|
| #40 | Restart-from-round-N semantics — branching the channel history and what is actually lost |
| #41 | Threshold-based auto-reset safety valve + context meter UI |
