# WebSocket Reconnection Tests + Document Badge Dropdown — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use hortora:subagent-driven-development (recommended) or hortora:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close #88 (WebSocket reconnection cycle tests) and #85 (document badge dropdown for A/B slot assignment).

**Architecture:** Extend `DebateWebSocketTest` with 3 integration tests covering reconnection catch-up, stale subscriptions, and concurrent push. Create `<drafthouse-doc-picker>` as a Shadow DOM custom element in the topbar for A/B document slot assignment, with an E2E Playwright test.

**Tech Stack:** Java 21, Quarkus 3.34.3, Jakarta WebSocket `@ClientEndpoint`, Playwright, TypeScript/JavaScript Web Components

## Global Constraints

- Test port: `%test.quarkus.http.port=9002`
- Build: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
- Single test: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=<ClassName>`
- E2E tests follow `@QuarkusTest` + `@WithPlaywright` + `@InjectPlaywright BrowserContext` pattern
- Panels use Shadow DOM with `adoptedStyleSheets` and `pages-event` listeners on `document`
- CSS custom properties: `--bg`, `--chrome`, `--border`, `--ink`, `--accent`, `--accent-light`, `--muted`, `--sepia`
- Issue refs in commits: `Refs #88` or `Refs #85`

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `server/runtime/src/test/java/io/casehub/drafthouse/DebateWebSocketTest.java` | Modify | Add 3 reconnection/concurrent tests, inject `WebSocketEventBus` |
| `server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js` | Create | Shadow DOM custom element for document A/B slot assignment |
| `server/runtime/src/main/webui/src/index.ts` | Modify | Replace badge span, add import, remove documents-changed handler, set session-id attribute |
| `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DocPickerE2ETest.java` | Create | Playwright E2E test for dropdown |

---

