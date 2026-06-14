# Workspace UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose DraftHouse's monolithic `index.html` into Web Component panels with Shadow DOM, a shared SSE event bus, and a workspace shell — aligned with the `@casehub/ui` Component model.

**Architecture:** Three Web Component panels (`<drafthouse-diff>`, `<drafthouse-debate>`, `<drafthouse-review-tracker>`) sharing a `DebateEventBus` for SSE events, orchestrated by a thin workspace shell. PanelRegistry provides the factory and metadata catalogue. All structural styles move into shadow roots via `adoptedStyleSheets`. UiResource extended to serve `panels/*.js`.

**Tech Stack:** Vanilla JS ES modules, Web Components (Custom Elements + Shadow DOM), `adoptedStyleSheets`, EventSource SSE, Quarkus JAX-RS (UiResource fix), Playwright E2E tests

**Spec:** `docs/superpowers/specs/2026-06-14-workspace-ui-redesign.md`

---

### Task 0: Prerequisite — DECLINED fold fix

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionDeclinedTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebateChannelProjectionDeclinedTest {

    private final DebateChannelProjection projection = new DebateChannelProjection();

    @Test
    void declinedUpdatesPointStatus() {
        ReviewState state = projection.identity();

        // RAISE a point
        MessageView raise = TestMessageView.builder()
                .content("META:entryType=RAISE|agent=REV|round=1|priority=P2|scope=ISOLATED\n\nRemove the fallback")
                .correlationId("point-1")
                .build();
        state = projection.apply(state, raise);
        assertEquals(ReviewStatus.OPEN, state.points().get("point-1").currentStatus());

        // DECLINED response
        MessageView declined = TestMessageView.builder()
                .content("META:entryType=DECLINED|agent=IMP|round=1\n\nNot applicable")
                .correlationId("point-1")
                .build();
        state = projection.apply(state, declined);

        assertEquals(ReviewStatus.DECLINED, state.points().get("point-1").currentStatus());
        assertEquals(2, state.points().get("point-1").thread().size());
        assertEquals(EntryType.DECLINED, state.points().get("point-1").thread().get(1).type());
    }
}
```

Note: `TestMessageView` is a builder for the `MessageView` interface. Check if one already exists in the test fixtures; if not, create a minimal one that implements `MessageView` with `content()`, `correlationId()`, `sender()`, and `createdAt()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionDeclinedTest`
Expected: FAIL — point status remains `OPEN` after DECLINED because `case DECLINED -> state;` is a no-op.

- [ ] **Step 3: Fix the fold — change DECLINED from no-op to appendToPoint**

In `DebateChannelProjection.java`, change:
```java
case DECLINED         -> state;
```
to:
```java
case DECLINED         -> appendToPoint(state, message, meta, EntryType.DECLINED, ReviewStatus.DECLINED);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionDeclinedTest`
Expected: PASS

- [ ] **Step 5: Run all tests to check for regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass (219/219)

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionDeclinedTest.java
git commit -m "fix: DECLINED fold updates point status to DECLINED

DebateChannelProjection.apply() had case DECLINED -> state; (no-op).
Changed to appendToPoint() — same pattern as AGREE/DISPUTE/COUNTER/QUALIFY.
Without this, get_debate_summary and client-side status derivation diverge.

Refs #51"
```

---

