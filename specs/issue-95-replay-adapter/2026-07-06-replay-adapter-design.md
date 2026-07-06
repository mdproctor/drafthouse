# Design: Replay Adapter for Completed design-review Workspaces

**Issue:** #95
**Date:** 2026-07-06
**Status:** Approved
**Approach:** Channel dispatch (Approach A)

## Overview

Build a replay adapter that reads a completed design-review workspace directory and
projects it as debate entries into a DraftHouse `DebateSession` via Qhorus channel
dispatch. The adapter parses workspace files (reviewer-N.md, implementor-N.md,
tracker.md) using regex patterns ported from design-review's `parser.py`, translates
each extracted element into a `DHMETA:`-encoded channel message, and dispatches through
`MessageService.dispatch()` with `MessageDispatch.builder()`. The existing
`DebateChannelProjection` folds entries into `ConversationState`. Browser panels render
via WebSocket push.

## Architecture Decision: Channel Dispatch

The adapter dispatches entries through a Qhorus channel rather than pushing directly
to `WebSocketEventBus`. This gives:

- **Persistence** — replayed sessions survive server restart (JPA store already wired)
- **MCP queryability** — `get_debate_summary` works without modification (calls `projectionService.project()`)
- **Future continuation** — an LLM could `respond_to` a replayed point, turning replay into conversation
- **Pipeline validation** — encoding then decoding via projection validates the wire format

The round-trip of encoding then decoding is a feature, not overhead — it proves the
adapter produces entries the projection correctly folds.

## Component Structure

### WorkspaceParser

Pure function. Takes a workspace directory path, returns structured data objects.
Ports 8 Python regexes to Java. No DraftHouse dependencies — operates on strings and
returns records. Testable in isolation with fixture files.

**Location:** `server/runtime/src/main/java/io/casehub/drafthouse/debate/WorkspaceParser.java`

### WorkspaceReplayAdapter

Orchestrator. Takes the parser's output, creates a `DebateSession`, creates a Qhorus
channel, encodes each parsed element as a `DHMETA:`-prefixed channel message, dispatches
via `MessageService.dispatch()` with `MessageDispatch.builder()` in the correct emission
order. Batch-pushes entries to WebSocket after all dispatches complete.

**Location:** `server/runtime/src/main/java/io/casehub/drafthouse/debate/WorkspaceReplayAdapter.java`

### MCP tool: load_workspace

Thin entry point on `DebateMcpTools`. Validates the path, calls
`WorkspaceReplayAdapter.replay()`, returns a summary string.

**Location:** Added to `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`

## Parser Model

```java
record WorkspaceParseResult(
    String specPath,              // from .spec-path
    String mode,                  // from .mode (e.g. "spec-review")
    String contextNote,           // from context.md (nullable)
    List<ParsedRound> rounds,     // ordered by round number
    List<ParsedTrackerEntry> trackerStatuses  // terminal statuses from tracker.md
)

record ParsedRound(
    int roundNumber,
    String signal,                // APPROVED / CONTINUE / DECISION_NEEDED
    String signalDescription,     // nullable — only for DECISION_NEEDED
    List<ParsedIssue> issues,     // from reviewer-N.md
    List<ParsedResponse> responses,  // from implementor-N.md
    List<ParsedConfirmation> confirmations,  // from reviewer-(N+1).md
    List<String> assumptions,     // ASSUMPTION: lines
    List<ParsedSettledDecision> settledDecisions  // SETTLED: lines
)

record ParsedIssue(String issueId, String title, String body)

record ParsedResponse(
    String issueId,
    String status,                // FIXED / REJECTED / ESCALATED
    String sectionRef,            // bare numeric e.g. "4.2" (nullable)
    String rationale,
    String body
)

record ParsedConfirmation(
    String issueId,
    boolean resolved,
    boolean accepted,
    String reason
)

record ParsedSettledDecision(String text, String fromIssue)

record ParsedTrackerEntry(
    String issueId,
    String title,
    String status,                // OPEN, ADDRESSED, VERIFIED, ACCEPTED, CONTESTED, DEFERRED
    String evidence               // nullable — commit hash or other evidence text
)
```

