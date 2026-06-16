# Context-Usage SSE Integration Test & Selection-Scoped Conversations

**Date:** 2026-06-16
**Issues:** #56, #54
**Branch:** `issue-56-context-usage-sse-test-and-selection-scoped`

---

## §1 Context-Usage SSE Integration Test (#56)

### Problem

The `DebateEventResource.events()` SSE stream composes three `Multi<String>` streams:
`initialContext` (snapshot on connect), `catchUp` (historical messages), `live` (tick-polled
messages + pending context snapshots). `DebateMcpToolsTest` verifies that
`pushContextSnapshot()` is called (mock verification), but no test validates the actual
SSE stream output — the delivery path from `ConcurrentHashMap` through the live tick to
the SSE client is untested.

### Design

Two new tests in `DebateEventResourceTest.java`, using the existing virtual-thread SSE
reader + latch pattern from `catchUp_deliversHistoricalEvents`:

**Test 1: `initialContextSnapshot_emittedOnConnect`**
- Start debate via injected `DebateMcpTools`
- Connect SSE via virtual thread + `HttpURLConnection`
- First non-heartbeat event must be a JSON object with `"type":"context-usage"`
- Assert: `windowSizeChars` present (proves initial snapshot, not pushed), plus
  `serverContributionChars`, `messageCount`, `effectivePercent`, `thresholdExceeded`

**Test 2: `pushedContextSnapshot_deliveredViaSse`**
- Start debate via injected `DebateMcpTools`
- Raise a point using `DebateE2EFixtures.dispatchRaise()` (needs the frontier exception
  wrapper — `raisePoint` dispatches a Qhorus message which triggers the ledger frontier
  query)
- Connect SSE, drain initial context + catch-up events
- Call `tools.reportContext(sessionId, 42.0)` directly (no wrapper needed —
  `reportContext` writes to `ContextTracker` and `pendingContextSnapshots` only; no Qhorus
  dispatch, no frontier query, no possible frontier exception)
- Wait for the next `context-usage` event in the stream
- Assert: `agentReportedPercent` is `42.0`, `effectivePercent` reflects the reported value

Both tests scan for `"type":"context-usage"` instead of `"entryType":"RAISE"`.

---

## §2 Selection-Scoped Conversations (#54)

### Problem

The diff panel emits a `selection-changed` CustomEvent with `{ side, startLine, endLine }`
when the user selects text. No consumer exists. The goal is to wire this event to the
debate channel so LLM agents can see what the user is looking at and ground their
conversation in the selected passage.

### Design

#### §2a Domain model: unified `SelectionScope` record

```java
// server/api — io.casehub.drafthouse
public record SelectionScope(DocumentSide side, int startLine, int endLine, String selectedText) {
    public SelectionScope {
        if (selectedText == null || selectedText.isBlank()) {
            throw new IllegalArgumentException("selectedText must be non-null and non-blank");
        }
    }
}
```

**Unification with the review path:** The review path currently stores selection as two
separate fields on `ReviewSession` — `selectionSide` (DocumentSide) and `selectionText`
(String) — with a compact constructor invariant requiring both null or both non-null.
`ReviewerChannelBackend.buildSelectionContext()` reads them.

Two selection representations for the same concept is unnecessary complexity. `SelectionScope`
becomes the single selection type for both paths:

- `ReviewSession` record fields collapse from `selectionSide` + `selectionText` to a single
  `SelectionScope selection` field (nullable — null means no selection)
- `ReviewSession.withSelection(DocumentSide, String)` becomes
  `withSelection(SelectionScope selection)` (null to clear)
- `ReviewSessionRegistry.updateSelection(UUID, DocumentSide, String)` becomes
  `updateSelection(UUID, SelectionScope)` (null to clear)
- `ReviewerChannelBackend.buildSelectionContext()` reads `session.selection().selectedText()`
- The `update_selection` MCP tool passes `startLine=0, endLine=0` when the caller does not
  supply line numbers (review path — text-only selection)