### Task 1: UiResource — serve subdirectory files with correct media types

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java` (add one test)

- [ ] **Step 1: Write the failing test**

Add to `HappyPathE2ETest.java`:
```java
@Test
void panelsDirectoryServesJavaScript() {
    // Create a test JS file in the ui.dir panels/ subdirectory
    var response = page.request().get(index + "panels/panel-registry.js");
    // Before Task 2 creates the file, this tests the path matching.
    // For now, verify 404 (file doesn't exist yet) rather than 200.
    // The key assertion is that the route MATCHES (not a 405 Method Not Allowed
    // or a JAX-RS routing failure) — we get a clean 404.
    assertEquals(404, response.status());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=HappyPathE2ETest#panelsDirectoryServesJavaScript`
Expected: FAIL — the current `@Path("{file}")` doesn't match multi-segment paths; the request hits the index route or returns an unexpected status.

- [ ] **Step 3: Fix UiResource**

Replace the entire `UiResource.java` with:
```java
package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

@ApplicationScoped
@jakarta.ws.rs.Path("/")
public class UiResource {

    @ConfigProperty(name = "ui.dir", defaultValue = ".")
    String uiDir;

    private static final Map<String, String> MEDIA_TYPES = Map.of(
            ".css", "text/css",
            ".js", "application/javascript",
            ".mjs", "application/javascript",
            ".html", MediaType.TEXT_HTML,
            ".json", MediaType.APPLICATION_JSON,
            ".svg", "image/svg+xml"
    );

    @GET
    public Response index() throws IOException {
        return serveFile("index.html");
    }

    @GET
    @jakarta.ws.rs.Path("{path:.*}")
    public Response file(@PathParam("path") String path) throws IOException {
        return serveFile(path);
    }

    private Response serveFile(String fileName) throws IOException {
        java.nio.file.Path resolved = java.nio.file.Path.of(uiDir).resolve(fileName).normalize();
        if (!resolved.startsWith(java.nio.file.Path.of(uiDir).normalize())) {
            return Response.status(403).build();
        }
        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            return Response.status(404).build();
        }
        String mediaType = MEDIA_TYPES.entrySet().stream()
                .filter(e -> fileName.endsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return Response.ok(Files.readString(resolved), mediaType).build();
    }
}
```

Key changes:
- `@Path("{path:.*}")` — matches any depth of subdirectory
- Path traversal guard — `resolved.startsWith(uiDir)` prevents `../../etc/passwd`
- Media type map — `.js`, `.mjs`, `.css`, `.html`, `.json`, `.svg`
- Directory guard — returns 404 for directory paths

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=HappyPathE2ETest#panelsDirectoryServesJavaScript`
Expected: PASS (clean 404 — file doesn't exist yet, but the route matches)

- [ ] **Step 5: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java server/runtime/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java
git commit -m "fix: UiResource serves subdirectory files with correct media types

@Path(\"{path:.*}\") replaces @Path(\"{file}\") for recursive directory
serving. Adds .js/.mjs/.json/.svg media type detection. Path traversal
guard prevents directory escape.

Refs #51"
```

---

### Task 2: PanelRegistry + DebateEventBus shared modules

**Files:**
- Create: `panels/panel-registry.js`
- Create: `panels/debate-event-bus.js`

- [ ] **Step 1: Create panel-registry.js**

```javascript
// panels/panel-registry.js
// PanelRegistry — component type catalogue + factory.
// First draft of what @casehub/ui's component type registry will be.

export class PanelRegistry {
  #panels = new Map();

  register(metadata) {
    if (!metadata.type || !metadata.component) {
      throw new Error('PanelRegistry.register() requires type and component');
    }
    if (this.#panels.has(metadata.type)) {
      throw new Error(`Panel type '${metadata.type}' already registered`);
    }
    customElements.define(metadata.type, metadata.component);
    this.#panels.set(metadata.type, {
      type: metadata.type,
      label: metadata.label || metadata.type,
      icon: metadata.icon || '',
      propsSchema: metadata.propsSchema || {},
    });
  }

  get(type) {
    const meta = this.#panels.get(type);
    if (!meta) throw new Error(`Unknown panel type: '${type}'`);
    return meta;
  }

  create(type, props) {
    if (!this.#panels.has(type)) {
      throw new Error(`Unknown panel type: '${type}'`);
    }
    const el = document.createElement(type);
    if (props) el.configure(props);
    return el;
  }

  types() {
    return [...this.#panels.keys()];
  }
}

export const registry = new PanelRegistry();
```

- [ ] **Step 2: Create debate-event-bus.js**

```javascript
// panels/debate-event-bus.js
// Shared SSE connection for debate events.
// Shell owns connect/disconnect; panels subscribe/unsubscribe orthogonally.

export class DebateEventBus {
  #eventSource = null;
  #sessionId = null;
  #subscribers = new Set();

  get connected() { return this.#eventSource !== null; }
  get sessionId() { return this.#sessionId; }

  connect(debateSessionId) {
    if (this.#sessionId === debateSessionId && this.#eventSource) return;
    this.disconnect();
    this.#sessionId = debateSessionId;
    this.#eventSource = new EventSource(
      '/api/debate/' + encodeURIComponent(debateSessionId) + '/events'
    );
    this.#eventSource.onmessage = (e) => {
      let data;
      try { data = JSON.parse(e.data); } catch { return; }
      if (data.type === 'heartbeat') return;
      const entries = Array.isArray(data) ? data : [data];
      for (const sub of this.#subscribers) {
        try { sub.onEntries(entries); } catch (err) {
          console.error('DebateEventBus subscriber error:', err);
        }
      }
    };
    this.#eventSource.onerror = () => {
      for (const sub of this.#subscribers) {
        try { if (sub.onReconnect) sub.onReconnect(); } catch (err) {
          console.error('DebateEventBus reconnect handler error:', err);
        }
      }
    };
  }

  disconnect() {
    if (this.#eventSource) {
      this.#eventSource.close();
      this.#eventSource = null;
    }
    this.#sessionId = null;
  }

  subscribe({ onEntries, onReconnect }) {
    if (typeof onEntries !== 'function') {
      throw new Error('subscribe() requires onEntries callback');
    }
    const sub = { onEntries, onReconnect: onReconnect || null };
    this.#subscribers.add(sub);
    return () => { this.#subscribers.delete(sub); };
  }
}

export const debateEventBus = new DebateEventBus();
```

- [ ] **Step 3: Verify files are loadable as ES modules**

Create a minimal test HTML (not committed — manual verification):
```html
<script type="module">
  import { registry } from './panels/panel-registry.js';
  import { debateEventBus } from './panels/debate-event-bus.js';
  console.log('registry types:', registry.types());
  console.log('bus connected:', debateEventBus.connected);
</script>
```

Start the server, open in browser, check console for no errors and correct output.

- [ ] **Step 4: Commit**

```
git add panels/panel-registry.js panels/debate-event-bus.js
git commit -m "feat: PanelRegistry and DebateEventBus shared modules

PanelRegistry — component type catalogue + factory. Registers custom
elements, provides create(type, props) factory. First draft of
@casehub/ui component type metadata.

DebateEventBus — shared SSE connection. Shell owns connect/disconnect;
panels subscribe({ onEntries, onReconnect }) orthogonally. Heartbeats
filtered, reconnect signalled via separate callback.

Refs #51"
```

---

### Task 3: Diff panel extraction — `<drafthouse-diff>`

This is the largest task — extracting ~660 lines of diff logic from `index.html` into a Web Component with Shadow DOM and `adoptedStyleSheets`.

**Files:**
- Create: `panels/drafthouse-diff.js`
- Modify: `index.html` (strip diff logic, replace with shell)
- Modify: `styles.css` (strip panel-internal styles, keep shell + tokens)

**Approach:** Extract the diff logic first, then rewrite `index.html` as the shell (Task 5). This task creates the panel; Task 5 creates the shell that uses it.

- [ ] **Step 1: Create drafthouse-diff.js with the full diff panel Web Component**

Extract all diff-related code from `index.html` into `panels/drafthouse-diff.js`. The file should:

1. Define a `CSSStyleSheet` with all panel-internal structural styles (moved from `styles.css` — the `.panel`, `.panel-header`, `.panel-label`, `.panel-path`, `.panel-body`, `.panel-empty`, `.md-wrap`, `.diff-del`, `.diff-ins`, `mark.diff-word-a`, `mark.diff-word-b`, `#divider`, `#diff-map` styles)
2. Define the `DraftHouseDiff` class extending `HTMLElement`
3. Implement `configure(props)`, `connectedCallback()`, `disconnectedCallback()`
4. Move all functions: `lineDiff()`, `wordDiff()`, `applyWordHighlights()`, `annotateWordDiffs()`, `drawDiffMap()`, `annotateRendered()`, `updateDiffMap()`, `buildScrollAnchors()`, `interp()`, `scrollPercent()`, `setupScrollSync()`, `toggleSync()`, `nextDiff()`, `prevDiff()`, `swapPanels()`, `selectFile()`, `loadFile()`, `fetchFile()`, `watchFile()`, `unwatchFile()`, `getDiffSummary()`, `updateDiffSummary()`, `updateNavButtons()`, `updateNavCounter()`, `updateSwapButton()`, `setupDropZone()`, `renderMarkdown()`, `scrollToChunk()`, `chunkOutOfView()`, `nonEqIndices()`, `normHead()`, `getScrollAnchors()`
5. Add new `scrollToLocation(location)` method
6. Emit `diff-updated` and `selection-changed` CustomEvents
7. Register with PanelRegistry

The Shadow DOM template should replicate the current panel HTML structure (panel-a, divider, panel-b) inside the shadow root.

**Key implementation note:** The `apiUrl()` helper function currently builds URLs relative to `API_PORT` for Electron mode. In the Web Component, use relative URLs (`/api/file?path=...`) for browser mode. Pass `apiPort` as an optional prop for Electron.

**Key implementation note:** `marked.js` and `highlight.js` are loaded via CDN `<script>` tags in the `<head>`. They set globals (`marked`, `hljs`). The Web Component references these globals — they are NOT moved into the shadow root. The `<script>` tags stay in `index.html`.

- [ ] **Step 2: Implement scrollToLocation()**

New method on the DraftHouseDiff class:

```javascript
scrollToLocation(location) {
  if (!location) return;
  const ref = location.startsWith('§') ? location.slice(1).trim() : location.trim();
  if (!ref) return;

  const numMatch = ref.match(/^(\d+)(?:\.(\d+))?$/);
  for (const side of ['a', 'b']) {
    const headings = [...this._shadow.querySelectorAll(
      `#render-${side} h1, #render-${side} h2, #render-${side} h3, #render-${side} h4`
    )];
    let target = null;
    if (numMatch) {
      const major = parseInt(numMatch[1], 10);
      const minor = numMatch[2] ? parseInt(numMatch[2], 10) : null;
      const topLevel = headings.filter(h => h.tagName === 'H2' || h.tagName === 'H1');
      if (major >= 1 && major <= topLevel.length) {
        if (minor === null) {
          target = topLevel[major - 1];
        } else {
          const start = headings.indexOf(topLevel[major - 1]);
          const nextTop = topLevel[major] ? headings.indexOf(topLevel[major]) : headings.length;
          const subHeadings = headings.slice(start + 1, nextTop);
          if (minor >= 1 && minor <= subHeadings.length) {
            target = subHeadings[minor - 1];
          }
        }
      }
    } else {
      const lower = ref.toLowerCase();
      target = headings.find(h =>
        h.textContent.toLowerCase().includes(lower)
      );
    }
    if (target) {
      const body = this._shadow.querySelector(`#body-${side}`);
      const delta = target.getBoundingClientRect().top - body.getBoundingClientRect().top - 24;
      body.scrollBy({ top: delta, behavior: 'instant' });
    }
  }
}
```

- [ ] **Step 3: Verify the panel file loads without errors**

Start the server. Temporarily add `<script type="module" src="panels/drafthouse-diff.js"></script>` to `index.html`. Open in browser. Check console — no errors. The panel won't render yet (the shell hasn't been built), but it should register without errors: `customElements.get('drafthouse-diff')` should return the class.

- [ ] **Step 4: Commit**

```
git add panels/drafthouse-diff.js
git commit -m "feat: extract diff logic into <drafthouse-diff> Web Component