**Round grouping:** The parser discovers rounds by scanning `responses/` for
`reviewer-N.md` / `implementor-N.md` files, incrementing N until no more exist.

**Cross-round confirmations:** reviewer-2.md contains confirmations for round-1 issues
AND new round-2 issues. Confirmations go into round 1's `ParsedRound`, new issues go
into round 2's `ParsedRound`.

**Section ref:** Stored as bare numeric (e.g. `"4.2"`), not prefixed with `§`. The
adapter adds the prefix when setting `location`.

## Regex Patterns (ported from parser.py)

| Name | Python Pattern | Purpose |
|------|---------------|---------|
| `SIGNAL_RE` | `^\s*SIGNAL\s*[:\s]+\s*(APPROVED\|CONTINUE\|DECISION_NEEDED)\b\s*[.:]*\s*(.*?)\s*$` | Round signal extraction |
| `HEADING_RE` | `^(#{2,3})\s+(.+?)\s*$` | Heading extraction for new issues |
| `ISSUE_ID_RE` | `R(\d+)-(\d+)` | Issue ID format |
| `ISSUE_RESPONSE_RE` | `^#{2,3}\s+R(\d+)-(\d+)\s*[:\s—\-]+\s*(FIXED\|REJECTED\|ESCALATED)\b` | Implementor response heading |
| `CONFIRMATION_RE` | `R(\d+)-(\d+)\b[^#\n]*?\b(resolved\|accepted\|still\s+open)\b` | Reviewer confirmation |
| `SECTION_REF_RE` | `§(\d+(?:\.\d+)*)\|[Ss]ection\s+(\d+(?:\.\d+)*)` | Section reference |
| `ASSUMPTION_RE` | `^ASSUMPTION:\s*(.+)$` | Assumption extraction |
| `SETTLED_RE` | `^SETTLED:\s*(.+?)(?:\(from\s+(R\d+-\d+)\))?\s*$` | Settled decision extraction |

Plus `_KNOWN_SECTIONS` — a `Set<String>` of 27 heading names to skip during issue extraction
(e.g. "assumptions", "settled decisions", "summary", "overview", "final assessment").

### Tracker.md patterns

Tracker.md uses a structured heading + bullet format (from `tracker.py.render()`).
Parsed via line-by-line extraction rather than multiline regexes:

| Name | Pattern | Purpose |
|------|---------|---------|
| `TRACKER_HEADING_RE` | `^###\s+(R\d+-\d+):\s+(.+)$` | Issue ID + title from heading |
| `TRACKER_STATUS_RE` | `^-\s+\*\*Status:\*\*\s+(\w+)` | Terminal status value |
| `TRACKER_EVIDENCE_RE` | `^-\s+\*\*Spec commit:\*\*\s*(.*)` | Commit hash evidence |

The parser reads tracker.md top-to-bottom, emitting a `ParsedTrackerEntry` each time
a new heading is encountered (flushing the previous entry). Status and evidence fields
are populated from the bullets under each heading.

## Entry Emission Order

Within each round, entries are emitted in this sequence:

1. **RAISE** — one per `ParsedIssue`, role=REV, round=N, pointId=issueId, location
   extracted from the issue body by scanning for `§N.N` or `Section N.N` patterns via
   `SECTION_REF_RE`. If no match in the body, check whether a corresponding `ParsedResponse`
   has a `sectionRef` and use that (implementor responses sometimes carry the section
   reference that the reviewer omitted).
2. **QUALIFY / COUNTER / FLAG_HUMAN** — one per `ParsedResponse`, role=IMP, round=N
   - FIXED → entryType=QUALIFY (not AGREE — preserves non-terminal "implementor claims fix")
   - REJECTED → entryType=COUNTER
   - ESCALATED → entryType=FLAG_HUMAN