- The `update_selection` MCP tool gains optional `startLine` and `endLine` parameters for
  future use

Migration: ~5 files, zero test logic change. The compact constructor invariant in
`ReviewSession` (both-null-or-both-non-null) is subsumed by `SelectionScope` being nullable
at the field level with non-null fields internally.

**`DebateSession`** gets:
```java
private volatile SelectionScope currentSelection;
public void updateSelection(SelectionScope selection) { this.currentSelection = selection; }
public SelectionScope currentSelection() { return currentSelection; }
```

Volatile is sufficient — single writer (REST endpoint), multiple readers (SSE tick,
summary render). The record is replaced as a unit, so no field-level atomicity is needed.

#### §2b REST endpoint on `DebateEventResource`

```
POST /api/debate/{debateSessionId}/selection
Content-Type: application/json
{ "side": "A", "startLine": 5, "endLine": 12, "selectedText": "The selected passage..." }
→ 200 {"status":"ok"}
→ 404 if session not found

DELETE /api/debate/{debateSessionId}/selection
→ 200 {"status":"ok"}
→ 404 if session not found
```

Lives on `DebateEventResource` — a UI-facing REST concern alongside SSE events and
session listings. Not an MCP tool.

No GET endpoint — agents read selection via `get_debate_summary`, not a dedicated REST call.

#### §2c SSE delivery: selection events in the live stream

On selection change, the SSE stream emits a `selection-scope` metadata event (non-array
JSON object, same pattern as `context-usage`):

```json
{"type":"selection-scope","side":"A","startLine":5,"endLine":12,"selectedText":"..."}
```

Implementation: same `ConcurrentHashMap<UUID, String>` + pending-snapshot pattern as
`pushContextSnapshot()`. The POST endpoint stores pending selection JSON; the live tick
drains and emits it. A cleared selection (DELETE) emits:

```json
{"type":"selection-scope","cleared":true}
```

**Live tick refactoring:** The current live tick has 4 conditional branches for context +
entries. Adding selection would double the combinatorics. Instead, refactor the tick to a
collect-then-emit pattern:

```java
// Drain all pending metadata
List<String> items = new ArrayList<>();
String pendingCtx = pendingContextSnapshots.remove(channelId);
if (pendingCtx != null) items.add(pendingCtx);
String pendingSel = pendingSelections.remove(channelId);
if (pendingSel != null) items.add(pendingSel);
// Add message entries (or heartbeat if nothing else to emit)
String entries = serializeMessages(messages, lastSentId);
if (entries != null) items.add(entries);
if (items.isEmpty()) items.add("{\"type\":\"heartbeat\"}");
return Multi.createFrom().iterable(items);
```

This eliminates O(2^n) conditional branches — metadata types can be added without
touching the emission logic.

#### §2d Browser: diff panel event enrichment + shell wiring

**Diff panel change (`panels/drafthouse-diff.js`):** The `mouseup` handler already has the
`Selection` object in scope (lines 318-321). Extract the text there and include it in the
event detail — the component owns its DOM state:

```javascript
// Inside drafthouse-diff.js mouseup handler (replaces current lines 334-337):
this.dispatchEvent(new CustomEvent('selection-changed', {
  bubbles: true,
  detail: { side, startLine, endLine, selectedText: sel.toString() },
}));
```

**Shell wiring (`index.html`):** The shell listens for the enriched event and POSTs to the
server. Uses `debateEventBus.sessionId` (the existing accessor, line 11 of
`debate-event-bus.js`) — no shell-scoped session ID variable exists:

