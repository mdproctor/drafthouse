# Channel-based HIL — Design Spec

**Issue:** #100
**Date:** 2026-07-29
**Branch:** issue-100-channel-based-hil

## Summary

Enable concurrent human-in-the-loop participation in adversarial debates. Instead
of the stop-the-world FLAG_HUMAN model, the human participates as a first-class
channel participant — commenting, overriding, raising points, reprioritising, and
batch-approving alongside running agents.

Human actions flow into the Qhorus channel (for real-time display in all panels)
and are written to `decisions/human-round-{n}.md` files in the workspace directory
(for design-review agents to read at round start).

## Domain Model Changes

### AgentType (api module)

Add `HUMAN` to the enum:

```java
public enum AgentType {
    REV, IMP, SUPERVISOR, MODERATOR, SELECTOR, HUMAN
}
```

The human is a distinct debate participant, not an alias for an existing role.
Platform `ActorType.HUMAN` already exists and is set on every human message dispatch.

### EntryType (api module)

Add three new entry types:

```java
COMMENT,          // response to a point, no status change
HUMAN_OVERRIDE,   // human terminal status override
REPRIORITISE,     // priority change on existing point
```

Existing types reused:
- `RAISE` — for human-raised points (distinguished by `AgentType.HUMAN` in meta)
- `VERIFIED` / `DEFERRED` — for batch accept/reject

### DebateChannelProjection

Extend the hooks in `statusAfter()`:
- `"COMMENT"` → `null` (thread entry, no status change)
- `"HUMAN_OVERRIDE"` → `"HUMAN_OVERRIDE"` (terminal)

Override `apply()` to intercept `REPRIORITISE` before base class processing (same
pattern as `ROUND_SNAPSHOT`). The override parses the priority string from meta
using `Priority.valueOf()` directly (note: `parsePriority()` is private in the
base class and cannot be called from subclasses), then delegates to a new
`ConversationFold.reprioritisePoint()` method:

```java
reprioritisePoint(state, pointId, messageId, messageType, sender, createdAt,
                  role, round, newPriority, content)
```

The method:
1. Returns state unchanged if `pointId` is not found
2. Reads the existing point's `PointClassification`, builds a new one with
   the updated `Priority` (preserving scope and location)
3. Adds a `ThreadEntry` with `entryType="REPRIORITISE"` — records who changed
   the priority, when, and why (content from the dispatch)
4. Returns a new `ConversationState` with the updated point

This matches the pattern of `respondToPoint()`: both update state AND leave a
trace in the point's thread history. Without the ThreadEntry, priority changes
would be invisible in the projected `ConversationState` that agents consume via
`getDebateSummary` — agents would see the new priority but no record of the change.

Wrapped in try-catch per PP-20260610-a47ef5.

**Cross-module dependency:** Requires adding `reprioritisePoint()` to
`ConversationFold` in casehub-agency (SNAPSHOT dependency). This follows the
established pattern — every state mutation goes through ConversationFold methods.

Update `DEBATE_CONFIG`:
- `statusEmoji`: `"HUMAN_OVERRIDE" → "👤"`
- `resolvedStatuses`: add `"HUMAN_OVERRIDE"` (terminal)
- `entryTypeLabel`: `"COMMENT" → "commented"`, `"HUMAN_OVERRIDE" → "overrode"`, `"REPRIORITISE" → "reprioritised"`
- `roleLabel`: add `"HUMAN" → "HUM"`

### DebateSession

Add `volatile String workspacePath` field, set when a design-review workspace is
associated with the session. Null if no workspace loaded — file writing silently
skipped.

Include `workspacePath` in `DebateSessionSnapshot` and restore it in
`fromSnapshot()` for restart resilience — avoids silent loss of file-writing
capability after server restart.

Human participant registered lazily via `registerIfAbsent(HUMAN, ...)` on first
action. Deregistered by existing `endDebate` cleanup (iterates `participants()`).

### DebateStreamEntry

No changes needed — `EntryType.valueOf()` and `AgentType.valueOf()` handle new
values automatically.

## REST API — HumanActionResource

New JAX-RS resource at `/api/debate/{id}/human`. All endpoints:
1. Parse session UUID, resolve DebateSession (404 if missing)
2. Register human sender via `DebateParticipants.ensureSender(session, HUMAN, ...)`
3. Derive current round from projected channel state (max round across points)
4. Encode with `DebateProtocol.META_SENTINEL`
5. Dispatch via `messageService.dispatch(...)` with `actorType(ActorType.HUMAN)`
6. Write to decisions file via `DecisionFileWriter`
7. Return `200 {"status":"ok"}` or `400`/`404`

