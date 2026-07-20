# Live Workspace Watching — Design Spec

**Issue:** #99
**Date:** 2026-07-20
**Epic:** #93 (Document Workbench)

## Problem

`load_workspace` replays completed design-review workspaces as interactive debate
sessions. But design-review runs take 10-30 minutes across multiple rounds — there
is no way to watch a review in progress. The user must wait for completion, then
load the workspace after the fact.

## Solution

Extend `load_workspace` to auto-detect whether a review is still running. If it is,
replay historical rounds (existing behaviour), then start a `WorkspaceWatcher` that
monitors the workspace directory for new files. When a new response file appears,
parse the new round and dispatch debate entries in near-real-time. Progress updates
from `progress.log` are pushed as metadata events on a separate topic.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Trigger | Auto-detect in `load_workspace` | Single entry point — user doesn't need to know if the review is running or complete |
| File watching | `io.methvin:directory-watcher:0.18.0` | Native macOS FSEvents via JNA. Cross-platform. Lightweight (no Vert.x/NIO2 polling fallback needed) |
| Progress events | Separate metadata topic | Progress is transient operational status, not part of the review conversation. Keeps the debate feed clean |
| Component boundary | New `WorkspaceWatcher` class | Different lifecycle from replay adapter (long-lived vs one-shot). Composes with existing parsing and dispatch infrastructure |
| UI | New `<workspace-status>` topbar element | Dedicated to progress display. Context-gauge stays focused on token tracking |
| blocks-ui | Nothing to extract | All components are tied to design-review workspace semantics. No generic pattern worth extracting yet |

## Component Architecture

```
load_workspace (MCP tool — DebateMcpTools)
  │
  ├─► WorkspaceParser.parse()           — full workspace parse (existing)
  ├─► WorkspaceReplayAdapter.replay()   — dispatch historical rounds (existing)
  │
  └─► if review still running:
      └─► WorkspaceWatcher.start()      — watch for new files
            ├─► new response file  → parseRound(N) → dispatch debate entries
            ├─► progress.log change → tail new lines → push metadata
            └─► terminal state     → stop watching, push completion event
```

### WorkspaceWatcher

New class: `io.casehub.drafthouse.debate.WorkspaceWatcher`

**Responsibilities:**
- Hold a `DirectoryWatcher` handle from `io.methvin:directory-watcher`
- Track state: `lastReplayedRound`, `progressLogOffset`, `existingIssueIds`,
  `raiseMessageIds`, `lastMessageId`, `processedFiles` (dedup set, keyed by
  filename stem e.g. `"reviewer-3"`, `"implementor-3"`),
  `previousTrackerStatuses` (for DEFERRED/evidence diffing)
- On new response file: parse single round via WorkspaceParser, dispatch entries
  via extracted `WorkspaceReplayAdapter` methods
- On progress.log change: tail new lines, parse and push metadata events
- On terminal state: stop watching, push completion event, remove from registry

**Dependencies (constructor-injected):**
- `WorkspaceReplayAdapter` — dispatch individual round entries (shared with replay)
- `WebSocketEventBus` — push debate entries and metadata to browser
- `DebateSession` — the session being watched (senders obtained via
  `session.instanceIdFor(AgentType.REV)` / `.IMP`)
- `String tenancyId` — captured from `channel.tenancyId()` at construction time
- `Runnable onComplete` — callback to remove watcher from `activeWatchers` map
  (provided by `DebateMcpTools`: `() -> activeWatchers.remove(session.debateSessionId())`)

**Lifecycle:**
- `start(Path workspacePath, int startFromRound, Set<String> existingIssueIds,
  Map<String, Long> raiseMessageIds, long lastMessageId,
  String projectRepoPath, String specPath)` — begin watching
- `stop()` — close DirectoryWatcher, clean up
- Implements `Closeable`

### WorkspaceParser Changes

Expose per-round parsing methods as package-visible (currently private):
- `parseRoundFromMarkdown(Path responsesDir, int roundNum, Set<String> existingIds)` → `ParsedRound`
- `parseRoundFromJsonl(Path responsesDir, int roundNum)` → `ParsedRound`
- `discoverMaxRound(Path responsesDir)` → `int`
- `parseTracker(Path workspaceDir)` → `List<ParsedTrackerEntry>` (already static)

No new public API. These are package-internal for WorkspaceWatcher.

### WorkspaceReplayAdapter Refactoring

The current `replay()` method (lines 53–285) is monolithic — it loops over all rounds
and dispatches 6 entry types inline. The watcher needs to dispatch entries for
individual rounds as they arrive. Refactor by extracting per-entry-type dispatch
methods as package-visible instance methods:

- `dispatchIssues(UUID channelId, String sender, ParsedRound round,
  Map<String, Long> raiseMessageIds)` → updates `raiseMessageIds` in place