```javascript
diffPanel.addEventListener('selection-changed', async (e) => {
  if (!debateEventBus.sessionId) return;
  const { side, startLine, endLine, selectedText } = e.detail;
  if (!selectedText) return;
  await fetch(`/api/debate/${debateEventBus.sessionId}/selection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ side, startLine, endLine, selectedText })
  });
});
```

This is cleaner than the shell reaching into Shadow DOM — the component owns its state
extraction, and the text is captured at the moment of selection rather than asynchronously.

#### §2e Summary integration: selection in `DebateMcpTools.getDebateSummary()`

The selection context append happens in `DebateMcpTools.getDebateSummary()` after the
`debateProjection.render()` call — not in `SummaryRenderer`. Rationale:

- `SummaryRenderer` is a pure-Java class in `server/api/` rendering from `ReviewState`,
  which correctly does not contain volatile UI state like the current selection
- `getDebateSummary()` already has the `DebateSession` resolved — it reads
  `session.currentSelection()` directly
- `getDebateSummaryAtRound()` correctly does NOT include live selection (historical state)

If the session has a `currentSelection`, append after the rendered summary:

```
## Active Selection
**Document A**, lines 5–12:
> The selected passage...
```

---

## §3 Testing Strategy

### §3.1 #56 Tests (integration)

Both tests in `DebateEventResourceTest.java`:
- `initialContextSnapshot_emittedOnConnect`
- `pushedContextSnapshot_deliveredViaSse`

### §3.2 #54 Tests

**Unit tests (`DebateMcpToolsTest`):**
- `getDebateSummary_includesSelectionContext` — set `SelectionScope` on session, call
  `getDebateSummary()`, assert output contains selection section with line numbers
- `getDebateSummary_noSelection_noSelectionSection` — no selection set, assert no
  "Active Selection" in output

**Integration tests (`DebateEventResourceTest`):**
- `selectionPost_storesAndClearsSelection` — POST selection, verify 200 and stored;
  DELETE, verify cleared
- `selectionPost_invalidSession_returns404`
- `selectionScope_deliveredViaSse` — POST selection while SSE connected, verify
  `selection-scope` event appears in stream

**E2E tests (`CrossPanelE2ETest`):**
- `selectionInDiff_postsToDebateSession` — start debate, load page with `?debate=`,
  programmatically select text in diff panel via Playwright, verify selection POST reaches
  server (inject `DebateSessionRegistry` to read `session.currentSelection()`)

---

## §4 Files Changed

| File | Change |
|------|--------|
| `server/api/.../SelectionScope.java` | New record — unified selection type for both paths |
| `server/api/.../DebateSession.java` | Add `currentSelection` field + accessors |
| `server/api/.../ReviewSession.java` | Replace `selectionSide` + `selectionText` with `SelectionScope selection` |
| `server/runtime/.../ReviewSessionRegistryImpl.java` | `updateSelection` takes `SelectionScope` |
| `server/api/.../ReviewSessionRegistry.java` | `updateSelection` signature change |
| `server/runtime/.../ReviewerChannelBackend.java` | Read from `session.selection()` |
| `server/runtime/.../DraftHouseMcpTools.java` | `update_selection` builds `SelectionScope`; optional line params |
| `server/runtime/.../DebateEventResource.java` | POST/DELETE selection endpoints, pending selection map, live tick refactoring (collect-then-emit) |
| `server/runtime/.../DebateMcpTools.java` | Append selection context to `getDebateSummary()` |
| `panels/drafthouse-diff.js` | Add `selectedText` to `selection-changed` event detail |
| `index.html` | `selection-changed` listener, POST to server via `debateEventBus.sessionId` |
| `server/runtime/test/.../DebateEventResourceTest.java` | §1 + §2 integration tests |
| `server/runtime/test/.../DebateMcpToolsTest.java` | §2 unit tests + review path selection tests updated |
| `server/runtime/test/.../e2e/CrossPanelE2ETest.java` | §2 E2E test |

---

## §5 Out of Scope

- Debate panel UI showing the current selection (future — requires panel changes)
- Selection persistence across session restarts (volatile state is sufficient)
- Multi-user selection conflict resolution (single-user tool)