3. **VERIFIED / DISPUTE / AGREE** — one per `ParsedConfirmation`, role=REV, round=N+1
   - resolved=true → entryType=VERIFIED (new status)
   - accepted=true → entryType=AGREE
   - neither (still open) → entryType=DISPUTE, content includes reason
4. **MEMO** — assumptions and settled decisions, role=REV, round=N

5. **DEFERRED** — one per issue where `tracker.md` terminal status is DEFERRED, role=REV.
   Round derivation: find the last `ParsedConfirmation` for that issueId where
   `resolved=false && accepted=false` — this is the confirmation round that triggered
   auto-escalation. **Fallback for explicit deferral:** if no matching confirmation exists
   (issue deferred via `OPEN → DEFERRED` without ever being contested), use the round the
   issue was raised in (`ParsedIssue` belonging round). Emitted after all conversation
   entries for that point, so the projection fold produces the correct terminal status.
6. **Evidence MEMO** — one per issue where `tracker.md` has a non-null `evidence` field
   (spec commit hashes). Emitted as MEMO with role=REV, content prefixed with the issueId
   (e.g. `"R1-02: spec commit abc123 → def456"`). Round derivation: find the
   `ParsedResponse` with status=FIXED for that issueId — its round is when the spec was
   changed. If no FIXED response exists (evidence recorded for other reasons), fall back
   to the round the issue was raised in. This fulfils issue #95's requirement to read
   evidence from tracker.md.

Each entry is encoded with `ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, content)`
and dispatched via `MessageService.dispatch()` with `MessageDispatch.builder()`. The builder
sets `channelId`, `sender` (from registered instance), `type` (MessageType), `content`
(encoded), `correlationId` (pointId), and `actorType(ActorType.AGENT)`.

**MessageType mapping:** Each entry type maps to a Qhorus `MessageType` for correct
commitment chain handling:

| Entry type | MessageType | Rationale |
|-----------|-------------|-----------|
| RAISE | `QUERY` | Opens a commitment |
| QUALIFY | `RESPONSE` | Conditioned response (non-terminal) |
| COUNTER | `RESPONSE` | Rejection response (non-terminal) |
| AGREE | `DONE` | Fulfils the commitment |
| DISPUTE | `DECLINE` | Declines resolution |
| FLAG_HUMAN | `HANDOFF` | Delegates to human |
| VERIFIED | `DONE` | Confirms resolution (like AGREE) |
| DEFERRED | `DECLINE` | Declines further resolution (like DISPUTE) |
| MEMO | `STATUS` | Advisory, no commitment effect |

**`inReplyTo` linkage:** Response entries (QUALIFY, COUNTER, FLAG_HUMAN, VERIFIED, DISPUTE,
AGREE, DEFERRED) set `inReplyTo` to the database message ID of the corresponding RAISE
dispatch. `MessageService.dispatch()` returns `DispatchResult`, whose `messageId` field
provides the persisted ID directly — no follow-up query needed. The adapter captures the
RAISE `DispatchResult.messageId()` and reuses it for all subsequent response dispatches
for that point. This maintains correct Qhorus commitment chain semantics.

**Batch push:** All entries are dispatched to the channel first (no per-message WebSocket
push). After all dispatches complete, the adapter calls
`messageService.pollAfter(channelId, 0L, Integer.MAX_VALUE)` to retrieve all persisted
messages, converts each to `DebateStreamEntry.from(Message)`, and pushes the full list
once via `WebSocketEventBus.pushDebateEntries()`. This uses the existing factory method
— no second construction path.

**Error recovery:** The entire dispatch sequence is wrapped in a try/catch. On failure,
cleanup mirrors `start_debate`: deregister instances, remove session from registry, delete
channel (which removes all partially-dispatched messages). The idempotency check on retry
will not find the cleaned-up session. Error is returned as `"error: ..."` per
PP-20260604-6e8d5d.