- `dispatchResponses(UUID channelId, String sender, ParsedRound round,
  Map<String, Long> raiseMessageIds)`
- `dispatchConfirmations(UUID channelId, String sender, ParsedRound round,
  Map<String, Long> raiseMessageIds)`
- `dispatchMemos(UUID channelId, String sender, int roundNum,
  List<String> assumptions, List<ParsedSettledDecision> settled)`
- `dispatchDeferred(UUID channelId, String sender,
  List<ParsedTrackerEntry> trackerStatuses, Map<String, Long> raiseMessageIds,
  WorkspaceParseResult parseResult)`
- `dispatchRoundSnapshot(UUID channelId, String sender, int round,
  String commitHash, String documentPath, String label, Instant timestamp, String body)`
  — already a private method, promote to package-visible

`replay()` becomes a thin loop calling these methods. The watcher holds a
`WorkspaceReplayAdapter` instance and calls the same methods for individual rounds.

**`ReplayResult` extension:**

```java
public record ReplayResult(int entryCount,
                            Map<String, String> statusDistribution,
                            DocumentTimeline timeline,
                            Map<Integer, String> snapshotContent,
                            Map<String, Long> raiseMessageIds,
                            long lastMessageId) {}
```

`raiseMessageIds` maps issue ID → Qhorus message ID (needed for `inReplyTo` on
response entries). `lastMessageId` is the highest message ID after replay (needed
for incremental WebSocket push).

### Active Watcher Registry

`ConcurrentHashMap<String, WorkspaceWatcher>` field on `DebateMcpTools`, keyed by
debate session ID.

- **Idempotency:** repeated `load_workspace` calls for the same workspace detect the
  existing watcher and return `"status":"already_watching"`
- **Cleanup on completion:** watcher invokes `onComplete` callback when terminal state
  detected, which removes itself from the `activeWatchers` map
- **Cleanup on end_debate:** Before session removal in `endDebate()`, check
  `activeWatchers.remove(channelId)` and call `watcher.stop()`. If `stop()` throws,
  log the error and continue with remaining cleanup (deregister participants,
  optionally delete channel). The watcher must stop before the session is removed from
  the registry, since mid-dispatch callbacks reference the session.
- **Server shutdown:** Add `@PreDestroy` method on `DebateMcpTools`:
  ```java
  @PreDestroy
  void shutdown() {
      activeWatchers.values().forEach(w -> {
          try { w.stop(); } catch (Exception e) { LOG.warning("shutdown: " + e.getMessage()); }
      });
      activeWatchers.clear();
  }
  ```
  `DebateMcpTools` is `@ApplicationScoped`, so `@PreDestroy` fires during CDI shutdown.
  `DirectoryWatcher.close()` is safe to call during shutdown — it closes the underlying
  watch service and cancels the watcher thread.

## File Watching — Event Flow

### Watched paths

DirectoryWatcher monitors the workspace directory recursively. Events are filtered to:

| Path pattern | Event | Action |
|-------------|-------|--------|
| `responses/reviewer-N.md` or `.jsonl` | CREATE | Parse round N, dispatch RAISE entries |
| `responses/implementor-N.md` or `.jsonl` | CREATE | Re-parse round N for responses, dispatch QUALIFY/COUNTER/FLAG_HUMAN entries |
| `progress.log` | MODIFY | Tail new lines, push metadata events |
| `tracker.md` | MODIFY | Re-parse tracker, diff against previous state, dispatch DEFERRED/evidence entries for changes |
| Everything else | * | Ignored |

### New response file flow

```
reviewer-3.md appears
  → parseRoundFromMarkdown(responsesDir, 3, existingIssueIds)
  → Dispatch RAISE entries for new issues
  → Dispatch CONFIRMATION entries (verdicts on previous round's responses)
  → Dispatch MEMO entries (assumptions, settled decisions)
  → Update existingIssueIds with new issue IDs
  → Push debate entries via eventBus.pushDebateEntries()

implementor-3.md appears  
  → Re-parse round 3 (responses now available)
  → Dispatch QUALIFY/COUNTER/FLAG_HUMAN entries (response entries only)
  → Re-parse tracker.md — diff against previous tracker state:
    • Newly DEFERRED issues → dispatchDeferred()
    • New evidence commits → dispatch evidence MEMO entries
  → Push debate entries via eventBus.pushDebateEntries()
  → Emit ROUND_SNAPSHOT if spec commit found in tracker
  → Update lastReplayedRound = 3
```

Reviewer files always appear before implementor files (reviewer runs first, then
implementor responds). The watcher processes them in arrival order.

**Partial round at replay boundary:** When replay runs, it processes all existing
files. If `reviewer-N.md` exists but `implementor-N.md` does not, the replay adapter
dispatches RAISE/CONFIRMATION/MEMO entries for round N. The watcher then starts and
only needs to handle `implementor-N.md` when it appears. The watcher responds to
file CREATE events — it never re-processes files that existed before watching started.