Moves ~660 lines of diff rendering, LCS engine, word-level highlighting,
canvas minimap, scroll sync, file watch SSE, and drag-and-drop from
index.html into a Shadow DOM Web Component with adoptedStyleSheets.

Adds scrollToLocation() for cross-panel review point navigation.
Emits diff-updated and selection-changed CustomEvents.
Registers with PanelRegistry.

Refs #51"
```

---

### Task 4: Debate panel — `<drafthouse-debate>`

**Files:**
- Create: `panels/drafthouse-debate.js`

- [ ] **Step 1: Create drafthouse-debate.js**

The debate panel subscribes to `DebateEventBus` and renders a conversation feed grouped by round.

```javascript
// panels/drafthouse-debate.js
import { registry } from './panel-registry.js';
import { debateEventBus } from './debate-event-bus.js';

const debateStyles = new CSSStyleSheet();
debateStyles.replaceSync(`
  :host { display: flex; flex-direction: column; overflow: hidden; height: 100%; }
  .debate-feed { flex: 1; overflow-y: auto; padding: 12px; }
  .round-header {
    font-size: 10px; font-weight: 700; color: var(--muted);
    text-transform: uppercase; letter-spacing: .08em;
    font-family: 'SFMono-Regular', Consolas, monospace;
    padding: 8px 0 4px; border-bottom: 1px solid var(--border-light);
    margin-top: 16px;
  }
  .round-header:first-child { margin-top: 0; }
  .entry {
    padding: 8px 12px; margin: 6px 0; border-radius: 2px;
    border: 1px solid var(--border-light); background: var(--bg);
    font-size: 12px; line-height: 1.6; cursor: pointer;
  }
  .entry:hover { border-color: var(--border); }
  .entry-header {
    display: flex; align-items: center; gap: 6px;
    font-size: 10px; color: var(--muted); margin-bottom: 4px;
    font-family: 'SFMono-Regular', Consolas, monospace;
  }
  .entry-type { font-weight: 700; text-transform: uppercase; }
  .entry-agent { color: var(--accent); }
  .entry-content { color: var(--sepia); white-space: pre-wrap; }
  .badge {
    display: inline-block; padding: 1px 6px; border-radius: 2px;
    font-size: 9px; font-weight: 700; text-transform: uppercase;
  }
  .badge-priority { background: var(--warn); color: white; }
  .badge-scope { background: var(--accent-tint); color: var(--accent); }

  .entry-raise { border-left: 3px solid var(--border); }
  .entry-agree { border-left: 3px solid var(--approve); }
  .entry-counter { border-left: 3px solid var(--warn); }
  .entry-dispute { border-left: 3px solid #ef4444; }
  .entry-qualify { border-left: 3px solid var(--accent); }
  .entry-flag_human {
    border: 2px solid var(--warn); background: #fdf6ec;
  }
  .entry-memo { font-style: italic; color: var(--muted); border-color: transparent; }
  .entry-sub_task_request,
  .entry-sub_task_finding,
  .entry-sub_task_error { margin-left: 24px; font-size: 11px; color: var(--muted); }
  .entry-sub_task_error { border-left: 3px solid #ef4444; }
  .entry-restart_context {
    border: none; background: none; text-align: center;
    color: var(--muted); font-size: 10px; font-style: italic;
    border-top: 1px dashed var(--border); border-bottom: 1px dashed var(--border);
    padding: 4px 0; margin: 12px 0;
  }
  .entry-declined { text-decoration: line-through; color: var(--muted); }

  .no-session {
    display: flex; align-items: center; justify-content: center;
    height: 100%; color: var(--muted); font-style: italic; font-size: 13px;
  }
  .jump-latest {
    position: absolute; bottom: 12px; right: 12px;
    background: var(--accent); color: white; border: none;
    border-radius: 2px; padding: 4px 10px; font-size: 11px; cursor: pointer;
  }
  .jump-latest.hidden { display: none; }
`);