### Task 1: WebSocket Reconnection Tests (#88)

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateWebSocketTest.java`

**Interfaces:**
- Consumes: `DebateMcpTools.startDebate()`, `raisePoint()`, `endDebate()` — existing CDI beans
- Consumes: `WebSocketEventBus.pushMetadata(UUID channelId, String topic, Object payload)` — new injection
- Produces: 3 passing test methods

- [ ] **Step 1: Write the three failing tests**

Add `@Inject WebSocketEventBus eventBus;` field to `DebateWebSocketTest`.

Add three test methods. All use the existing `TestClient` inner class and `connectWebSocket()` helper.

**Test 1 — `reconnect_receives_full_catch_up_matching_pre_disconnect_state`:**

```java
@Test
void reconnect_receives_full_catch_up_matching_pre_disconnect_state() throws Exception {
    // Phase 1: Create debate, raise a point
    String startResult = tools.startDebate("test-spec.md", null);
    activeDebateSessionId = extractDebateId(startResult);
    tools.raisePoint(activeDebateSessionId, "REV", 1,
            "First point", "HIGH", null, null);

    // Phase 2: Connect client-1, subscribe, verify catch-up
    TestClient client1 = new TestClient(5);
    Session session1 = connectWebSocket(client1);
    client1.awaitMessages(5); // reconnected + sessions
    client1.resetLatch(3);    // debate-entries, context-usage, documents-changed
    session1.getBasicRemote().sendText(
            "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
    assertThat(client1.awaitMessages(5)).isTrue();

    // Phase 3: Raise a second point (live push)
    client1.resetLatch(1);
    tools.raisePoint(activeDebateSessionId, "REV", 1,
            "Second point", "MEDIUM", null, null);
    assertThat(client1.awaitMessages(5)).isTrue();

    // Phase 4: Close client-1 (simulates disconnect)
    session1.close();

    // Phase 5: Open client-2 (fresh connection = reconnection)
    TestClient client2 = new TestClient(2);
    wsSession = connectWebSocket(client2);
    assertThat(client2.awaitMessages(5)).isTrue();

    // Positional assertion: first message MUST be reconnected
    JsonNode firstMsg = mapper.readTree(client2.received.get(0));
    assertThat(firstMsg.get("topic").asText()).isEqualTo("reconnected");

    // Phase 6: Re-subscribe — catch-up must contain BOTH points
    client2.resetLatch(3); // debate-entries, context-usage, documents-changed
    wsSession.getBasicRemote().sendText(
            "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
    assertThat(client2.awaitMessages(5)).isTrue();

    List<JsonNode> catchUp = client2.parsedMessages(mapper);
    JsonNode entries = catchUp.stream()
            .filter(m -> "debate-entries".equals(m.get("topic").asText()))
            .findFirst().orElseThrow(() -> new AssertionError("No debate-entries in catch-up"));
    assertThat(entries.get("payload").size()).isGreaterThanOrEqualTo(2);
}
```

**Test 2 — `stale_subscription_after_session_end_silently_ignored_on_reconnect`:**

```java
@Test
void stale_subscription_after_session_end_silently_ignored_on_reconnect() throws Exception {
    // Phase 1: Create debate, connect, subscribe
    String startResult = tools.startDebate("test-spec.md", null);
    activeDebateSessionId = extractDebateId(startResult);

    TestClient client1 = new TestClient(5);
    Session session1 = connectWebSocket(client1);
    client1.awaitMessages(5);
    client1.resetLatch(3);
    session1.getBasicRemote().sendText(
            "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
    assertThat(client1.awaitMessages(5)).isTrue();

    // Phase 2: End debate while still connected
    tools.endDebate(activeDebateSessionId, false);

    // Phase 3: Close connection
    session1.close();

    // Phase 4: Reconnect
    TestClient client2 = new TestClient(2);
    wsSession = connectWebSocket(client2);
    assertThat(client2.awaitMessages(5)).isTrue();

    // Phase 5: Re-subscribe to the ended session
    client2.resetLatch(1);
    wsSession.getBasicRemote().sendText(
            "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");

    // No catch-up — session is ended. Latch should NOT count down.
    assertThat(client2.awaitMessages(2)).isFalse();
    assertThat(wsSession.isOpen()).isTrue();

    activeDebateSessionId = null; // already ended — skip teardown
}
```

**Test 3 — `concurrent_push_from_multiple_producers`:**

```java
@Test
void concurrent_push_from_multiple_producers() throws Exception {
    String startResult = tools.startDebate("test-spec.md", null);
    activeDebateSessionId = extractDebateId(startResult);

    TestClient client = new TestClient(5);
    wsSession = connectWebSocket(client);
    client.awaitMessages(5);
    client.resetLatch(3);
    wsSession.getBasicRemote().sendText(
            "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
    assertThat(client.awaitMessages(5)).isTrue();

    // Fire two pushes from different threads, synchronized via CyclicBarrier
    client.resetLatch(2);
    java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);

    java.util.concurrent.CompletableFuture<Void> push1 = java.util.concurrent.CompletableFuture.runAsync(() -> {
        try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
        tools.raisePoint(activeDebateSessionId, "REV", 1, "Concurrent point", "HIGH", null, null);
    });
    java.util.concurrent.CompletableFuture<Void> push2 = java.util.concurrent.CompletableFuture.runAsync(() -> {
        try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
        eventBus.pushMetadata(
                UUID.fromString(activeDebateSessionId),
                "context-usage",
                java.util.Map.of("effectivePercent", 42.0, "serverContributionChars", 1000,
                        "messageCount", 5, "thresholdExceeded", false));
    });

    push1.join();
    push2.join();
    assertThat(client.awaitMessages(5)).isTrue();

    // Verify all messages are valid JSON (not garbled from interleaving)
    for (String raw : client.received) {
        assertThat(mapper.readTree(raw)).isNotNull();
    }
    List<JsonNode> msgs = client.parsedMessages(mapper);
    assertThat(msgs).anyMatch(m -> "debate-entries".equals(m.get("topic").asText()));
    assertThat(msgs).anyMatch(m -> "context-usage".equals(m.get("topic").asText()));
}
```

Add required import at the top of the file:

```java
import java.util.UUID;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateWebSocketTest`
Expected: 3 new tests FAIL (compilation errors for `eventBus` field — `WebSocketEventBus` not injected yet, or tests fail at runtime).

- [ ] **Step 3: Fix compilation — add the injection**

Add `@Inject WebSocketEventBus eventBus;` field to the class (alongside existing `@Inject DebateMcpTools tools;` and `@Inject ObjectMapper mapper;`).

- [ ] **Step 4: Run all DebateWebSocketTest tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateWebSocketTest`
Expected: All 8 tests PASS (5 existing + 3 new).

- [ ] **Step 5: Commit**

```
test: add WebSocket reconnection cycle and concurrent push tests

Three new integration tests in DebateWebSocketTest:
- reconnect_receives_full_catch_up_matching_pre_disconnect_state
- stale_subscription_after_session_end_silently_ignored_on_reconnect
- concurrent_push_from_multiple_producers

Refs #88
```

---

### Task 2: Document Badge Dropdown Component (#85)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js`

**Interfaces:**
- Consumes: `pages-event` CustomEvents (topics: `documents-changed`, `comparison-changed`, `reconnected`)
- Consumes: `POST /api/debate/{sessionId}/comparison` with body `{pathA, pathB}` — existing REST endpoint
- Produces: `<drafthouse-doc-picker>` custom element with `session-id` observed attribute

- [ ] **Step 1: Create the doc-picker component**

Create `server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js`:

```javascript
const styles = new CSSStyleSheet();
styles.replaceSync(`
  :host {
    display: none;
    position: relative;
    align-items: center;
    font-size: 12px;
  }

  :host(.visible) {
    display: inline-flex;
  }

  .badge {
    cursor: pointer;
    user-select: none;
    padding: 2px 6px;
    border-radius: 3px;
  }

  .badge:hover {
    background: var(--accent-light);
  }

  .dropdown {
    position: absolute;
    top: 100%;
    left: 0;
    margin-top: 4px;
    min-width: 280px;
    max-width: 400px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
    z-index: 1000;
    padding: 8px 0;
  }

  .header {
    padding: 4px 12px 8px;
    font-weight: 600;
    font-size: 11px;
    color: var(--muted);
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  .doc-row {
    display: flex;
    align-items: center;
    padding: 4px 12px;
    gap: 6px;
  }

  .doc-row:hover {
    background: var(--chrome);
  }

  .doc-label {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
    color: var(--ink);
  }

  .slot-btn {
    border: 1px solid var(--border);
    background: var(--chrome);
    color: var(--ink);
    padding: 1px 6px;
    border-radius: 3px;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    min-width: 22px;
    text-align: center;
  }

  .slot-btn:hover {
    background: var(--accent-light);
  }

  .slot-btn.active {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
  }

  .slot-btn.pending {
    border-style: dashed;
    border-color: var(--accent);
    color: var(--accent);
  }

  .error-flash {
    padding: 4px 12px;
    font-size: 11px;
    color: var(--error, #c0392b);
  }
`);

class DraftHouseDocPicker extends HTMLElement {
  #shadow = null;
  #unsubscribe = null;
  #documents = [];
  #currentComparison = null;
  #sessionId = null;
  #open = false;
  #pendingA = null;
  #pendingB = null;
  #outsideClickHandler = null;
  #escapeHandler = null;
  #errorTimeout = null;

  static get observedAttributes() {
    return ['session-id'];
  }

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(_props) {}

  attributeChangedCallback(name, _oldVal, newVal) {
    if (name === 'session-id') {
      this.#sessionId = newVal;
    }
  }

  connectedCallback() {
    this.#render();

    const listener = (e) => {
      const { topic, payload } = e.detail;
      if (topic === 'documents-changed') {
        this.#documents = payload.documents || [];
        if (this.#pendingA && !this.#documents.some(d => d.path === this.#pendingA)) {
          this.#pendingA = null;
        }
        if (this.#pendingB && !this.#documents.some(d => d.path === this.#pendingB)) {
          this.#pendingB = null;
        }
        if (this.#documents.length === 0) {
          this.#open = false;
        }
        this.#render();
      } else if (topic === 'comparison-changed') {
        if (payload.pathA == null && payload.pathB == null) {
          this.#currentComparison = null;
        } else {
          this.#currentComparison = { pathA: payload.pathA, pathB: payload.pathB };
        }
        this.#pendingA = null;
        this.#pendingB = null;
        this.#render();
      } else if (topic === 'reconnected') {
        this.#documents = [];
        this.#currentComparison = null;
        this.#pendingA = null;
        this.#pendingB = null;
        this.#open = false;
        this.#render();
      }
    };
    document.addEventListener('pages-event', listener);
    this.#unsubscribe = () => document.removeEventListener('pages-event', listener);
  }

  disconnectedCallback() {
    if (this.#unsubscribe) {
      this.#unsubscribe();
      this.#unsubscribe = null;
    }
    this.#removeGlobalListeners();
    if (this.#errorTimeout) clearTimeout(this.#errorTimeout);
  }

  #render() {
    const count = this.#documents.length;
    this.classList.toggle('visible', count > 0);

    this.#shadow.innerHTML = '';

    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = '\u{1F4C4} ' + count;
    badge.addEventListener('click', (e) => {
      e.stopPropagation();
      this.#open = !this.#open;
      this.#render();
    });
    this.#shadow.appendChild(badge);

    if (!this.#open || count === 0) {
      this.#removeGlobalListeners();
      return;
    }

    const dropdown = document.createElement('div');
    dropdown.className = 'dropdown';

    const header = document.createElement('div');
    header.className = 'header';
    header.textContent = 'Documents';
    dropdown.appendChild(header);

    for (const doc of this.#documents) {
      const row = document.createElement('div');
      row.className = 'doc-row';

      const label = document.createElement('span');
      label.className = 'doc-label';
      label.title = doc.path;
      const parts = doc.path.split('/');
      label.textContent = doc.label || parts[parts.length - 1];
      row.appendChild(label);

      const btnA = document.createElement('button');
      btnA.className = 'slot-btn';
      btnA.textContent = 'A';
      if (this.#currentComparison && this.#currentComparison.pathA === doc.path) {
        btnA.classList.add('active');
      } else if (this.#pendingA === doc.path) {
        btnA.classList.add('pending');
      }
      btnA.addEventListener('click', (e) => {
        e.stopPropagation();
        this.#handleSlotClick('a', doc.path);
      });
      row.appendChild(btnA);

      const btnB = document.createElement('button');
      btnB.className = 'slot-btn';
      btnB.textContent = 'B';
      if (this.#currentComparison && this.#currentComparison.pathB === doc.path) {
        btnB.classList.add('active');
      } else if (this.#pendingB === doc.path) {
        btnB.classList.add('pending');
      }
      btnB.addEventListener('click', (e) => {
        e.stopPropagation();
        this.#handleSlotClick('b', doc.path);
      });
      row.appendChild(btnB);

      dropdown.appendChild(row);
    }

    this.#shadow.appendChild(dropdown);
    this.#addGlobalListeners();
  }

  #handleSlotClick(slot, path) {
    if (this.#currentComparison) {
      // Comparison exists — single-click swap
      if (slot === 'a') {
        if (this.#currentComparison.pathA === path) return; // identity no-op
        this.#postComparison(path, this.#currentComparison.pathB);
      } else {
        if (this.#currentComparison.pathB === path) return; // identity no-op
        this.#postComparison(this.#currentComparison.pathA, path);
      }
    } else {
      // No comparison — pending state
      if (slot === 'a') {
        this.#pendingA = (this.#pendingA === path) ? null : path; // toggle
        if (this.#pendingA && this.#pendingB) {
          this.#postComparison(this.#pendingA, this.#pendingB);
          return;
        }
      } else {
        this.#pendingB = (this.#pendingB === path) ? null : path; // toggle
        if (this.#pendingA && this.#pendingB) {
          this.#postComparison(this.#pendingA, this.#pendingB);
          return;
        }
      }
      this.#render();
    }
  }

  #postComparison(pathA, pathB) {
    if (!this.#sessionId) return;
    fetch(`/api/debate/${this.#sessionId}/comparison`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pathA, pathB }),
    }).then(r => {
      if (!r.ok) {
        this.#showError();
      }
    }).catch(() => {
      this.#showError();
    });
  }

  #showError() {
    if (this.#errorTimeout) clearTimeout(this.#errorTimeout);
    const existing = this.#shadow.querySelector('.error-flash');
    if (existing) existing.remove();
    const dropdown = this.#shadow.querySelector('.dropdown');
    if (!dropdown) return;
    const err = document.createElement('div');
    err.className = 'error-flash';
    err.textContent = 'Failed to update comparison';
    dropdown.appendChild(err);
    this.#errorTimeout = setTimeout(() => {
      err.remove();
      this.#errorTimeout = null;
    }, 3000);
  }

  #addGlobalListeners() {
    if (this.#outsideClickHandler) return;
    this.#outsideClickHandler = (e) => {
      if (!this.contains(e.target) && !this.#shadow.contains(e.target)) {
        this.#open = false;
        this.#render();
      }
    };
    this.#escapeHandler = (e) => {
      if (e.key === 'Escape') {
        this.#open = false;
        this.#render();
      }
    };
    document.addEventListener('click', this.#outsideClickHandler);
    document.addEventListener('keydown', this.#escapeHandler);
  }

  #removeGlobalListeners() {
    if (this.#outsideClickHandler) {
      document.removeEventListener('click', this.#outsideClickHandler);
      this.#outsideClickHandler = null;
    }
    if (this.#escapeHandler) {
      document.removeEventListener('keydown', this.#escapeHandler);
      this.#escapeHandler = null;
    }
  }
}

customElements.define('drafthouse-doc-picker', DraftHouseDocPicker);
```

- [ ] **Step 2: Integrate into index.ts**

Four changes to `server/runtime/src/main/webui/src/index.ts`:

**2a. Add import** (after line 10, with other panel imports):
```typescript
import "./panels/drafthouse-doc-picker.js";
```

**2b. Replace badge HTML** in the topbar `html()` template (line 65):

Replace:
```html
<span id="doc-badge" style="display:none; cursor:pointer;">📄 <span id="doc-count">0</span></span>
```
With:
```html
<drafthouse-doc-picker></drafthouse-doc-picker>
```

**2c. Remove documents-changed handler** from the `pages-event` listener. Delete the entire `if (topic === "documents-changed")` block (lines 173-185).

**2d. Set session-id attribute** in `connectDebateSession()` function. Add after the `if (reviewEl)` line (after line 120):
```typescript
const docPicker = document.querySelector('drafthouse-doc-picker') as any;
if (docPicker) docPicker.setAttribute('session-id', sessionId);
```

- [ ] **Step 3: Build and verify manually**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests`
Expected: Build succeeds. Quinoa bundles the new component.

- [ ] **Step 4: Commit**

```
feat: add document badge dropdown for A/B slot assignment

Replace the static doc-badge span with <drafthouse-doc-picker>, a Shadow DOM
custom element that shows a dropdown with A/B toggle buttons per document.
Clicking A or B assigns the document to that comparison slot via
POST /api/debate/{id}/comparison. Handles pending state for first-time
assignment, identity no-ops, error flash, and reconnection reset.

Refs #85
```

---

### Task 3: Doc-Picker E2E Test

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DocPickerE2ETest.java`

**Interfaces:**
- Consumes: `DebateMcpTools.startDebate()`, `addDocument()`, `setComparison()`, `endDebate()` — existing CDI beans
- Consumes: `DebateE2EFixtures.startDebateSession()`, `extractSessionId()` — existing helpers
- Consumes: `PlaywrightFixtures.fixturePath()` — existing helper
- Produces: 1 passing E2E test verifying the full dropdown interaction

- [ ] **Step 1: Create test fixtures**

Create two additional fixture files for the doc picker test (3 documents needed — `diff-a.md` and `diff-b.md` already exist):

Create `server/runtime/src/test/resources/fixtures/diff-c.md`:
```markdown
# Document C

This is a third test document for the doc picker.

## Section One

Content that differs from document A and B.

## Section Two

More unique content for testing comparison switching.
```

- [ ] **Step 2: Write the E2E test**

Create `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DocPickerE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.DebateE2EFixtures.startDebateSession;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;

@QuarkusTest
@WithPlaywright
class DocPickerE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;

    private Page page;
    private String sessionId;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    @Test
    void dropdown_shows_documents_and_reassigns_comparison() {
        // Setup: create debate with 3 documents and initial comparison
        sessionId = startDebateSession(tools);
        String pathA = fixturePath("diff-a.md");
        String pathB = fixturePath("diff-b.md");
        String pathC = fixturePath("diff-c.md");

        tools.addDocument(sessionId, pathA, "doc-a");
        tools.addDocument(sessionId, pathB, "doc-b");
        tools.addDocument(sessionId, pathC, "doc-c");
        tools.setComparison(sessionId, pathA, pathB);

        // Navigate with debate session
        String encodedA = URLEncoder.encode(pathA, StandardCharsets.UTF_8);
        String encodedB = URLEncoder.encode(pathB, StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + encodedA + "&b=" + encodedB + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);

        // Verify badge is visible with count 3
        Locator picker = page.locator("drafthouse-doc-picker");
        Locator badge = picker.locator(".badge");
        assertThat(badge).containsText("3");

        // Click badge — dropdown appears
        badge.click();
        Locator dropdown = picker.locator(".dropdown");
        assertThat(dropdown).isVisible();

        // Verify 3 document rows
        Locator rows = picker.locator(".doc-row");
        assertThat(rows).hasCount(3);

        // Verify current A assignment is highlighted
        Locator activeA = picker.locator(".slot-btn.active");
        assertThat(activeA.first()).hasText("A");

        // Click A on doc-c to reassign — wait for diff viewer to update
        Locator docCRow = rows.filter(new Locator.FilterOptions().setHasText("doc-c"));
        docCRow.locator(".slot-btn", new Locator.LocatorOptions().setHasText("A")).click();

        // Wait for comparison-changed event to propagate — the diff viewer
        // re-renders with the new file, producing new [data-diff-chunk] elements
        page.locator("[data-diff-chunk]").first().waitFor();

        // Verify the A button on doc-c is now active
        Locator docCActiveA = docCRow.locator(".slot-btn.active");
        assertThat(docCActiveA).hasText("A");

        // Click outside dropdown — should close
        page.locator("#topbar").click(new Locator.ClickOptions().setPosition(1, 1));
        assertThat(dropdown).isHidden();
    }
}
```

- [ ] **Step 3: Run the E2E test**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DocPickerE2ETest`
Expected: PASS