**File completeness check:** The design-review PM writes JSONL files atomically
(temp file + `os.rename()`), so CREATE events for `.jsonl` files always see complete
content. Markdown response files are written by the `claude -p` agent in a single
Write operation. As a safety measure, the watcher verifies that a markdown file
contains a `SIGNAL:` line before parsing. If absent, the watcher retries after a
500ms delay (max 3 retries) before treating the file as unparseable.

**Catch-up reconciliation and dedup:** The watcher maintains a
`Set<String> processedFiles = ConcurrentHashMap.newKeySet()` to prevent duplicate
processing. The set is keyed by filename stem (e.g. `"reviewer-3"`, `"implementor-3"`)
— not by round number, since each round has two independent files that must both be
processed. Both the async event handler and the catch-up scan guard processing with
`processedFiles.add(filenameStem)` — if it returns `false`, that specific file was
already handled by the other thread. After the watcher starts, it immediately calls
`WorkspaceParser.discoverMaxRound()` and compares against `lastReplayedRound`. Any
gap files are processed (subject to the dedup guard). This closes the TOCTOU window
without introducing duplicate entries or dropping implementor responses.

### Progress.log tailing

On each MODIFY event for progress.log:
- Read from `lastProgressOffset` to EOF
- Parse new lines against known patterns
- Push metadata events via `eventBus.pushMetadata(channelId, "workspace-progress", payload)`
- Update `lastProgressOffset`

**Parsed line patterns:**

| Pattern | Metadata type |
|---------|--------------|
| `[HH:MM:SS]   Reviewer/Implementor (fresh session)...` | `AGENT_START` |
| `[HH:MM:SS]     [Ns] reviewer/implementor: <text>` | `AGENT_STATUS` |
| `[HH:MM:SS]   Reviewer/Implementor done ($X.XX)` | `AGENT_COMPLETE` |
| `[HH:MM:SS]   N new issue(s) raised` | `ISSUES_RAISED` |
| `[HH:MM:SS]   Round N complete — ~$X.XX/round, $X.XX cumulative` | `ROUND_COMPLETE` |
| `REVIEW DONE` / `REVIEW PAUSED` / `REVIEW FAILED` / `REVIEW ABORTED` / `REVIEW CRASHED: {error}` / `REVIEW INTERRUPTED` | `REVIEW_TERMINAL` |

### Metadata event payload

```json
{"type": "AGENT_STATUS", "round": 2, "agent": "reviewer",
 "message": "Reading ARC42STORIES and exploring architecture docs",
 "elapsed": 60}

{"type": "AGENT_COMPLETE", "round": 2, "agent": "reviewer",
 "cost": 1.93}

{"type": "ROUND_COMPLETE", "round": 1, "cost": 4.99,
 "cumulativeCost": 4.99, "issuesRaised": 13}

{"type": "REVIEW_TERMINAL", "finalState": "DONE"}
```

### Incremental WebSocket push

After dispatching entries for a new round, the watcher pushes only the new entries:

```java
var newMessages = messageService.pollAfter(channelId, lastMessageId, Integer.MAX_VALUE);
var newEntries = newMessages.stream().map(DebateStreamEntry::from).filter(Objects::nonNull).toList();
eventBus.pushDebateEntries(channelId, newEntries);
lastMessageId = newMessages.isEmpty() ? lastMessageId
    : newMessages.get(newMessages.size() - 1).id();
```

`lastMessageId` is initialised from `ReplayResult.lastMessageId()` at watcher start.

### Terminal state handling

When any terminal state is detected in progress.log:
- `REVIEW DONE`, `REVIEW PAUSED`, `REVIEW FAILED (exit N)`,
  `REVIEW ABORTED`, `REVIEW CRASHED: {error}`, `REVIEW INTERRUPTED`

Actions:
1. Push `REVIEW_TERMINAL` metadata event with `finalState` field
2. Call `stop()` — close DirectoryWatcher
3. Invoke `onComplete.run()` — removes watcher from `activeWatchers` map via the
   callback provided at construction time

## load_workspace Integration

**Modified flow in `DebateMcpTools.loadWorkspace()`:**

After the existing replay logic completes:

```java
// Detect review state
boolean reviewComplete = isReviewComplete(wsPath);

if (!reviewComplete) {
    int lastRound = parseResult.rounds().size();
    Set<String> existingIds = collectIssueIds(parseResult);

    var adapter = new WorkspaceReplayAdapter(
        messageService, instanceService, channelGateway, eventBus);
    var watcher = new WorkspaceWatcher(adapter, eventBus, session,
        channel.tenancyId(), () -> activeWatchers.remove(session.debateSessionId()));
    watcher.start(wsPath, lastRound, existingIds,
        result.raiseMessageIds(), result.lastMessageId(),
        parseResult.projectRepoPath(), parseResult.specPath());
    activeWatchers.put(session.debateSessionId(), watcher);
}
```