class DraftHouseDebate extends HTMLElement {
  #shadow;
  #props = null;
  #entries = [];
  #unsubscribe = null;
  #userScrolledUp = false;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [debateStyles];
  }

  configure(props) {
    this.#props = props;
    if (this.isConnected) this.#init();
  }

  connectedCallback() {
    if (this.#props) this.#init();
    else this.#renderNoSession();
  }

  disconnectedCallback() {
    if (this.#unsubscribe) { this.#unsubscribe(); this.#unsubscribe = null; }
  }

  #init() {
    if (this.#unsubscribe) { this.#unsubscribe(); this.#unsubscribe = null; }
    this.#entries = [];
    this.#unsubscribe = debateEventBus.subscribe({
      onEntries: (entries) => {
        this.#entries.push(...entries);
        this.#render();
        this.#autoScroll();
      },
      onReconnect: () => {
        this.#entries = [];
        this.#userScrolledUp = false;
      },
    });
    this.#render();
  }

  #renderNoSession() {
    this.#shadow.innerHTML = '<div class="no-session">Waiting for debate session…</div>';
  }

  #render() {
    if (!this.#entries.length) {
      this.#shadow.innerHTML = '<div class="no-session">Waiting for debate events…</div>';
      return;
    }

    const rounds = new Map();
    for (const e of this.#entries) {
      const r = e.round || 0;
      if (!rounds.has(r)) rounds.set(r, []);
      rounds.get(r).push(e);
    }

    const feed = document.createElement('div');
    feed.className = 'debate-feed';
    feed.addEventListener('scroll', () => {
      const max = feed.scrollHeight - feed.clientHeight;
      this.#userScrolledUp = max > 0 && (max - feed.scrollTop) > 50;
    });

    for (const [round, entries] of [...rounds.entries()].sort((a, b) => a[0] - b[0])) {
      const header = document.createElement('div');
      header.className = 'round-header';
      header.textContent = 'Round ' + round;
      feed.appendChild(header);

      for (const entry of entries) {
        const el = this.#renderEntry(entry);
        feed.appendChild(el);
      }
    }

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(feed);
  }

  #renderEntry(entry) {
    const el = document.createElement('div');
    const typeLower = (entry.entryType || '').toLowerCase();
    el.className = 'entry entry-' + typeLower;
    el.dataset.pointId = entry.pointId || '';

    if (entry.entryType === 'RESTART_CONTEXT') {
      el.textContent = '── session branched ──';
      return el;
    }

    const header = document.createElement('div');
    header.className = 'entry-header';
    header.innerHTML =
      '<span class="entry-agent">' + (entry.agentRole || '') + '</span>' +
      '<span class="entry-type">' + (entry.entryType || '') + '</span>' +
      (entry.pointId ? ' <span>· ' + entry.pointId.slice(0, 8) + '</span>' : '') +
      (entry.priority ? ' <span class="badge badge-priority">' + entry.priority + '</span>' : '') +
      (entry.scope ? ' <span class="badge badge-scope">' + entry.scope + '</span>' : '');
    el.appendChild(header);

    const content = document.createElement('div');
    content.className = 'entry-content';
    content.textContent = entry.content || '';
    el.appendChild(content);

    if (entry.pointId) {
      el.addEventListener('click', () => {
        this.dispatchEvent(new CustomEvent('point-selected', {
          bubbles: true,
          detail: {
            pointId: entry.pointId,
            round: entry.round,
            location: entry.location || null,
          },
        }));
      });
    }

    return el;
  }

  #autoScroll() {
    if (this.#userScrolledUp) return;
    const feed = this.#shadow.querySelector('.debate-feed');
    if (feed) feed.scrollTop = feed.scrollHeight;
  }
}