- [ ] **Step 4: Run the full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests PASS (existing + 3 new WebSocket + 1 new E2E).

- [ ] **Step 5: Commit**

```
test: add E2E test for document badge dropdown

Playwright test verifies badge visibility, dropdown document list,
A/B assignment highlighting, comparison reassignment via click,
and outside-click dismissal.

Refs #85
```

---

### Task 4: Issue reconciliation and cleanup

**Files:** None (GitHub only)

- [ ] **Step 1: Comment on #88 about dead connection test coverage**

Add a comment to GitHub issue #88 explaining that test 4 (dead connection self-healing E2E) is covered by the existing unit test `WebSocketEventBusTest.sendText_failure_triggers_unregister()` and why no integration test is needed (the integration dimension — whether Vert.x produces a failed Uni for broken TCP — is a framework guarantee).

```bash
gh issue comment 88 --repo casehubio/drafthouse --body "Dead connection self-healing (test 4) is covered by the unit test \`WebSocketEventBusTest.sendText_failure_triggers_unregister()\` — it mocks a connection whose \`sendText()\` returns a failed Uni, verifies \`unregister()\` fires, and confirms the connection is removed from all watcher sets. The integration dimension (does Vert.x produce a failed Uni for broken TCP?) is a framework guarantee. No additional integration test needed."
```

- [ ] **Step 2: Final full build**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests PASS.