## DraftHouse Changes: VERIFIED and DEFERRED

### Server-side — DebateChannelProjection

`DebateChannelProjection` extends `ConversationProjection` (from `casehub-blocks`).
Infrastructure entry types (MEMO, SUB_TASK_*, FLAG_HUMAN, RESTART_CONTEXT) are handled
by the base class automatically. Domain entry types are dispatched via the `handleDomain()`
→ `statusAfter()` hook path. The base class enforces PP-20260610-a47ef5 (apply must not
throw) — `statusAfter()` returning `null` for unknown types is the defensive contract.

Changes to the `statusAfter()` hook override:
- `case "VERIFIED" -> "VERIFIED";`
- `case "DEFERRED" -> "DEFERRED";`

Changes to `DEBATE_CONFIG` (`ConversationRendererConfig`):
- `resolvedStatuses`: add `"VERIFIED"`, `"DEFERRED"`
- `statusEmoji`: add `"VERIFIED"` → `"✅"`, `"DEFERRED"` → `"⏸"`
- `entryTypeLabel`: add `"VERIFIED"` → `"verified"`, `"DEFERRED"` → `"deferred"`

Note: QUALIFY → "qualified" is an intentional semantic mapping. In the debate model,
QUALIFY means "implementor provides a qualified (conditioned) response" — this is the
correct semantic for what design-review calls FIXED.

### API — EntryType enum

- Add `VERIFIED` and `DEFERRED` to the enum

### Client-side — drafthouse-review-tracker.js