registry.register({
  type: 'drafthouse-debate',
  component: DraftHouseDebate,
  label: 'Debate',
  icon: '💬',
  propsSchema: {
    debateSessionId: { type: 'string' },
  },
});
```

- [ ] **Step 2: Verify the panel registers without errors**

Start server, add `<script type="module" src="panels/drafthouse-debate.js"></script>` to `index.html` (temporary). Open in browser. Console should show no errors. `customElements.get('drafthouse-debate')` should return the class.

- [ ] **Step 3: Commit**

```
git add panels/drafthouse-debate.js
git commit -m "feat: debate panel — SSE event feed with conversation rendering

<drafthouse-debate> Web Component subscribes to DebateEventBus, renders
conversation feed grouped by round. Visual treatment per EntryType:
colour-coded borders, priority badges, scope tags, sub-task indentation.
Auto-scroll with jump-to-latest. No-session placeholder.

Refs #51"
```

---

### Task 5: Review tracker — `<drafthouse-review-tracker>`

**Files:**
- Create: `panels/drafthouse-review-tracker.js`

- [ ] **Step 1: Create drafthouse-review-tracker.js**

The review tracker subscribes to `DebateEventBus` and renders a status-derived checklist.

```javascript
// panels/drafthouse-review-tracker.js
import { registry } from './panel-registry.js';
import { debateEventBus } from './debate-event-bus.js';

const ENTRY_TO_STATUS = {
  RAISE: 'OPEN',
  AGREE: 'AGREED',
  COUNTER: 'ACTIVE',
  DISPUTE: 'DISPUTED',
  QUALIFY: 'ACTIVE',
  FLAG_HUMAN: 'PENDING_HUMAN',
  DECLINED: 'DECLINED',
};

const STATUS_ICON = {
  OPEN: '○',
  ACTIVE: '⟳',
  AGREED: '✓',
  DISPUTED: '✕',
  PENDING_HUMAN: '⚑',
  DECLINED: '✓',
};

const STATUS_ORDER = { OPEN: 0, PENDING_HUMAN: 1, ACTIVE: 2, DISPUTED: 3, AGREED: 4, DECLINED: 5 };

const trackerStyles = new CSSStyleSheet();
trackerStyles.replaceSync(`
  :host { display: flex; flex-direction: column; overflow: hidden; height: 100%; }
  .tracker { flex: 1; overflow-y: auto; padding: 12px; }
  .progress {
    display: flex; align-items: center; gap: 8px; padding: 8px 0;
    font-size: 11px; color: var(--muted); border-bottom: 1px solid var(--border-light);
    margin-bottom: 8px;
  }
  .progress-bar {
    flex: 1; height: 6px; background: var(--chrome); border-radius: 3px; overflow: hidden;
  }
  .progress-fill { height: 100%; background: var(--approve); border-radius: 3px; transition: width .3s; }
  .filters {
    display: flex; gap: 6px; padding: 4px 0 8px; font-size: 10px;
  }
  .filter-btn {
    background: var(--chrome); border: 1px solid var(--border-light);
    border-radius: 2px; padding: 2px 8px; cursor: pointer; font-size: 10px;
    color: var(--muted);
  }
  .filter-btn.active { background: var(--accent-tint); border-color: var(--accent); color: var(--accent); }
  .point {
    display: flex; align-items: flex-start; gap: 8px; padding: 8px;
    border-bottom: 1px solid var(--border-light); cursor: pointer; font-size: 12px;
  }
  .point:hover { background: var(--chrome); }
  .point-icon { font-size: 14px; flex-shrink: 0; width: 18px; text-align: center; }
  .point-body { flex: 1; min-width: 0; }
  .point-summary { color: var(--sepia); }
  .point-trail { font-size: 10px; color: var(--muted); margin-top: 2px; }
  .point-location {
    font-size: 10px; color: var(--accent); font-family: 'SFMono-Regular', Consolas, monospace;
  }
  .status-OPEN .point-icon { color: var(--muted); }
  .status-ACTIVE .point-icon { color: var(--warn); }
  .status-AGREED .point-icon { color: var(--approve); }
  .status-AGREED .point-summary { text-decoration: line-through; color: var(--muted); }
  .status-DISPUTED .point-icon { color: #ef4444; }
  .status-PENDING_HUMAN .point-icon { color: var(--warn); }
  .status-DECLINED .point-icon { color: var(--muted); }
  .status-DECLINED .point-summary { text-decoration: line-through; color: var(--muted); }
  .status-ACTIVE.qualify .point-icon { color: var(--accent); }
  .no-session {
    display: flex; align-items: center; justify-content: center;
    height: 100%; color: var(--muted); font-style: italic; font-size: 13px;
  }
`);