### Endpoints

**POST /api/debate/{id}/human/comment**
```json
{ "pointId": "uuid", "content": "string" }
```
Dispatches `COMMENT` entry. `inReplyTo` resolved via `findByCorrelationId(pointId)`.
MessageType: `RESPONSE`.

**POST /api/debate/{id}/human/raise**
```json
{
  "content": "string",
  "priority": "P1|P2|P3",
  "location": "string (optional)",
  "side": "A|B (optional)",
  "startLine": 45,
  "endLine": 52,
  "selectedText": "string (optional)"
}
```
Dispatches `RAISE` entry with `role=HUMAN`. Generates a new `correlationId` (pointId).
Selection fields stored in meta if present. MessageType: `QUERY`.

**POST /api/debate/{id}/human/override**
```json
{ "pointId": "uuid", "reason": "string" }
```
Dispatches `HUMAN_OVERRIDE` entry. `inReplyTo` resolved via `findByCorrelationId`.
MessageType: `DONE`.

**POST /api/debate/{id}/human/prioritise**
```json
{ "pointId": "uuid", "newPriority": "P1|P2|P3" }
```
Dispatches `REPRIORITISE` entry. REST endpoint converts P1→HIGH, P2→MEDIUM,
P3→LOW and stores using the standard `ConversationProtocol.PRIORITY` key in meta.
The `apply()` override parses this via `Priority.valueOf(meta.get("priority").toUpperCase())`
and passes the typed `Priority` to `reprioritisePoint()`. `inReplyTo` resolved via
`findByCorrelationId`. MessageType: `RESPONSE`.

**POST /api/debate/{id}/human/batch**
```json
{ "pointIds": ["uuid1", "uuid2"], "verdict": "VERIFIED|DEFERRED" }
```
Dispatches one entry per pointId with the verdict as entry type. All with
`actorType(ActorType.HUMAN)`, `role=HUMAN`. MessageType: `DONE` for VERIFIED,
`DECLINE` for DEFERRED.

### Input Validation

All endpoints validate inputs and return 400 with a descriptive error:
- Session UUID must be valid and resolve to an active session (404 if not)
- `pointId` must reference an existing point in the projected state
- `content` must be non-null and non-blank where required (comment, raise, override)
- `priority` / `newPriority` must be one of P1, P2, P3
- `verdict` must be one of VERIFIED, DEFERRED
- `pointIds` must be non-empty for batch
- `newPriority` must differ from the point's current priority
- Override on an already-resolved point returns 409 (Conflict)

Validation follows the same pattern as `DebateMcpTools` (parse enums, check session
existence, validate point references via projected state).

### Dependencies

Injected: `DebateSessionRegistry`, `MessageService`, `InstanceService`,
`ProjectionService`, `DebateChannelProjection`, `DecisionFileWriter`.

WebSocket push is not needed — `DebateChannelBackend.post()` automatically converts
dispatched messages to `DebateStreamEntry` and pushes via `eventBus.pushDebateEntries()`.
Context tracking (`DebateEventResource`) is irrelevant for human actions.

### Round Derivation

Human actions don't carry an explicit round. The resource projects the channel
state via `ProjectionService` and takes the max round number from point entries.
Defaults to 1 if no entries exist.

### sender() Pattern

Extracted to `DebateParticipants.ensureSender(session, role, instanceService, registry)`:
a package-private static utility method in the runtime module, shared by both
`DebateMcpTools` and `HumanActionResource`. The method registers the instance via
`registerIfAbsent()`, using the existing `DebateSession.instanceId()` convention,
and persists the session via `registry.persist()` on first registration. Without
the persist call, new participants don't survive restart via `fromSnapshot()`.

## Decisions File Writer

### DecisionFileWriter

Utility class that appends human decisions to structured markdown files in the
workspace's `decisions/` directory.

```java
decisionFileWriter.append(workspacePath, round, section, pointId, content)
```

- Creates `decisions/` directory if it doesn't exist
- Opens `decisions/human-round-{n}.md` — creates with header if new, appends if exists
- Appends entry under the correct `## Section` heading (Comments, Overrides,
  New Points, Priority Changes, Batch Decisions)
- Section heading written once per type per round (on first entry of that type)
- Synchronous file I/O with `synchronized` on the round file path — prevents
  interleaving when batch operations dispatch multiple entries concurrently
