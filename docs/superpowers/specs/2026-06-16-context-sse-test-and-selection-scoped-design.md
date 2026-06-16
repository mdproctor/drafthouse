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
- Start debate, raise a point (creates message activity), connect SSE
- Drain initial context + catch-up events
- Call `tools.reportContext(sessionId, 42.0)` to push a snapshot
- Wait for the next `context-usage` event in the stream
- Assert: `agentReportedPercent` is `42.0`, `effectivePercent` reflects the reported value

Both tests scan for `"type":"context-usage"` instead of `"entryType":"RAISE"`. The
`reportContext` call absorbs ledger frontier exceptions via the same `DebateE2EFixtures`
pattern — `reportContext` does not dispatch a Qhorus message, so no frontier exception
is expected, but the test is defensive.

---

## §2 Selection-Scoped Conversations (#54)

### Problem

The diff panel emits a `selection-changed` CustomEvent with `{ side, startLine, endLine }`
when the user selects text. No consumer exists. The goal is to wire this event to the
debate channel so LLM agents can see what the user is looking at and ground their
conversation in the selected passage.

### Design

#### §2a Domain model: `SelectionScope` record

```java
// server/api — io.casehub.drafthouse
public record SelectionScope(DocumentSide side, int startLine, int endLine, String selectedText) {}
```

`DebateSession` gets:
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

#### §2d Browser: shell wiring in `index.html`

The workspace shell listens for `selection-changed` on the diff panel and POSTs to the
server. Only active when a debate session is connected:

```javascript
diffPanel.addEventListener('selection-changed', async (e) => {
  if (!currentDebateSessionId) return;
  const { side, startLine, endLine } = e.detail;
  const sel = diffPanel.shadowRoot.getSelection
    ? diffPanel.shadowRoot.getSelection()
    : document.getSelection();
  const text = sel?.toString() || '';
  if (!text) return;
  await fetch(`/api/debate/${currentDebateSessionId}/selection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ side, startLine, endLine, selectedText: text })
  });
});
```

The selected text is extracted from the browser's selection API at event time — no
round-trip to re-derive from line numbers.

#### §2e Summary integration: selection in `get_debate_summary`

After rendering the projection summary, if the session has a `currentSelection`, append:

```
## Active Selection
**Document A**, lines 5–12:
> The selected passage...
```

This gives LLM agents grounding context — same pattern as
`ReviewerChannelBackend.buildSelectionContext()` but for the debate path.

---

## §3 Testing Strategy

### §3.1 #56 Tests (integration)

Both tests in `DebateEventResourceTest.java`:
- `initialContextSnapshot_emittedOnConnect`
- `pushedContextSnapshot_deliveredViaSse`

### §3.2 #54 Tests

**Unit tests (`DebateMcpToolsTest`):**
- `getDebateSummary_includesSelectionContext` — set `SelectionScope` on session, call
  `getDebateSummary()`, assert output contains selection section
- `getDebateSummary_noSelection_noSelectionSection` — no selection, assert no
  "Active Selection" in output

**Integration tests (`DebateEventResourceTest`):**
- `selectionPost_storesAndClearsSelection` — POST selection, verify 200 and stored;
  DELETE, verify cleared
- `selectionPost_invalidSession_returns404`
- `selectionScope_deliveredViaSse` — POST selection while SSE connected, verify
  `selection-scope` event appears in stream

**E2E tests (new or in `CrossPanelE2ETest`):**
- `selectionInDiff_postsToDebateSession` — start debate, load page with `?debate=`,
  programmatically select text in diff panel via Playwright, verify selection POST reaches
  server (session has `currentSelection`)

---

## §4 Files Changed

| File | Change |
|------|--------|
| `server/api/.../SelectionScope.java` | New record |
| `server/api/.../DebateSession.java` | Add `currentSelection` field + accessors |
| `server/runtime/.../DebateEventResource.java` | POST/DELETE selection endpoints, pending selection map, SSE emission |
| `server/runtime/.../DebateMcpTools.java` | Append selection context to `getDebateSummary()` |
| `index.html` | `selection-changed` listener, POST to server |
| `server/runtime/test/.../DebateEventResourceTest.java` | §1 + §2 integration tests |
| `server/runtime/test/.../DebateMcpToolsTest.java` | §2 unit tests |
| `server/runtime/test/.../e2e/CrossPanelE2ETest.java` | §2 E2E test |

---

## §5 Out of Scope

- Debate panel UI showing the current selection (future — requires panel changes)
- Selection persistence across session restarts (volatile state is sufficient)
- Multi-user selection conflict resolution (single-user tool)