class DraftHouseReviewTracker extends HTMLElement {
  #shadow;
  #props = null;
  #entries = [];
  #unsubscribe = null;
  #showResolved = true;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [trackerStyles];
  }

  configure(props) {
    this.#props = props;
    if (this.isConnected) this.#init();
  }

  connectedCallback() {
    if (this.#props) this.#init();
    else this.#renderNoSession();
  }

  disconnectedCallback() {
    if (this.#unsubscribe) { this.#unsubscribe(); this.#unsubscribe = null; }
  }

  #init() {
    if (this.#unsubscribe) { this.#unsubscribe(); this.#unsubscribe = null; }
    this.#entries = [];
    this.#unsubscribe = debateEventBus.subscribe({
      onEntries: (entries) => {
        this.#entries.push(...entries);
        this.#render();
      },
      onReconnect: () => {
        this.#entries = [];
      },
    });
    this.#render();
  }

  #renderNoSession() {
    this.#shadow.innerHTML = '<div class="no-session">Waiting for debate session…</div>';
  }

  #derivePoints() {
    const points = new Map();
    for (const e of this.#entries) {
      if (!e.pointId) continue;
      if (e.entryType === 'SUB_TASK_REQUEST' || e.entryType === 'SUB_TASK_FINDING' || e.entryType === 'SUB_TASK_ERROR') continue;
      if (!points.has(e.pointId)) {
        points.set(e.pointId, {
          pointId: e.pointId,
          summary: (e.content || '').split('\n')[0].slice(0, 120),
          priority: e.priority,
          scope: e.scope,
          location: e.location,
          status: 'OPEN',
          lastEntryType: e.entryType,
          trail: [],
        });
      }
      const point = points.get(e.pointId);
      const mapped = ENTRY_TO_STATUS[e.entryType];
      if (mapped) {
        point.status = mapped;
        point.lastEntryType = e.entryType;
      }
      point.trail.push((e.agentRole || '?') + ' ' + (e.entryType || '?').toLowerCase());
    }
    return [...points.values()].sort((a, b) =>
      (STATUS_ORDER[a.status] ?? 99) - (STATUS_ORDER[b.status] ?? 99)
    );
  }

  #render() {
    const points = this.#derivePoints();
    const resolved = points.filter(p => p.status === 'AGREED' || p.status === 'DECLINED').length;
    const total = points.length;
    const pct = total > 0 ? Math.round((resolved / total) * 100) : 0;

    const tracker = document.createElement('div');
    tracker.className = 'tracker';

    // Progress bar
    const progress = document.createElement('div');
    progress.className = 'progress';
    progress.innerHTML =
      '<div class="progress-bar"><div class="progress-fill" style="width:' + pct + '%"></div></div>' +
      '<span>' + resolved + ' of ' + total + ' resolved</span>';
    tracker.appendChild(progress);

    // Filter toggles
    const filters = document.createElement('div');
    filters.className = 'filters';
    const resolvedBtn = document.createElement('button');
    resolvedBtn.className = 'filter-btn' + (this.#showResolved ? ' active' : '');
    resolvedBtn.textContent = this.#showResolved ? 'Hide resolved' : 'Show resolved';
    resolvedBtn.addEventListener('click', () => {
      this.#showResolved = !this.#showResolved;
      this.#render();
    });
    filters.appendChild(resolvedBtn);
    tracker.appendChild(filters);

    // Points
    const visible = this.#showResolved
      ? points
      : points.filter(p => p.status !== 'AGREED' && p.status !== 'DECLINED');

    for (const point of visible) {
      const el = document.createElement('div');
      const qualifyClass = point.lastEntryType === 'QUALIFY' ? ' qualify' : '';
      el.className = 'point status-' + point.status + qualifyClass;

      el.innerHTML =
        '<div class="point-icon">' + (STATUS_ICON[point.status] || '○') + '</div>' +
        '<div class="point-body">' +
          '<div class="point-summary">' + this.#escapeHtml(point.summary) + '</div>' +
          '<div class="point-trail">' + point.trail.join(' → ') + '</div>' +
          (point.location ? '<div class="point-location">§' + this.#escapeHtml(point.location) + '</div>' : '') +
        '</div>';

      el.addEventListener('click', () => {
        this.dispatchEvent(new CustomEvent('point-selected', {
          bubbles: true,
          detail: {
            pointId: point.pointId,
            round: 0,
            location: point.location || null,
          },
        }));
      });

      tracker.appendChild(el);
    }

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(tracker);
  }

  #escapeHtml(s) {
    const el = document.createElement('span');
    el.textContent = s;
    return el.innerHTML;
  }
}

registry.register({
  type: 'drafthouse-review-tracker',
  component: DraftHouseReviewTracker,
  label: 'Review',
  icon: '☑',
  propsSchema: {
    debateSessionId: { type: 'string' },
  },
});
```

- [ ] **Step 2: Verify the panel registers without errors**

Start server, add `<script type="module" src="panels/drafthouse-review-tracker.js"></script>` to `index.html` (temporary). Open in browser. Console should show no errors.

- [ ] **Step 3: Commit**

```
git add panels/drafthouse-review-tracker.js
git commit -m "feat: review tracker — status-derived checklist with lifecycle

<drafthouse-review-tracker> Web Component subscribes to DebateEventBus,
derives ReviewStatus per pointId from DebateStreamEntry sequence.
Progress bar, sorted display (open → active → resolved), show/hide
resolved filter, strikethrough on AGREED/DECLINED, agent trail.
Client fold is ~30 lines: filter, group-by pointId, map last entryType.