- Silently skipped if `session.workspacePath()` is null

### File Format

```markdown
# Human Decisions — Round 3

## Comments

### R1-05
The original wording was intentional — this is a legal requirement.

## Overrides

### R1-03 → HUMAN_OVERRIDE
Both agents missed that this is required by compliance.

## New Points

### H-<uuid-short> — §3.2, lines 45-52
**Priority:** P1
The error handling path doesn't account for network timeouts.

## Priority Changes

### R1-07 → P1 (was P3)
This is more critical than the reviewer assessed.

## Batch Decisions

Approved: R1-01, R1-02, R1-04
```

### WorkspaceWatcher Interaction

The watcher's `RESPONSE_FILE` pattern matches `reviewer-round-N.md` and
`implementer-round-N.md`. Files in `decisions/` don't match — no loop risk.

## UI Changes

### Channel Feed (`<channel-feed>`)

- `AGENT_LABELS`: add `HUMAN → "Human"`, `SUPERVISOR → "Supervisor"`,
  `MODERATOR → "Moderator"`, `SELECTOR → "Selector"`. Remove stale `FAC` entry
  (no corresponding `AgentType` value).
- `ENTRY_TYPE_LABELS`: add `COMMENT → "commented"`, `HUMAN_OVERRIDE → "overrode"`,
  `REPRIORITISE → "reprioritised"`
- Human entries get a distinct badge colour (warm accent, not agent blue/green)
  and a `👤` icon prefix on the role badge
- No structural rendering changes — human entries are DebateStreamEntry like all others

### Review Tracker (`<review-tracker>`)

- `ENTRY_TO_STATUS`: add `HUMAN_OVERRIDE → "HUMAN_OVERRIDE"`, `COMMENT → null` (no
  status change — handled by not adding to the map)
- `STATUS_ICON`: add `HUMAN_OVERRIDE → "👤"`
- `STATUS_ORDER`: add `HUMAN_OVERRIDE` after `DEFERRED`
- `RESOLVED_STATUSES`: add `"HUMAN_OVERRIDE"`
- `ACTION_SHORT`: add `COMMENT → "commented"`, `HUMAN_OVERRIDE → "overrode"`,
  `REPRIORITISE → "reprioritised"`
- `AGENT_SHORT`: add `HUMAN → "HUM"`, `SUPERVISOR → "SUP"`, `MODERATOR → "MOD"`,
  `SELECTOR → "SEL"`. Remove stale `FAC` entry.
- **Action buttons per unresolved point:**
  - 💬 Comment — inline text input below the point
  - 👤 Override — dropdown with reason field
  - ↕ Priority — P1/P2/P3 dropdown
- Action buttons hidden on resolved points
- All buttons POST to `/api/debate/{id}/human/*` endpoints

### Document Diff (`<document-diff>`)

- **"Raise Point" button** — appears when text is selected in either panel
- Opens inline form: content field (pre-filled with selection context), priority
  dropdown (P1/P2/P3), location auto-populated from selection position
- Submit POSTs to `/api/debate/{id}/human/raise`
- Uses local selection state directly (side, startLine, endLine, selectedText)

### Batch Accept (Review Tracker)

- At round boundaries (new entries with higher round than previous), a batch
  action bar appears at the top of the tracker:
  "N low-priority points open — Accept all / Defer all"
- Dispatches `POST /api/debate/{id}/human/batch` with collected pointIds
- Only shown when 2+ unresolved LOW-priority points exist

## Protocols Honoured

| Protocol | How |
|----------|-----|
| PP-20260607-508f7b (actorType) | `actorType(ActorType.HUMAN)` on every dispatch |
| PP-20260608-d94c7d (sentinel) | All encoding via `DebateProtocol.META_SENTINEL` |
| PP-20260610-a47ef5 (apply no throw) | REPRIORITISE handler in try-catch |
| PP-20260604-6e8d5d (MCP errors) | N/A — REST endpoints use HTTP status codes |
| PP-20260608-21c69f (instance cleanup) | Human instance via registerIfAbsent, cleaned by endDebate |

## Out of Scope

- **design-review skill changes** — reading `decisions/`, prompt updates (soredium,
  separate approval required). Tracked as GitHub issue on soredium repo. Issue #100
  remains open until soredium work lands (acceptance criteria 3 and 7 depend on it).
- **Authentication** — DraftHouse runs locally, consistent with existing endpoints
- **Human action persistence beyond channel + decisions/ files** — channel projection
  already persists ConversationState
