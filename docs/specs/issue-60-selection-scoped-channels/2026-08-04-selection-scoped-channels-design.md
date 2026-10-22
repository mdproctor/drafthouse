# Selection-Scoped Conversation Channels — Design

**Issue:** casehubio/drafthouse#60
**Date:** 2026-08-04
**Status:** Approved

## Problem

DraftHouse debate sessions route all conversation through a single Qhorus
channel. When a human or agent wants to discuss a specific document region,
the selection scope is ephemeral — set on the session, appended to summaries,
then replaced by the next selection. There is no way to maintain a persistent
conversation anchored to a specific document region, and no way to have
multiple concurrent conversations about different parts of the document.

## Decision Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Thread identity | Explicit creation (UUID) | Selection is the visual anchor, not the identity. Consensus across Google Docs, GitHub, GitLab, Figma. Overlap handled in UI. |
| Channel model | Same channel + threadId metadata | Thread is a grouping concern, not an infrastructure boundary. Avoids channel proliferation. Cross-thread queries stay O(1). |
| UI layout | Dedicated threads panel | Fits panel-based architecture. Gives room for multi-turn conversations. Bidirectional linking with diff is the key UX. |
| Thread vs debate | Independent | Threads don't participate in rounds or priorities. Two filtered views of the same channel. |
| Thread lifecycle | open → resolved | Simple. Any participant can resolve. No priority/verified/deferred. Persistence via Qhorus. |
| Approach | Thread-as-Domain-Concept | First-class `SelectionThread` type, dedicated projection and MCP tools. Main debate projection gains a threadId skip filter; DebateChannelBackend gains thread-aware event routing. |

## Architecture

### Domain Model (server/api/)

New types:

```java
record SelectionThread(String threadId, SelectionScope anchor, ThreadStatus status)
enum ThreadStatus { OPEN, RESOLVED }
record ThreadEntry(String threadId, String sender, String content,
                   String agentRole, Instant timestamp)
```

`DebateSession` additions (thread metadata only — conversation content lives
exclusively in the projection, not in DebateSession):
- `Map<String, SelectionThread> threads` — thread metadata registry keyed by threadId (ID, anchor, status — no conversation entries)
- `startThread(SelectionScope anchor) → String` — creates thread, returns UUID
- `resolveThread(String threadId)` — sets status to RESOLVED
- `findThreadsNear(SelectionScope scope) → List<SelectionThread>` — finds threads whose anchors overlap the given scope (same side, any line overlap: `anchor.side == scope.side && anchor.startLine <= scope.endLine && anchor.endLine >= scope.startLine`)

**Source of truth:** `DebateSession.threads` holds metadata for fast MCP tool
validation (does thread exist? is it OPEN?). `ThreadProjection` is the sole
authority for conversation content (entries, ordering, rendering). These are
complementary, not competing — same pattern as `DebateSession.participants`
(metadata) vs `DebateChannelProjection` (conversation state).

### Message Encoding

Thread messages use the existing `DHMETA:` sentinel and `ChannelMessageMeta.encode()` pattern. Two new metadata keys:

| Key | Values | Purpose |
|-----|--------|---------|
| `threadId` | UUID string | Partition key — present on all thread messages |
| `threadAction` | `START`, `REPLY`, `RESOLVE` | Lifecycle action (no REOPEN — simple lifecycle, backward-compatible to add later) |

The `START` message's metadata also carries the selection anchor: `side`, `startLine`, `endLine`, `selectedText`.

All thread messages go through the debate session's existing Qhorus channel.
The `threadId` field is the partition key — its presence distinguishes thread
messages from main debate messages.

### Projection

**`ThreadProjection`** — new `ChannelProjection<ThreadState>`:

```
ThreadState:
  threads: Map<String, ThreadView>

ThreadView:
  threadId: String
  anchor: SelectionScope
  status: ThreadStatus
  entries: List<ThreadEntry>    // ordered by timestamp
  createdBy: String             // agentRole of the START sender
```

Behaviour:
- `apply()` checks for `threadId` in metadata — absent → return state unchanged (skip main debate messages)
- `START` → create new ThreadView with anchor parsed from metadata
- `REPLY` → append ThreadEntry to existing thread
- `RESOLVE` → update status to RESOLVED
- Follows `channel-projection-apply-must-not-throw` protocol

**`DebateChannelProjection` change:** skip messages with `threadId` in metadata.
This keeps thread entries out of debate summaries, review tracker, and round-grouped feed.

`ThreadProjection.render()` produces markdown of all threads grouped by
document side — used by `get_thread_summary` MCP tool and export.

### MCP Tools

New `ThreadMcpTools` class (separate from `DebateMcpTools`):

**`start_thread`**
- Params: `debateSessionId`, `agentRole`, `side`, `startLine`, `endLine`, `selectedText`, `content`
- Creates UUID threadId, encodes `threadAction=START` + anchor in metadata
- Dispatches to the session's channel
- Registers `SelectionThread` on the `DebateSession`
- Returns `{threadId, status: "created", nearbyThreads: [...]}`
- Nearby threads found via `findThreadsNear()` — caller decides whether to reply to existing