Refs #51"
```

---

### Task 6: Workspace shell — rewrite index.html

**Files:**
- Modify: `index.html` (rewrite as workspace shell)
- Modify: `styles.css` (strip panel-internal styles)

- [ ] **Step 1: Strip panel-internal styles from styles.css**

Remove from `styles.css`:
- `.panel` (line 55)
- `#divider`, `#diff-map` (lines 57–62)
- `.panel-header`, `.panel-label`, `.panel-path` (lines 64–81)
- `.panel-body` (lines 83–86)
- `.panel-empty` and children (lines 88–97)
- `.panel-body.drag-over` (line 100)
- `.md-wrap` and all children (lines 102–130)
- `.diff-del`, `.diff-ins` (lines 132–142)
- `#critique-panel`, `#critique-hdr`, `#critique-body` (lines 144–162)
- `mark.diff-word-a`, `mark.diff-word-b` (lines 185–187)

Keep:
- `:root` tokens (lines 1–16)
- `*` reset, `body` (lines 18–23)
- `#topbar`, `#logo`, `.sep`, `#topbar-spacer` (lines 25–38)
- `button` styles (lines 40–48)
- `#main`, `#panels` (lines 50–52)
- `#diff-summary::after` tooltip (lines 164–183)
- `#diff-legend` and children (lines 189–207)

Add new shell layout styles for the slot system:
```css
/* ── Workspace slots ── */
.slot { display: flex; flex-direction: column; overflow: hidden; }
.slot.hidden { display: none; }
.slot-left { flex: 3; }
.slot-right { flex: 2; display: flex; flex-direction: column; }
.slot-right-top { flex: 3; }
.slot-right-bottom { flex: 2; }
.slot-bottom { flex-shrink: 0; height: 200px; border-top: 1px solid var(--border); }
.slot-divider {
  width: 6px; background: var(--chrome); cursor: col-resize; flex-shrink: 0;
  border-left: 1px solid var(--border); border-right: 1px solid var(--border);
}
.slot-divider-h {
  height: 6px; background: var(--chrome); cursor: row-resize; flex-shrink: 0;
  border-top: 1px solid var(--border); border-bottom: 1px solid var(--border);
}
```

- [ ] **Step 2: Rewrite index.html as the workspace shell**

Replace the current `index.html` content with the workspace shell. The shell:
1. Loads CDN scripts (marked.js, highlight.js) and registers the DRL language
2. Loads panel modules via `<script type="module">`
3. Creates the slot layout (left, right-top, right-bottom, bottom)
4. Reads URL params (`?a=`, `?b=`, `?debate=`)
5. Creates panels via `registry.create()` and inserts into slots
6. Wires topbar buttons to diff panel methods
7. Wires cross-panel `point-selected` event routing
8. Implements session discovery polling (§7a)
9. Implements panel toggle buttons
10. Implements slot divider dragging

The shell should be ~250 lines. It does NOT contain any diff logic, debate rendering, or review tracking logic.

- [ ] **Step 3: Test manually — diff-only mode**

Start server. Open `http://localhost:9001/?a=/path/to/sample-a.md&b=/path/to/sample-b.md`.
Verify:
- Diff panel renders in the left slot
- Minimap visible
- Scroll sync works
- Next/prev diff navigation works from topbar
- Swap button works

- [ ] **Step 4: Test manually — with debate session**

Start a debate session via MCP tools, then open `http://localhost:9001/?a=...&b=...&debate=<sessionId>`.
Verify:
- Debate panel appears in right-top slot
- Review tracker appears in right-bottom slot
- Events stream in and render
- Clicking a point in the tracker fires `point-selected`
- Panel toggles show/hide panels

- [ ] **Step 5: Commit**

```
git add index.html styles.css
git commit -m "feat: workspace shell — fixed-slot layout with panel orchestration

Rewrite index.html as ~250-line workspace shell. Panel-internal styles
moved into Web Component shadow roots (Task 3-5). Shell manages layout
slots, topbar, cross-panel event routing, session discovery polling,
and URL state parsing. Critique panel stub removed (superseded).

Refs #51"
```

---

### Task 7: E2E test migration — Shadow DOM selectors

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DiffRenderingE2ETest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/WordDiffE2ETest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/ScrollSyncE2ETest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/SwapPanelsE2ETest.java`

Unaffected (topbar selectors — no changes):
- `NavigationE2ETest`
- `DiffSummaryE2ETest`
- `DiffLegendE2ETest`

- [ ] **Step 1: Add shadowLocator helper to PlaywrightFixtures**

```java
public static Locator shadowLocator(Page page, String hostType, String selector) {
    return page.locator(hostType).locator(">>> " + selector);
}
```

Update `waitForRender()`:
```java
public static void waitForRender(Page page) {
    page.locator("drafthouse-diff").locator(">>> [data-diff-chunk]").first().waitFor();
}
```

- [ ] **Step 2: Migrate each affected test class**

For each test class, replace direct selectors with `shadowLocator()`:
- `page.locator("#render-a")` → `shadowLocator(page, "drafthouse-diff", "#render-a")`
- `page.locator("#render-b")` → `shadowLocator(page, "drafthouse-diff", "#render-b")`
- `page.locator("#body-a")` → `shadowLocator(page, "drafthouse-diff", "#body-a")`
- `page.locator("#body-b")` → `shadowLocator(page, "drafthouse-diff", "#body-b")`
- `page.locator("[data-diff-chunk]")` → `shadowLocator(page, "drafthouse-diff", "[data-diff-chunk]")`
- `page.locator(".diff-del")` → `shadowLocator(page, "drafthouse-diff", ".diff-del")`
- `page.locator(".diff-ins")` → `shadowLocator(page, "drafthouse-diff", ".diff-ins")`
- `page.locator(".diff-word-a")` → `shadowLocator(page, "drafthouse-diff", ".diff-word-a")`
- `page.locator(".diff-word-b")` → `shadowLocator(page, "drafthouse-diff", ".diff-word-b")`
- `page.locator("#label-a")` → `shadowLocator(page, "drafthouse-diff", "#label-a")`
- `page.locator("#label-b")` → `shadowLocator(page, "drafthouse-diff", "#label-b")`

- [ ] **Step 3: Run all E2E tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 4: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/
git commit -m "test: migrate E2E selectors for Shadow DOM

Add shadowLocator() helper to PlaywrightFixtures for piercing open
Shadow DOM via Playwright's >>> combinator. Migrate 5 test classes
with panel-internal selectors. 3 test classes unchanged (topbar
selectors in shell light DOM).

Refs #51"
```