`isReviewComplete()` reads the last few lines of progress.log and checks for any of
the six terminal states: `REVIEW DONE`, `REVIEW PAUSED`, `REVIEW FAILED`,
`REVIEW ABORTED`, `REVIEW CRASHED`, `REVIEW INTERRUPTED`.

**Return value changes:**
- Complete review: `"status":"loaded"` (unchanged)
- Watching review: `"status":"watching"`
- Already watching: `"status":"already_watching"`

## Thread Model

- `DirectoryWatcher.watchAsync()` runs on its own thread (CompletableFuture)
- Event callbacks fire on the watcher thread — a plain Java thread with no CDI context
- `WebSocketEventBus` uses ConcurrentHashMap, `sendText()` via Mutiny subscribe — safe from any thread
- No Vert.x event loop interaction — all dispatch is from a background thread

**CDI context on watcher thread:** `MessageService.dispatch()` is `@Transactional`
and accesses request-scoped beans (e.g. `CurrentPrincipal` for tenancy resolution).
The watcher thread has no CDI request context. Solution:

1. Capture `tenancyId` from the channel at watcher construction time (`channel.tenancyId()`
   — the channel already carries its tenancy from creation). No `CurrentPrincipal` injection
   needed in `DebateMcpTools`.
2. Activate CDI request context around each callback:
   ```java
   var rc = Arc.container().requestContext();
   rc.activate();
   try {
       // set tenancyId explicitly on all MessageDispatch builders
       // parse round, dispatch entries, push WebSocket
   } finally {
       rc.deactivate();
   }
   ```
3. All `MessageDispatch` builders include `.tenancyId(capturedTenancyId)` explicitly,
   avoiding the `CurrentPrincipal` fallback path in `MessageService.dispatch()`

## UI — Workspace Status Element

New Lit element: `<workspace-status>` in `server/runtime/src/main/webui/src/panels/workspace-status.ts`

**Behaviour:**
- Subscribes to `workspace-progress` pages events
- Only visible when a workspace is being watched (hidden otherwise)
- Renders in the topbar area

**Display states:**

| State | Render |
|-------|--------|
| Agent working | "Round 2 — reviewer (1m 30s)" with elapsed timer |
| Agent status update | Updates message text: "reviewer: Reading issues #88, #85..." |
| Agent complete | "Round 2 — reviewer done ($1.93), implementor working..." |
| Round complete | "Round 2 complete — $4.99" (brief flash, then next round or idle) |
| Review complete | "Review complete — 3 rounds, $12.50" |
| Review paused/failed | "Review paused" / "Review failed" with appropriate styling |

**Elapsed timer:** Client-side interval timer started on `AGENT_START`, stopped on
`AGENT_COMPLETE`. No server polling needed — the client counts seconds locally
between status events.

**Element lifecycle:** `configure({debateSessionId})` from workbench layout. Hidden by
default until the first `workspace-progress` event arrives. Cleaned up in
`disconnectedCallback()`.

## New Dependency

```xml
<dependency>
    <groupId>io.methvin</groupId>
    <artifactId>directory-watcher</artifactId>
    <version>0.18.0</version>
</dependency>
```

Added to `server/runtime/pom.xml`. New dependency — not previously used in the
platform. Chosen for native macOS FSEvents support via JNA (no polling), cross-platform
compatibility, and minimal transitive dependencies.

## Testing Strategy

**Unit tests:**
- `WorkspaceWatcherTest` — mock MessageService/EventBus, create temp workspace dir,
  write files programmatically, assert correct debate entries dispatched
- Progress.log line parsing — extract metadata from known line formats
- Terminal state detection — verify watcher stops on REVIEW DONE/PAUSED/FAILED
- Idempotency — repeated load_workspace with active watcher returns already_watching

**Integration/E2E:**
- Load a workspace that has partial rounds, verify replay + watching mode activation
- Write a new response file to the watched directory, verify debate entries appear
  in the channel feed within seconds
- Write progress.log lines, verify metadata events reach the browser
- Write REVIEW DONE, verify watcher stops and completion event is pushed

## Out of Scope

- **Design-review script changes** — no POST-to-DraftHouse from the script side
- **blocks-ui extraction** — all components are workspace-specific
- **Checkpoint/decision files** — `checkpoint-N.md` and `decisions/` directory watching
  (tracked as GitHub issue on drafthouse — to be filed at implementation time)
- **Multiple simultaneous watchers** — the registry supports it, but no UI for
  switching between watched workspaces (tracked as GitHub issue on drafthouse — to be
  filed at implementation time)