**`reply_to_thread`**
- Params: `debateSessionId`, `agentRole`, `threadId`, `content`
- Validates thread exists and is OPEN
- Encodes `threadAction=REPLY` + threadId, dispatches
- Returns `{status: "dispatched"}`

**`resolve_thread`**
- Params: `debateSessionId`, `threadId`
- Any participant can resolve
- Encodes `threadAction=RESOLVE`, dispatches
- Returns `{status: "resolved"}`

**`get_thread_summary`**
- Params: `debateSessionId`, optional `threadId`
- With threadId: returns that thread's entries
- Without: returns all threads with anchors and statuses
- Projects channel through `ThreadProjection`

### REST Endpoints (Browser-Initiated)

On `HumanActionResource`, under `/api/debate/{debateSessionId}/human/`:

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/thread` | Start a thread (fields mirror `start_thread` MCP) |
| POST | `/thread/{threadId}/reply` | Reply to thread |
| POST | `/thread/{threadId}/resolve` | Resolve thread |

These follow the existing HIL pattern — dispatch with `ActorType.HUMAN`,
`AgentType.HUMAN` role, same as comment/raise/override.

### WebSocket Events

`DebateChannelBackend.post()` currently creates `DebateStreamEntry` and pushes
via `eventBus.pushDebateEntries()`. For thread messages:

| Event | Trigger | Payload |
|-------|---------|---------|
| `thread-entries` | Any thread message | `ThreadStreamEntry[]` |
| `thread-created` | START message | `{threadId, anchor, createdBy}` |
| `thread-resolved` | RESOLVE message | `{threadId}` |

The backend checks for `threadId` in metadata and routes to `thread-entries`
instead of `debate-entries`. The debate-feed panel remains unchanged.

**`ThreadStreamEntry`** — a separate type from `DebateStreamEntry`, with
thread-specific fields:

```typescript
interface ThreadStreamEntry {
  threadId: string;
  threadAction: string;  // START, REPLY, RESOLVE
  content: string;
  agentRole: string;
  timestamp?: string;
  // START-only fields:
  anchor?: { side: string; startLine: number; endLine: number; selectedText: string };
}
```

Thread entries use `threadAction` (START/REPLY/RESOLVE) instead of debate's
`entryType` (RAISE/AGREE/COUNTER etc.). Separate types avoid union-type
confusion and make each panel's contract explicit.

### UI

**`<selection-threads>` panel** — new LitElement in `@casehubio/agency-ui-document-workbench`:

- **Thread list view** (default): compact cards showing anchor text snippet,
  status badge (open/resolved), reply count, latest reply preview. Sorted by
  document position. Resolved threads dimmed/collapsed.
- **Thread detail view** (on click): full conversation entries, reply input
  field, resolve button, back button.

Subscribes to `thread-entries`, `thread-created`, `thread-resolved`, `reconnected`.

**`<document-diff>` integration:**

1. **Gutter markers** — colored indicators at thread anchor line ranges.
   Accent for open, neutral for resolved.
2. **Click-to-navigate** — clicking a gutter marker dispatches `thread-selected`
   custom event. Threads panel scrolls to that thread.
3. **Reverse navigation** — threads panel dispatches `thread-focused`. Diff
   panel scrolls to the anchor region and briefly highlights it.

**Thread creation flow (browser):**
1. User selects text in diff (existing mouseup flow)
2. "Start Thread" button appears
3. User types content, submits → `POST /human/thread`
4. If nearby threads exist: "1 existing thread nearby" hint with link

**Workbench layout:** threads panel slots into the right column alongside
debate-feed, likely as a tabbed view (Debate / Threads) or secondary panel.
Registered via `registerPanel()`.

## Protocols

Applicable protocols for implementation:

- `channel-projection-apply-must-not-throw` — ThreadProjection.apply() must never throw
- `channel-projection-actor-type` — classify actors via actorType(), not sender strings
- `debate-message-sentinel-encoding` — use DebateProtocol.META_SENTINEL for encoding

## Garden Context

Relevant entries consulted during design:

- GE-20260529-d1397c — Qhorus ChannelBackend registration via ChannelInitialisedEvent
- GE-20260704-73bebb — pages event op skips lastSeq tracking (full catch-up on reconnect)
- GE-20260724-7b07f5 — replace event bus request-reply with direct injection
- GE-20260620-768950 — WebSocket buildAsync().join() timing
- GE-20260620-04450c — waitForSession() barrier pattern

## Persistence

`DebateSessionSnapshot` must include thread state to survive restarts.
Add `Map<String, SelectionThread> threads` to the snapshot record. The
`DebateSession.snapshot()` method captures the threads map alongside
documents and participants. `DebateSession.fromSnapshot()` restores it.

Thread message content is already durable via the Qhorus channel — the
`ThreadProjection` can reconstruct `ThreadView` entries from channel
messages. The snapshot only needs thread metadata (ID, anchor, status)
for fast session reconstitution without full projection.

## Out of Scope

- Thread-to-thread cross-references (cite another thread from within a thread)
- Thread assignment (assign a thread to a specific agent/human)
- Thread notifications (email/push when a thread gets a reply)
- Agent auto-engagement (agent automatically starts threads on findings) — future follow-up
- Document change tracking (re-anchoring threads when the document changes)