---

### Task 8: Debate panel + review tracker E2E tests

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/ReviewTrackerE2ETest.java`
- Create: `server/runtime/src/test/resources/fixtures/debate-fixture.md` (if needed)

- [ ] **Step 1: Write debate panel E2E tests**

Test that the debate panel renders events from the SSE stream. These tests need an active debate session — use `start_debate` MCP tool programmatically via the existing test infrastructure (or set up a fixture debate session in the test `@BeforeAll`).

Key test cases:
- Panel shows "Waiting for debate session" placeholder when no session is configured
- Panel renders entries grouped by round when SSE events arrive
- Entry type visual treatment: RAISE has neutral border, AGREE has green border, DISPUTE has red border
- Auto-scroll: new entries scroll into view
- `point-selected` event fires when clicking an entry with a pointId

- [ ] **Step 2: Write review tracker E2E tests**

Key test cases:
- Tracker shows "Waiting for debate session" placeholder when no session
- Progress bar shows "0 of N resolved" initially
- AGREE on a point changes its status icon to `✓` and adds strikethrough
- DECLINED on a point changes its status icon to `✓` (grey) with strikethrough
- "Hide resolved" filter hides AGREED/DECLINED points
- Points sorted: OPEN first, then ACTIVE, then resolved

- [ ] **Step 3: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass (existing + new)

- [ ] **Step 4: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java server/runtime/src/test/java/io/casehub/drafthouse/e2e/ReviewTrackerE2ETest.java
git commit -m "test: E2E tests for debate panel and review tracker

Debate panel: placeholder, round grouping, entry type visual treatment,
auto-scroll, point-selected event. Review tracker: placeholder, progress
bar, status derivation, strikethrough, filter toggles, sort order.

Refs #51"
```

---

### Task 9: scrollToLocation E2E tests + cross-panel coordination

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java`

- [ ] **Step 1: Write scrollToLocation tests**

```java
@Test
void scrollToNumericReference() {
    // Load files with known headings
    loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
    // Call scrollToLocation via JS on the diff panel element
    page.evaluate("document.querySelector('drafthouse-diff').scrollToLocation('§2')");
    // Verify the second top-level heading is near the top of the viewport
    // (check scrollTop changed from 0)
    var scrollTop = page.evaluate(
        "document.querySelector('drafthouse-diff').shadowRoot.querySelector('#body-a').scrollTop");
    assertTrue((double) scrollTop > 0, "scrollToLocation should scroll the panel");
}

@Test
void scrollToTextReference() {
    loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
    page.evaluate("document.querySelector('drafthouse-diff').scrollToLocation('§Architecture')");
    var scrollTop = page.evaluate(
        "document.querySelector('drafthouse-diff').shadowRoot.querySelector('#body-a').scrollTop");
    assertTrue((double) scrollTop > 0, "text reference should scroll to matching heading");
}

@Test
void scrollToNoMatch() {
    loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
    var before = page.evaluate(
        "document.querySelector('drafthouse-diff').shadowRoot.querySelector('#body-a').scrollTop");
    page.evaluate("document.querySelector('drafthouse-diff').scrollToLocation('§NonexistentHeading')");
    var after = page.evaluate(
        "document.querySelector('drafthouse-diff').shadowRoot.querySelector('#body-a').scrollTop");
    assertEquals(before, after, "no-match should not scroll");
}
```

- [ ] **Step 2: Write cross-panel coordination test**

Test that clicking a point in the review tracker dispatches `point-selected` which the shell routes to the diff panel.

- [ ] **Step 3: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 4: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java
git commit -m "test: scrollToLocation and cross-panel point-selected coordination

Numeric reference (§2), text reference (§Architecture), no-match no-op.
Cross-panel: point-selected from review tracker routes to diff panel's
scrollToLocation via the workspace shell.

Refs #51"
```

---

### Task 10: Final cleanup and push

**Files:**
- Modify: `ARC42STORIES.MD` (update UI architecture section if applicable)

- [ ] **Step 1: Run the full test suite one final time**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 2: Manual smoke test**

Open `http://localhost:9001/?a=sample-a.md&b=sample-b.md` in a browser. Verify:
- Diff panel renders correctly in left slot
- Minimap works
- All diff navigation works (sync, swap, n/p keys, arrow buttons)
- Panel toggle buttons show/hide debate + review tracker panels
- Dividers are draggable

- [ ] **Step 3: Push branch**

```
git push -u origin issue-51-workspace-ui-redesign
```

- [ ] **Step 4: Commit any ARC42STORIES.MD updates**

If the architecture section needs updating to reflect the Web Component panel model, update `ARC42STORIES.MD` §9.4 with a new layer entry.

```
git add ARC42STORIES.MD
git commit -m "docs: update ARC42STORIES — Web Component panel architecture

Refs #51"
git push
```