- `ENTRY_TO_STATUS`: add `VERIFIED → 'VERIFIED'`, `DEFERRED → 'DEFERRED'`
- `STATUS_ORDER`: add `VERIFIED: 6`, `DEFERRED: 7` (terminal, sorted last)
- `STATUS_ICON`: add `VERIFIED: '✓✓'` (double-check, distinct from AGREED's `✓`), `DEFERRED: '⏸'`
- Add both to resolved set for progress bar calculation

### Not in scope

- No structured commit evidence fields on `DebateStreamEntry` — commit SHAs from
  tracker.md are emitted as MEMO content (step 6 of Entry Emission Order)
- No auto-escalation logic in the projection — DEFERRED is emitted by the adapter from
  `tracker.md` terminal statuses, not computed by the projection state machine

## MCP Tool and Session Setup

```
@Tool("load_workspace")
String loadWorkspace(String workspacePath)
```

**Session creation flow:**
1. Extract workspace directory name from path (e.g. `"websocket-tests-20260704-190851"`)
2. Compute deterministic channel name: `"drafthouse/debate/replay-" + workspaceDirName`
   (letter-leading per GE-20260607-a4d78a; `drafthouse/debate/` prefix required for
   `DebateChannelBackendFactory` routing — channels without this prefix fall through to
   `ReviewerChannelBackendFactory` which attaches the LLM reviewer backend)
3. Check idempotency — search active sessions in registry for matching channel name.
   If found, return existing session summary
4. Create Qhorus channel with the deterministic name
5. Set `debateSessionId = channel.id().toString()` (UUID — required by `resolveSession()`
   which calls `UUID.fromString()`)
6. Create `DebateSession` with channel ID and session ID
7. Read `.spec-path`, add spec as primary document
8. `registry.put(session)` — before `channelGateway.initChannel()` (same ordering
   constraint as `start_debate` — factory reads from registry during synchronous CDI event)
9. Register REV and IMP instances via `sender(session, AgentType.REV)` and
   `sender(session, AgentType.IMP)` — required before `MessageService.dispatch()` which
   validates the sender ID against registered instances
10. `channelGateway.initChannel(channel.id(), new ChannelRef(...))` — triggers
    `DebateChannelBackendFactory` registration
11. Call `WorkspaceReplayAdapter.replay()` — parse, dispatch, batch push
12. Broadcast `session-created` event

**Browser connection:** The `session-created` broadcast triggers auto-connect via existing
WebSocket subscription. Manual connection via `?debate={debateSessionId}`.

**Diff panel:** Loads spec file from `.spec-path` in single-file mode. No B-side for replay
(git history comparison is out of scope for #95).

## Protocols

The following project protocols apply to this work:

| Protocol | Relevance |
|----------|-----------|
| PP-20260607-508f7b (actor type) | Adapter sets `actorType` on messages, not sender strings |
| PP-20260610-a47ef5 (apply must not throw) | New `statusAfter()` cases must be defensive — unknown entry types return null |
| PP-20260608-d94c7d (sentinel encoding) | All entries use `DebateProtocol.META_SENTINEL`, never hardcoded strings |
| PP-20260604-6e8d5d (MCP error strings) | `load_workspace` returns `"error: ..."` on failure, never throws |

## Testing Strategy

### Unit — WorkspaceParserTest

Fixture files in `server/runtime/src/test/resources/fixtures/workspace-replay/` — a
minimal workspace with 2 rounds, 3 issues, mix of FIXED/REJECTED/ESCALATED responses,
one confirmation cycle.

Tests:
- Issue extraction from reviewer markdown
- Response extraction from implementor markdown (FIXED/REJECTED/ESCALATED)
- Confirmation extraction from cross-round reviewer markdown
- Known section headings skipped
- Section ref extraction (both `§N.N` and `Section N.N` forms)
- Signal extraction from last 10 lines
- Assumption and settled decision extraction
- Tracker.md parsing: status extraction, evidence extraction, missing fields
- Edge cases: existing issue IDs skipped, empty body handling

### Integration — WorkspaceReplayAdapterTest

Quarkus `@QuarkusTest`. Verifies:
- Channel created with correct slug
- Entries dispatched in correct order
- Projection folds into expected `ConversationState`
- VERIFIED and DEFERRED statuses correctly derived
- Point count and status distribution match expectations

### E2E — WorkspaceReplayE2ETest

Playwright test with a trimmed workspace fixture (3-4 issues, not full 18). Verifies:
- Review points appear in tracker panel with correct status icons
- VERIFIED points show `✓✓` icon
- Section highlight works on at least one point with §-reference
- Debate panel shows threaded conversation for a multi-round issue
- Progress bar reflects resolved count

Follows existing E2E protocols: `playwright-page-lifecycle`, `playwright-render-complete-signal`,
`playwright-jvm-warmup`.

## Files to Create / Modify

| File | Change |
|------|--------|
| `server/runtime/.../debate/WorkspaceParser.java` | **New.** Java port of parser.py regex extraction |
| `server/runtime/.../debate/WorkspaceReplayAdapter.java` | **New.** Orchestrates parse → channel dispatch → WebSocket push |
| `server/runtime/.../DebateMcpTools.java` | **Modify.** Add `load_workspace` MCP tool |
| `server/api/.../debate/EntryType.java` | **Modify.** Add VERIFIED, DEFERRED |
| `server/runtime/.../debate/DebateChannelProjection.java` | **Modify.** statusAfter + renderer config for VERIFIED, DEFERRED |
| `server/runtime/.../webui/src/panels/drafthouse-review-tracker.js` | **Modify.** VERIFIED/DEFERRED in status maps |
| `server/runtime/src/test/.../debate/WorkspaceParserTest.java` | **New.** Unit tests for parser |
| `server/runtime/src/test/.../debate/WorkspaceReplayAdapterTest.java` | **New.** Integration test |
| `server/runtime/src/test/.../e2e/WorkspaceReplayE2ETest.java` | **New.** Playwright E2E test |
| `server/runtime/src/test/resources/fixtures/workspace-replay/` | **New.** Fixture workspace |
