# Document Timeline — Version Navigation Across Review Rounds

**Issue:** casehubio/drafthouse#98
**Date:** 2026-07-07
**Status:** Design approved

## Overview

Add a document timeline to DraftHouse that shows how a document evolved across review
rounds. Each round produces a git commit — the timeline makes these navigable. Clicking
any two points shows the diff between those snapshots. Clicking an issue in the review
tracker highlights the round where it was addressed.

Designed for replay of completed design-review workspaces now, but the model accommodates
future snapshot sources (live watching #99, explicit saves) without UI changes.

## API Model

### New types in `server/api/`

**`SnapshotSource`** — sealed interface discriminating how a snapshot was captured:

```java
public sealed interface SnapshotSource {
    record GitCommit(String commitHash, Instant timestamp, int roundNumber) implements SnapshotSource {}
    // Future variants: LiveRound, ExplicitSave, ContentCapture
}
```

Only `GitCommit` is implemented in this issue. The sealed interface ensures future variants
are compile-time checked. The `roundNumber` is source-specific metadata — debate rounds
are a property of the git-commit source, not a generic snapshot concern. Future variants
(ExplicitSave, ContentCapture) carry their own source-specific metadata without needing
a round number.

**`DocumentSnapshot`** — a point-in-time capture of document content:

```java
public record DocumentSnapshot(
    String documentPath,
    String label,
    SnapshotSource source
) {}
```

Content is not carried on the snapshot — it stays on the server, loaded lazily via
the snapshot endpoint. The `label` carries human-readable display text for all source
types ("Round 0 (original)", "Round 1 (+9 raised)", "Save 3", etc.).

**`DocumentTimeline`** — ordered sequence of snapshots for a document:

```java
public record DocumentTimeline(
    String documentId,
    List<DocumentSnapshot> snapshots
) {}
```

The timeline list index is the canonical key for snapshot lookup — the endpoint uses it,
and the client uses it in comparison events.

### New entry type

`ROUND_SNAPSHOT` added to `EntryType`. This is a domain-meaningful entry (not infrastructure
provenance like `RESTART_CONTEXT`) — it marks a round boundary with version metadata.

Like `RESTART_CONTEXT`, `ROUND_SNAPSHOT` is a system-level boundary marker with no agent
role. `DebateStreamEntry.from()` allows null `agentRole` for both `RESTART_CONTEXT` and
`ROUND_SNAPSHOT`.

The `DHMETA:` sentinel carries: `entryType=ROUND_SNAPSHOT`, `round=N`, `commitHash=<hash>`,
`documentPath=<path>`, `timestamp=<iso>`. The content body carries a human-readable summary:
`"Round N snapshot — N issues raised, N fixed"`.

### DebateStreamEntry changes

`DebateStreamEntry` gains two nullable fields for timeline metadata:

```java
public record DebateStreamEntry(
    EntryType entryType, AgentType agentRole, int round,
    String content, String pointId, String subTaskId,
    Priority priority, String scope, String location,
    String sender, Instant timestamp,
    String commitHash,      // non-null only for ROUND_SNAPSHOT
    String documentPath     // non-null only for ROUND_SNAPSHOT
) { ... }
```

The `from()` factory method is updated to extract `commitHash` and `documentPath` from the
DHMETA meta map when `entryType` is `ROUND_SNAPSHOT`. For all other entry types, these
fields are null. This follows the existing pattern — `subTaskId`, `priority`, `scope`, and
`location` are already null for most entry types.

The null-role guard is updated:

```java
} else if (entryType != EntryType.RESTART_CONTEXT
        && entryType != EntryType.ROUND_SNAPSHOT) {
    return null;
}
```

## Server-Side Components

### WorkspaceParser changes

`ParsedTrackerEntry` gains a structured `commitHash` field parsed from the existing
`**Spec commit:** → <hash>` format. The `evidence` field stays for backward compatibility —
`commitHash` is extracted from it when present, null otherwise.

`WorkspaceParseResult` gains a `projectRepoPath` field derived from `.source-dirs` (first
entry). This provides the git context needed for round 0 derivation and `git show` content
loading, since the design-review workspace (`~/adr/...`) is a different git repository from
the project repo where the spec lives.

`ParsedRound` gains no new fields — round-to-commit mapping is derived by the replay
adapter by grouping tracker entries by their resolution round.

### WorkspaceReplayAdapter changes

After dispatching all entries for a round, the adapter emits a `ROUND_SNAPSHOT` entry:

1. **Extract commit hashes from tracker** — each `ParsedTrackerEntry` has a `commitHash`
   and was responded to in a specific round (known from `responses/implementor-N.md`
   processing order). Group entries by response round. All issues addressed in the same
   round typically share the same commit hash — take the first non-null hash found for
   that round. If a round has no tracker entries with commit hashes (e.g., a round that
   only raised issues), no snapshot is emitted for it.
2. **Round 0 (original document)** — derived via `git log --reverse <specPath>` run against
   the project repo directory (from `WorkspaceParseResult.projectRepoPath()`).
3. **Pre-load content** — at parse time, run `git show <hash>:<specPath>` for each round's
   commit hash, using the project repo as the git working directory. Store in a
   `Map<Integer, String>` (timeline index → content) on the `ReplayResult`.

`ReplayResult` gains a `snapshotContent` field and a `timeline` field:

```java
public record ReplayResult(
    int entryCount,
    Map<String, String> statusDistribution,
    DocumentTimeline timeline,
    Map<Integer, String> snapshotContent   // timeline index → document content
) {}
```

The caller (session manager / MCP tool layer) stores the `snapshotContent` map alongside
the session for the snapshot endpoint to read. Content is derived data — on server restart,
the workspace can be re-replayed to regenerate it. Persistence of snapshot content is
explicitly not required; re-replay is the recovery mechanism.

### Snapshot endpoint

`GET /api/debate/{id}/snapshot/{index}` — returns document content at the given timeline
index as plain text. Reads from the content map stored alongside the session (populated
by `ReplayResult.snapshotContent()`). Returns 404 if index doesn't exist.

### Projection changes

`ConversationState` is **not modified** — it is a platform type in `casehub-blocks`
(`io.casehub.blocks.conversation`), and adding DraftHouse-specific timeline state would
violate the application boundary established by the P5 conversation-protocol extraction.

`DebateChannelProjection` overrides `apply()` to intercept `ROUND_SNAPSHOT` entries before
they reach the base class dispatch:

```java
@Override
public ConversationState apply(ConversationState state, MessageView message) {
    try {
        Map<String, String> meta = ChannelMessageMeta.parseMeta(sentinel(), message.content());
        if ("ROUND_SNAPSHOT".equals(meta.get(ConversationProtocol.ENTRY_TYPE))) {
            return state;  // timeline marker — no conversation state change
        }
    } catch (Exception e) {
        LOG.log(Level.WARNING, "ROUND_SNAPSHOT check failed — delegating to base", e);
    }
    return super.apply(state, message);
}
```

The try-catch preserves the PP-20260610-a47ef5 contract: `apply()` must never throw. The
base class wraps its own `doApply()` in a catch block; this override must do the same for
any work it performs before delegating. On exception, the override falls through to
`super.apply()` which applies its own protected dispatch.

For non-ROUND_SNAPSHOT messages, meta is parsed twice (once here, once in `doApply()`).
This is redundant but not a correctness concern at DraftHouse scale — the meta header is
~100 characters and the session processes hundreds of messages, not millions. A
`skipEntryTypes()` hook in `ConversationProjection` would eliminate the double-parse, but
that is a platform change deferred to casehubio/blocks#39.

Without this override, `ROUND_SNAPSHOT` would fall through to `handleDomain()` in the base
class, which checks for a role (null → WARNING log and discard) and then tries point
dispatch (not a point initiator, no matching correlationId → discarded with warning). The
override avoids spurious warnings and makes the intent explicit.

The timeline is a **client-side concern** — the browser builds it from filtered stream
entries. No server-side projection state is needed.

### WebSocket delivery

No new event topic. `ROUND_SNAPSHOT` entries flow through the existing `debate-entries`
topic. The timeline panel filters for them client-side.

## Timeline Panel (`<drafthouse-timeline>`)

### Component structure

A Shadow DOM web component. Renders as a thin horizontal strip (~40px) positioned above
the diff panel in the layout via `rows()` composition in `index.ts`:

```
rows(
  html(timelineStrip),
  hostPanel('diff-panel')
)
```

### Visual design

Horizontal track with round markers connected by a line:

```
● Round 0 ──────● Round 1 (+9) ──────● Round 2 (+1) ──────● Round 3 (verified)
  (original)       9 raised             1 fixed              all verified
```

Each marker shows the round number and a compact status summary. The active comparison
is highlighted — two markers connected by a coloured bar showing the range the diff
panel is displaying.

### Interaction model

- **Default state:** adjacent comparison — latest round vs its predecessor
- **Click a marker:** sets it as one end of the comparison. Pairs with the adjacent
  marker if no other end is selected.
- **Shift-click:** selects a second marker without clearing the first — for non-adjacent
  comparison
- **Issue trail highlight:** when the review tracker emits `point-selected`, the timeline
  highlights the round where the fix was applied (bold marker) and shows lighter dots on
  raise and verify rounds. The `point-selected` event is enriched with per-phase round
  data: `{ pointId, raiseRound, fixRound, verifyRound, location }`. The review tracker's
  `#derivePoints()` already has the full entry sequence per pointId — the raise entry
  provides `raiseRound`, the QUALIFY/COUNTER response provides `fixRound`, and the
  VERIFIED/AGREE confirmation provides `verifyRound` (null if not yet verified). No
  additional data source is needed.

### Events

- **Subscribes to:** `debate-entries` (filters `ROUND_SNAPSHOT`), `point-selected` /
  `point-deselected` (from review tracker)
- **Emits:** `timeline-comparison-changed` with `{ sessionId, indexA, indexB, labelA, labelB }`
  — labels are sourced from the `DocumentSnapshot.label()` values the timeline panel holds
  from its ROUND_SNAPSHOT-derived timeline data. Including labels in the event avoids the
  diff panel needing a separate metadata lookup, since timeline indices are not equivalent
  to round numbers (a round with no commits produces no snapshot, so indices may skip).

## Diff Panel Integration

The diff panel listens for `timeline-comparison-changed`. On receiving it:

1. Reads `sessionId` from the event, calls `GET /api/debate/{sessionId}/snapshot/{indexA}`
   and `GET /api/debate/{sessionId}/snapshot/{indexB}`
2. Renders via a new `loadContent(panel, content, label)` public method alongside the
   existing `loadFile(panel, path)`
3. Reads `labelA` and `labelB` from the event detail and uses them as header labels
   (e.g., "Round 0 (original)" / "Round 1 (+9 raised)") — the diff panel does not
   derive labels from indices

## Data Flow

```
WorkspaceParser                    WorkspaceReplayAdapter
  ├── parse tracker.md               ├── for each round:
  │   └── extract commitHash            │   ├── dispatch RAISE/QUALIFY/etc (existing)
  │       from "Spec commit: → hash"    │   └── dispatch ROUND_SNAPSHOT entry
  ├── read .source-dirs                 │       (entryType, round, commitHash, documentPath)
  │   └── projectRepoPath              ├── pre-load: git show <hash>:<specPath>
  └── return WorkspaceParseResult       │   (using projectRepoPath for git context)
                                        └── return ReplayResult with timeline + snapshotContent

DebateChannelProjection             Browser
  ├── apply(ROUND_SNAPSHOT)           ├── <drafthouse-timeline>
  │   └── return state unchanged         │   ├── debate-entries → filter ROUND_SNAPSHOT
  │       (no ConversationState change)  │   │   → build timeline from commitHash,
  └── other entries → super.apply()      │   │     documentPath, round, label
                                         │   └── emit timeline-comparison-changed
                                      ├── <drafthouse-diff>
                                         │   ├── listen timeline-comparison-changed
                                         │   │   (indexA, indexB, labelA, labelB)
                                         │   ├── GET /api/debate/{id}/snapshot/{index}
                                         │   └── loadContent(content, label) → render diff
                                      └── <drafthouse-review-tracker>
                                             └── point-selected → timeline trail
                                                 (pointId, raiseRound, fixRound,
                                                  verifyRound, location)
```

## Backward Compatibility

Workspaces without `**Spec commit:**` fields produce no `ROUND_SNAPSHOT` entries. The
timeline panel renders nothing — it is absent, not empty. No error state needed.

## Future Extensibility

- **#99 Live watching:** the live adapter emits `ROUND_SNAPSHOT` entries as rounds complete,
  using the same entry type and event flow. The timeline panel grows incrementally.
- **#97 Chunked orchestration:** more rounds means more timeline markers. The visual design
  handles arbitrary round counts — no fixed-width assumptions.
- **New snapshot sources:** add variants to `SnapshotSource` (`LiveRound`, `ExplicitSave`,
  `ContentCapture`). The timeline panel and diff integration are source-agnostic — they
  operate on `DocumentSnapshot`, not on `SnapshotSource` internals. Each source carries
  its own metadata (round number for debates, save ID for explicit saves, content hash
  for captures) without forcing a common field.

## Testing Strategy

### Unit tests

- **WorkspaceParser** — `commitHash` extraction from `**Spec commit:** → <hash>`, null when
  absent, backward compat with old tracker format, `projectRepoPath` from `.source-dirs`
- **DebateChannelProjection** — `ROUND_SNAPSHOT` entries are skipped (state unchanged, no
  warning logged), other entry types continue to fold normally
- **DebateStreamEntry** — `ROUND_SNAPSHOT` entries parse with null `agentRole`, `commitHash`
  and `documentPath` extracted from meta; non-snapshot entries have null for these fields
- **DocumentSnapshot / SnapshotSource** — record equality, sealed interface variant handling

### Integration test

- **WorkspaceReplayAdapter** — replay a workspace with tracker commit hashes, verify
  `ROUND_SNAPSHOT` entries appear in the channel message stream at round boundaries,
  verify `ReplayResult.snapshotContent()` is populated, verify
  `ReplayResult.timeline()` contains correct snapshots

### E2E tests (Playwright)

- **Timeline renders** — replay a workspace, verify timeline strip appears with correct
  number of round markers and issue counts
- **Timeline navigation** — click two markers, verify diff panel updates to show the delta
  between those rounds (labels show "Round N" format)
- **Review tracker → timeline** — click an issue in the review tracker, verify the
  corresponding round marker is highlighted

### Test fixtures

E2E tests need a fixture workspace directory with git history (2-3 rounds, tracker with
`**Spec commit:**` fields, commits matching those hashes). Unit and integration tests
work with in-memory data.
