# Design: WebSocket Reconnection Tests + Document Badge Dropdown

**Issues:** casehubio/drafthouse#88, casehubio/drafthouse#85
**Date:** 2026-07-04
**Status:** Approved

## Scope

Two S-scale issues on one branch:

1. **#88** — WebSocket reconnection cycle and concurrent push tests (S/Med)
2. **#85** — Document badge dropdown for A/B slot assignment (S/Low)

## Issue #88: WebSocket Reconnection Tests

### Problem

The WebSocket push implementation (#87) has unit-level coverage in `WebSocketEventBusTest` and basic integration coverage in `DebateWebSocketTest`, but the reconnection-critical paths specified in the design spec's test strategy are not yet exercised.

### Approach

Extend `DebateWebSocketTest` with 3 new integration tests. No new test classes needed — the existing `TestClient` inner class (Jakarta WS `@ClientEndpoint` with `resetLatch()`) and `connectWebSocket()` helper support multi-phase reconnection tests.

One new injection: `@Inject WebSocketEventBus eventBus` for direct push access in the concurrent test.

### Key Insight

From the server's perspective, "reconnection" is indistinguishable from "close + new connection." Pages handles reconnection by opening a fresh WebSocket and re-sending all subscriptions. The server pushes `reconnected` on every `@OnOpen` and sends full catch-up on every subscribe. The tests validate this behavioral contract — no TCP-level manipulation needed.

### Test Specifications

#### Test 1: `reconnect_receives_full_catch_up_matching_pre_disconnect_state`

| Phase | Action | Assertion |
|-------|--------|-----------|
| 1 | Create debate, raise point-1 | — |
| 2 | Connect client-1, subscribe | Catch-up contains point-1 |
| 3 | Raise point-2 (live push) | Client-1 receives point-2 |
| 4 | Close client-1 | — |
| 5 | Open client-2 (fresh connection) | First message is `reconnected` |
| 6 | Re-subscribe on client-2 | Catch-up contains **both** points |

Validates: full catch-up on reconnect returns complete state, not just what happened since the previous connection. The `reconnected` event arrives before any catch-up data. Phase 5 assertion is positional: `client2.received.get(0)` must parse to topic `reconnected`, not `anyMatch`.

#### Test 2: `stale_subscription_after_session_end_silently_ignored_on_reconnect`

| Phase | Action | Assertion |
|-------|--------|-----------|
| 1 | Create debate, connect, subscribe | Catch-up received |
| 2 | End debate via `tools.endDebate()` | — |
| 3 | Close connection | — |
| 4 | Open new connection (reconnect) | `reconnected` + `sessions` received |
| 5 | Re-subscribe to ended session ID | No catch-up (latch timeout), no error, connection stays open |

Validates: pages client reconnects and blindly re-sends all tracked subscriptions. A subscription for a session ended during the disconnection window is silently ignored — no error, no catch-up, connection not closed.

#### Test 3: `concurrent_push_from_multiple_producers`

| Phase | Action | Assertion |
|-------|--------|-----------|
| 1 | Create debate, connect, subscribe, consume catch-up | — |
| 2 | Fire `tools.raisePoint()` and `eventBus.pushMetadata()` from two `CompletableFuture.runAsync()` tasks, synchronized via `CyclicBarrier(2)` to force simultaneous execution | Both events arrive |
| 3 | — | Every received message is valid JSON (not garbled) |

Validates: Vert.x per-connection `sendText()` serialization holds under concurrent push from different application threads (Qhorus dispatch thread + direct metadata push thread).

### Dead Connection Self-Healing

Already covered by `WebSocketEventBusTest.sendText_failure_triggers_unregister()` — mocks a connection whose `sendText()` returns a failed `Uni`, verifies `unregister()` fires, confirms the connection is removed from all watcher sets. The integration dimension (does Vert.x produce a failed `Uni` for broken TCP?) is a framework guarantee. No additional integration test needed.

**Issue #88 reconciliation:** Add a comment to issue #88 explaining that test 4 (dead connection self-healing E2E) is covered by the existing unit test and why no integration test is needed. This closes the gap between the issue's 4-test list and the spec's 3 implemented tests.

### Files Modified

| File | Change |
|------|--------|
| `server/runtime/src/test/java/.../DebateWebSocketTest.java` | Add 3 test methods, inject `WebSocketEventBus` |

---

## Issue #85: Document Badge Dropdown

### Problem

The `#doc-badge` element exists in the topbar but has no click handler. Users cannot assign documents to A/B comparison slots from the browser — only via MCP tools (`set_comparison`). The dropdown was deferred during the Quinoa migration (#75).

### Approach

Create `<drafthouse-doc-picker>` as a standalone Shadow DOM custom element placed in the topbar HTML. Not registered as a pages panel — it's a topbar element that self-initializes via `pages-event` listeners.

### Component: `<drafthouse-doc-picker>`

**State:**

| Field | Source | Description |
|-------|--------|-------------|
| `documents` | `pages-event` topic `documents-changed` | Array of `{path, label}` |
| `currentComparison` | `pages-event` topic `comparison-changed` | `{pathA, pathB}` or null |
| `sessionId` | `session-id` attribute via `observedAttributes` + `attributeChangedCallback` (set by index.ts) | Current debate session ID |
| `pendingA` / `pendingB` | Local state | For first-time assignment when no comparison exists |
| `open` | Local state | Dropdown visibility |

**Event subscriptions:**

| Event | Topic | Action |
|-------|-------|--------|
| `pages-event` | `documents-changed` | Update document list. If pending document no longer in list, clear pending. If document list is empty, set `open = false`. Re-render |
| `pages-event` | `comparison-changed` | If both `pathA` and `pathB` are null, set `currentComparison = null`; otherwise set `currentComparison = {pathA, pathB}`. Clear pending. Re-render |
| `pages-event` | `reconnected` | Clear all state, close dropdown |
| `click` | (document) | Close dropdown on outside click |
| `keydown` | `Escape` | Close dropdown |

**DOM structure (Shadow DOM):**

```
<style> (adoptedStyleSheets) </style>
<span class="badge" @click="toggle">📄 <span class="count">N</span></span>
<div class="dropdown" hidden>
  <div class="header">Documents</div>
  <div class="doc-list">
    <!-- per document: -->
    <div class="doc-row">
      <span class="doc-label" title="/full/path">filename.md</span>
      <button class="slot-btn [active]" data-slot="a">A</button>
      <button class="slot-btn [active]" data-slot="b">B</button>
    </div>
  </div>
</div>
```

**Assignment behavior:**

| State | User Action | Result |
|-------|-------------|--------|
| Comparison set `{A: doc1, B: doc2}` | Click A on doc3 | Immediate POST `{pathA: doc3, pathB: doc2}` |
| Comparison set `{A: doc1, B: doc2}` | Click B on doc3 | Immediate POST `{pathA: doc1, pathB: doc3}` |
| Comparison set `{A: doc1, B: doc2}` | Click A on doc1 (identity) | No-op — already assigned |
| Comparison set `{A: doc1, B: doc2}` | Click B on doc2 (identity) | No-op — already assigned |
| No comparison | Click A on doc1 | Mark `pendingA = doc1` (visual indicator, no POST) |
| `pendingA` set | Click B on doc2 | POST `{pathA: pendingA, pathB: doc2}` (pending cleared by `comparison-changed` event) |
| `pendingA` set | Click A on different doc | Replace `pendingA` with new doc (visual indicator moves) |
| `pendingA` set | Click A on same doc | Clear `pendingA` (toggle off) |
| No comparison | Click B on doc1 | Mark `pendingB = doc1` (visual indicator, no POST) |
| `pendingB` set | Click A on doc2 | POST `{pathA: doc2, pathB: pendingB}` (pending cleared by `comparison-changed` event) |
| `pendingB` set | Click B on different doc | Replace `pendingB` with new doc (visual indicator moves) |
| `pendingB` set | Click B on same doc | Clear `pendingB` (toggle off) |
| Any state | POST returns non-2xx | Flash error indicator (auto-dismiss after 3s), preserve pending state |

POST target: `POST /api/debate/${sessionId}/comparison` with `Content-Type: application/json` body `{pathA, pathB}`. The `comparison-changed` event from the server confirms the change — doc-picker re-renders from the event, not from the POST response. On non-2xx response, flash a transient error indicator (auto-dismiss after 3s). Pending state is never cleared on dispatch — only on receipt of `comparison-changed`.

**Same document in both slots:** Allowed. No artificial restriction — `DocumentSet.setComparison()` doesn't check for equality and the behavior is harmless.

### Styling

Shadow DOM with `adoptedStyleSheets`. Uses CSS custom properties from the host context:

- Badge: inherits topbar text styling, hidden when 0 documents
- Dropdown: `position: absolute`, anchored below badge, `background: var(--bg)`, `border: 1px solid var(--border)`, `border-radius: 4px`, z-index above content panels
- Document rows: flex layout, label + A/B buttons
- A/B buttons: follow existing topbar button pattern (`var(--chrome)` default, `var(--accent)` when active)
- Pending state: pulsing or dashed-border indicator on the pending slot button

### Integration with index.ts

| Change | Detail |
|--------|--------|
| Add import | `import "./panels/drafthouse-doc-picker.js";` (side-effect) |
| Replace badge HTML | `<drafthouse-doc-picker></drafthouse-doc-picker>` replaces `<span id="doc-badge">...` |
| Remove handler | Delete `documents-changed` block in the `pages-event` listener — doc-picker owns this |
| Set session attribute | In `connectDebateSession()`: `document.querySelector('drafthouse-doc-picker')?.setAttribute('session-id', sessionId)` |

No circular imports. index.ts imports doc-picker as a side-effect. Doc-picker reads `session-id` from the attribute set by index.ts.

### Testing

E2E Playwright test in `DocPickerE2ETest.java`:

1. Start debate session via `DebateMcpTools`, add 3 documents, set initial comparison
2. Navigate browser to drafthouse with `?debate=<id>`
3. Verify badge shows document count
4. Click badge → verify dropdown appears with all documents listed
5. Verify current A/B assignments highlighted
6. Click A on a different document → wait for diff viewer content to update via Playwright locator (e.g., `page.locator("drafthouse-diff").locator(".line-content").first().waitFor()`) → verify comparison changes in diff viewer
7. Click outside → verify dropdown closes

Uses existing `@QuarkusTest` + `@WithPlaywright` pattern per `playwright-page-lifecycle` protocol.

### Files Modified

| File | Change |
|------|--------|
| `server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js` | New — Shadow DOM custom element |
| `server/runtime/src/main/webui/src/index.ts` | Replace badge, add import, set attribute, remove handler |
| `server/runtime/src/test/java/.../e2e/DocPickerE2ETest.java` | New — Playwright E2E test |

---

## Platform Coherence

- **Shadow DOM Web Component pattern**: Consistent with `drafthouse-diff`, `drafthouse-debate`, `drafthouse-review-tracker`, `drafthouse-context-gauge`
- **`pages-event` subscription**: Same event-driven architecture used by all panels
- **`adoptedStyleSheets`**: Same styling approach as all panels
- **CSS custom properties**: Reuses existing theme variables — no new design tokens
- **Jakarta WS `@ClientEndpoint`**: Same test client pattern as existing `DebateWebSocketTest`
- **`@QuarkusTest` + `@WithPlaywright`**: Per `playwright-page-lifecycle` protocol
- **No server-side changes**: Both issues use existing endpoints and event infrastructure
