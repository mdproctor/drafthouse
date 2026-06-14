# Workspace UI Redesign — Modular Panels, Channel View, Review Tracker

Covers issue #51. Designs DraftHouse's transition from a monolithic diff viewer to a composable workspace of Web Component panels, aligned with the `@casehub/ui` `Component` model for cross-product reuse.

**Architectural decisions made during brainstorming:**
- **Web Components, vanilla JS, zero build step** — no React, no framework. Shadow DOM encapsulation. Aligned with `@casehub/ui` direction (confirmed in the Melviz page model spec, issue melviz#8).
- **Full `@casehub/ui` `Component` model from day one** (Approach A) — panels are describable as `Component` nodes `{ type, id, props, slots, items, style, access }`. Web Components accept props via a `configure()` method that the `@casehub/ui` renderer will call. Migration to `@casehub/ui` becomes a dependency swap, not a rewrite.
- **Fixed-slot layout** (Approach A for layout) — named regions with show/hide and draggable dividers. The workspace shell is explicitly temporary — replaced by `@casehub/ui` layout primitives (`split()`, `grid()`) when they ship.
- **Review tracker derives status from the SSE stream** — no new backend state. The tracker maps `DebateStreamEntry.entryType` per `pointId` to `ReviewStatus` display values. This is a simple status map, not a replication of the server-side `DebateChannelProjection` fold.
- **Shared SSE connection via DebateEventBus** — one `EventSource` per session, multiple panel consumers. Panels subscribe to the bus, not to the endpoint directly.
- **Cross-panel coordination via DOM events** — panels emit `CustomEvent`s, the shell routes them. No direct coupling between panels.

**Platform context:**
- All CaseHub frontend apps (DraftHouse, Claudony, Devtown, AML, Clinical, Life) will adopt this Web Component model. DraftHouse is the first mover.
- `@casehub/ui` extraction is planned but not yet shipped. DraftHouse targets the `Component` model from the Melviz spec (`types.ts` — zero external deps) so that migration is additive.
- The Melviz spec (melviz#8) explicitly defers "Workspace layout primitives — `split()` (resizable panes) for IDE-like layouts" — DraftHouse's shell fills this gap temporarily.

---

## 1. Architecture Overview

```
@casehub/ui Component model (type, id, props, slots, items, style, access)
        │
        ├── Model alignment: panels are describable as Component nodes
        ├── Renderer compatibility: Web Components accept configure(props)
        │
        ▼
┌─ Workspace Shell (temporary — replaced by @casehub/ui layout primitives) ─┐
│                                                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐                │
│  │<drafthouse-  │  │<drafthouse-  │  │<drafthouse-       │                │
│  │ diff>        │  │ debate>      │  │ review-tracker>   │                │
│  │              │  │              │  │                   │                │
│  │ Two-panel    │  │ SSE debate   │  │ Review points    │                │
│  │ markdown     │  │ event feed   │  │ with status      │                │
│  │ diff viewer  │  │ + conversation│  │ lifecycle        │                │
│  │ + minimap    │  │              │  │ + strikethrough   │                │
│  └──────────────┘  └──────────────┘  └───────────────────┘                │
│           │                  │                  │                          │
│           └──────── DebateEventBus ─────────────┘                          │
│                    (shared SSE connection)                                 │
│                                                                            │
│  Topbar: panel toggles, diff nav, session controls                        │
└────────────────────────────────────────────────────────────────────────────┘
        │
        ▼
  Quarkus backend (existing — UiResource requires modification for panels/)
  - GET /api/file?path=       (diff panel)
  - GET /api/watch?path=      (diff panel SSE file watch)
  - GET /api/debate/{id}/events  (DebateEventBus SSE)
  - GET /api/debate/sessions     (session discovery)
  - MCP tools                    (debate + review session lifecycle)
```

Each panel is a Web Component with Shadow DOM encapsulation. The workspace shell manages layout and cross-panel event routing. The `DebateEventBus` manages the shared SSE connection. No new backend endpoints — all data flows exist from #50. `UiResource` requires a minor modification to serve subdirectory files (see §7).

The existing `index.html` (~770 lines) is decomposed: diff logic moves into `<drafthouse-diff>`, debate rendering into `<drafthouse-debate>`, review tracking into `<drafthouse-review-tracker>`, and the shell becomes a thin orchestrator.

---

## 2. Panel Contract

Two distinct concerns — model alignment and renderer compatibility — that the `@casehub/ui` world separates clearly.

### 2a. Model alignment

Every DraftHouse panel is describable as a `@casehub/ui` `Component` node — the declarative, JSON-serialisable descriptor:

```typescript
// From @casehub/ui (Melviz spec, melviz#8)
interface Component {
  readonly type: string;
  readonly id?: string;
  readonly props?: Readonly<Record<string, unknown>>;
  readonly style?: Readonly<Record<string, string>>;
  readonly access?: AccessControl;
  readonly slots?: Readonly<Record<string, readonly Component[]>>;
  readonly items?: readonly GridItem[];
}
```

DraftHouse panels use: `type` (custom element name), `props` (configuration), `style` (CSS overrides). `id` is relevant for layout persistence. `slots` and `items` are declared empty (leaf components). `access` is future.

A `Component` node describing the diff panel:
```javascript
{ type: 'drafthouse-diff', props: { pathA: '/path/to/a.md', pathB: '/path/to/b.md' } }
```

This is the model. It contains no lifecycle, no DOM, no runtime behaviour.

### 2b. Renderer compatibility

The runtime renders a `Component` node by creating the custom element and calling `configure(props)`:

```javascript
// What the @casehub/ui renderer will do (and what the DraftHouse shell does now)
const el = document.createElement(component.type);  // 'drafthouse-diff'
if (component.props) el.configure(component.props);
if (component.style) Object.assign(el.style, component.style);
container.appendChild(el);
```

Every DraftHouse Web Component implements:

| Method | Purpose |
|---|---|
| `constructor()` | `attachShadow({ mode: 'open' })`, initial DOM structure |
| `configure(props)` | Accept `Component.props`, (re)connect data sources |
| `connectedCallback()` | Start rendering, connect to DebateEventBus or fetch data |
| `disconnectedCallback()` | Cleanup — unsubscribe from event bus, cancel timers |

### 2c. Component type metadata

`Component` is a data model — it carries no display metadata (label, icon). The `@casehub/ui` spec has no component metadata registry. DraftHouse is the first mover; this registry is a first draft of what `@casehub/ui` needs. It is DraftHouse-local until the shared registry design ships.

```javascript
// ComponentTypeMetadata — display metadata not in Component
interface ComponentTypeMetadata {
  type: string;          // matches Component.type / custom element name
  label: string;         // display name for toolbar toggles ("Diff", "Debate")
  icon: string;          // toolbar icon
  propsSchema: object;   // describes what configure() accepts
}
```

### 2d. PanelRegistry API

Global singleton, ES module export. Both catalogues component types (declarative) and instantiates elements (factory).

```javascript
// panel-registry.js
class PanelRegistry {
  register(metadata)                     // add a component type + its class
  get(type) → ComponentTypeMetadata      // look up metadata by type string
  create(type, props) → HTMLElement      // factory: createElement + configure(props)
  types() → string[]                     // list all registered type strings
}

export const registry = new PanelRegistry();
```

Registration:
```javascript
import { registry } from './panel-registry.js';

registry.register({
  type: 'drafthouse-diff',
  component: DraftHouseDiff,           // the class (for customElements.define)
  label: 'Diff',
  icon: '⇄',
  propsSchema: {
    pathA: { type: 'string' },
    pathB: { type: 'string' },
    labelA: { type: 'string', default: 'File A' },
    labelB: { type: 'string', default: 'File B' },
  },
});
```

`register()` calls `customElements.define(metadata.type, metadata.component)` internally. `create(type, props)` calls `document.createElement(type)` then `el.configure(props)`.

### 2e. Theming across Shadow DOM

DraftHouse's existing CSS custom properties (`--bg`, `--chrome`, `--border`, `--ink`, `--sepia`, `--muted`, `--accent`, etc.) are defined on `:root`. Shadow DOM inherits CSS custom properties from the host document — panels use `var(--bg)` and get the Archive Room aesthetic without style injection.

When `@casehub/ui` ships its theming system, the property names swap (mechanical find-replace).

---

## 3. DebateEventBus — Shared SSE Connection

A shared ES module that manages the `EventSource` connection. Panels subscribe to events; only one SSE connection exists per session. Not shell-owned — the bus is independent infrastructure that survives the shell replacement.

```javascript
// debate-event-bus.js
class DebateEventBus {
  connect(debateSessionId)              // opens EventSource if not already connected
  disconnect()                          // ref-counted close (closes when last subscriber leaves)
  subscribe(callback) → unsubscribe    // add a consumer; returns cleanup function
  get connected() → boolean            // current connection state
  get sessionId() → string | null      // current session
}

export const debateEventBus = new DebateEventBus();
```

**Lifecycle:**
1. Shell calls `debateEventBus.connect(id)` when a debate session is active
2. Panels call `debateEventBus.subscribe(callback)` in `connectedCallback()`
3. Panels call the returned `unsubscribe` in `disconnectedCallback()`
4. When the last subscriber unsubscribes, the EventSource closes automatically

**Event format:** Callbacks receive parsed `DebateStreamEntry` arrays (the bus does `JSON.parse` once, all subscribers get the same objects). Heartbeats are filtered out by the bus.

**Reconnection:** The browser `EventSource` auto-reconnects on network errors. On reconnect, the bus does a full catch-up (same as initial connection) — subscribers that accumulated partial state should clear on reconnect. The bus emits a synthetic `{ type: 'reconnect' }` event to signal this.

---

## 4. Diff Panel (`<drafthouse-diff>`)

Extraction of existing diff logic from `index.html` into a Web Component. No new functionality — repackaging for modularity.

### What moves into the panel

- Two-pane markdown renderer (marked.js + highlight.js)
- LCS line diff engine (`lineDiff()`)
- Word-level diff highlighting (`wordDiff()`, `applyWordHighlights()`)
- Canvas minimap (`drawDiffMap()`)
- Scroll sync with heading anchors (`buildScrollAnchors()`, `interp()`)
- Drag divider between A and B panes
- Diff navigation logic (next/prev chunk, counter)
- File watch SSE (`EventSource` for `/api/watch`)
- Drop zones and file selection

### Props

```javascript
{
  pathA: string | null,
  pathB: string | null,
  labelA: string,       // default: filename from pathA
  labelB: string,       // default: filename from pathB
}
```

### Public API (called by the shell topbar)

| Method | Purpose |
|---|---|
| `toggleSync()` | Enable/disable scroll sync |
| `swapPanels()` | Swap A and B content |
| `nextDiff()` | Navigate to next diff chunk |
| `prevDiff()` | Navigate to previous diff chunk |
| `getDiffSummary()` | Returns `{ modified, deleted, inserted, currentIdx, totalDiffs }` |
| `scrollToLocation(location)` | Scroll to a `§`-reference (heading match in rendered content) |

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `diff-updated` | `{ chunks, totalA, totalB }` | Fired after every diff recalculation. Other panels can consume. |
| `selection-changed` | `{ side, startLine, endLine }` | Fired on text selection. Enables selection-scoped conversations (#54, wired now, consumed later). |

### Design choice — topbar controls

The diff navigation buttons (sync, swap, prev/next, counter, summary, legend) stay in the shell topbar, calling methods on the `<drafthouse-diff>` element. The panel focuses on rendering; the shell owns the toolbar chrome. When `@casehub/ui` ships and panels can declare toolbar contributions, this can revisit. The panel's public method API is the stable interface either way.

---

## 5. Debate Panel (`<drafthouse-debate>`)

Renders the SSE debate event stream from #50. Consumes events from the `DebateEventBus` (§3), not from its own `EventSource`.

### Props

```javascript
{
  debateSessionId: string,   // UUID — the bus uses this to connect
}
```

### Data flow

1. On `connectedCallback()`, subscribes to `debateEventBus`
2. Receives `DebateStreamEntry[]` arrays via the subscription callback
3. Accumulates entries and renders as a conversation feed grouped by round
4. On reconnect event, clears accumulated state and rebuilds from the catch-up payload
5. On `disconnectedCallback()`, unsubscribes

### Visual treatment per entry type

| EntryType | Visual | Colour | Notes |
|---|---|---|---|
| `RAISE` | Bordered card, priority badge, scope tag | Neutral border | |
| `AGREE` | Compact card | Green left border | |
| `COUNTER` | Compact card | Amber left border | |
| `DISPUTE` | Compact card | Red left border | |
| `QUALIFY` | Compact card (partial agreement) | Blue left border | |
| `FLAG_HUMAN` | Distinct callout with reason | Attention styling | |
| `MEMO` | Muted, italic — reasoning notes | Grey | |
| `SUB_TASK_REQUEST` | Indented, provenance label, shows related `pointId` | Muted | |
| `SUB_TASK_FINDING` | Indented, provenance: "fresh context", shows related `pointId` | Muted | |
| `SUB_TASK_ERROR` | Indented, error styling, shows related `pointId` | Red muted | |
| `RESTART_CONTEXT` | Horizontal divider | Session branch marker | |
| `DECLINED` | Strikethrough | Grey | |

### Auto-scroll

New entries scroll into view automatically unless the user has scrolled up (reading history). A "jump to latest" affordance appears when the user is behind the latest entry.

### Session discovery

When mounted with no `debateSessionId`, the panel calls `GET /api/debate/sessions` and shows active sessions to connect to. If exactly one session is active, auto-connects.

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `point-selected` | `{ pointId, round, location }` | User clicked a review point. `location` is the `§`-reference from the point's classification. Cross-panel linking — shell routes to diff panel's `scrollToLocation()`. |

---

## 6. Review Tracker (`<drafthouse-review-tracker>`)

A structured view of review points with status lifecycle and resolution tracking. Consumes events from the same `DebateEventBus` as the debate panel — same SSE connection, different presentation.

### Props

```javascript
{
  debateSessionId: string,   // same session as debate panel
}
```

### Data flow

Same as debate panel — subscribes to `DebateEventBus`, accumulates entries, clears on reconnect. The difference is presentation: instead of a conversation feed, the tracker extracts review points and computes a status summary.

### Client-side status derivation

Status is derived from `DebateStreamEntry` fields (already parsed by the server from raw `Message` into typed fields). The client fold is a **simple status-per-pointId map**:

1. Filter entries that have a `pointId`
2. Group by `pointId`
3. For each point, take the last `entryType` and map to `ReviewStatus`

**What the client fold does:**
- Groups `DebateStreamEntry` by `pointId`
- Maps last `entryType` → `ReviewStatus` display value
- Extracts `priority`, `scope`, `location` from the RAISE entry (first entry per point)
- Computes resolved/total progress count

**What the client fold does NOT do** (stays server-side in `DebateChannelProjection`):
- META header parsing (already done by `DebateStreamEntry.from()`)
- Agent type resolution (already in `DebateStreamEntry.agentRole`)
- Thread history construction (`ReviewPoint.thread`)
- Sub-task finding aggregation (`ReviewState.subTaskFindings`)
- Memo collection (`ReviewState.memos`)
- `PointClassification` record construction
- Round-bounded projection (`RoundBoundedProjection`)

### Status mapping (aligned with ReviewStatus enum)

| Last EntryType for pointId | ReviewStatus | Icon | Visual |
|---|---|---|---|
| `RAISE` (no response) | `OPEN` | `○` | Neutral |
| `AGREE` | `AGREED` | `✓` | Green, content strikethrough |
| `COUNTER` | `ACTIVE` | `⟳` | Amber |
| `DISPUTE` | `DISPUTED` | `✕` | Red |
| `QUALIFY` | `ACTIVE` | `⟳` | Blue accent (distinguish from COUNTER) |
| `FLAG_HUMAN` | `PENDING_HUMAN` | `⚑` | Attention styling |
| `DECLINED` | `DECLINED` | `✓` | Grey, content strikethrough |

Entry types that do not affect point status (no `pointId` or infrastructure): `MEMO`, `RESTART_CONTEXT`. Sub-task entries (`SUB_TASK_REQUEST`, `SUB_TASK_FINDING`, `SUB_TASK_ERROR`) carry a `pointId` but do not change the point's `ReviewStatus` — they are informational provenance, not negotiation moves.

### Why client-side derivation (not server-side ReviewState)

Emitting `ReviewState` snapshots would require a new backend endpoint. The client fold is acceptable because it operates on `DebateStreamEntry` (already parsed — `entryType`, `agentRole`, `pointId` are typed fields), not raw `Message` content. The fold is ~30 lines of JavaScript: filter, group-by, map. It does not replicate `DebateChannelProjection`'s ~200-line fold. If the server-side fold adds new `EntryType` values that affect status, the client status map needs a corresponding entry — this is a small, explicit mapping table, not a logic duplication risk.

### Display

- Progress bar at top: `N of M resolved`
- Default sort: open points first, then in-negotiation (`ACTIVE`), then resolved (`AGREED`/`DECLINED`)
- Filter toggles: show/hide resolved, show/hide by priority
- Each point shows: status icon, pointId, summary (first line of content), agent trail (e.g., "REV raised → IMP countered → round 2"), `location` reference

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `point-selected` | `{ pointId, round, location }` | Clicking a point highlights it in debate panel and scrolls diff to its `location` via `scrollToLocation()`. |

### Cross-panel coordination

The tracker, debate panel, and diff panel all understand `pointId`. Clicking a point in any panel highlights it in the others. The event detail carries `{ pointId, round, location }` — the diff panel receives `location` directly and doesn't need debate state.

Coordination is via DOM events on the workspace shell (shared parent). The shell listens for `point-selected` on any panel and forwards it to all others. No direct coupling between panels.

---

## 7. Workspace Shell

The thin orchestrator that holds panels, manages layout, and wires cross-panel events. Explicitly temporary — replaced by `@casehub/ui` layout primitives when they ship.

### Layout

Fixed-slot layout with draggable dividers:

```
┌──────────────────────────────────────────────────────────┐
│ Topbar: [DraftHouse] [⇄ Sync] [⇄ Swap] [↑↓] │ toggles │
├─────────────────────────┬────────────────────────────────┤
│                         │                                │
│   Left slot             │   Right-top slot               │
│   (diff panel)          │   (debate panel)               │
│                         │                                │
│                         ├────────────────────────────────┤
│                         │   Right-bottom slot            │
│                         │   (review tracker)             │
│                         │                                │
├─────────────────────────┴────────────────────────────────┤
│   Bottom slot (hidden by default)                        │
│   (future: context meter, agent status, etc.)            │
└──────────────────────────────────────────────────────────┘
```

### Migration target — `@casehub/ui` Component tree

When `@casehub/ui` ships `split()`, the shell is replaced by a declarative `Component` tree:

```javascript
{ type: 'split', props: { direction: 'horizontal', sizes: [60, 40] },
  slots: {
    left: [
      { type: 'drafthouse-diff', props: { pathA: '...', pathB: '...' } }
    ],
    right: [
      { type: 'split', props: { direction: 'vertical', sizes: [60, 40] },
        slots: {
          top: [
            { type: 'drafthouse-debate', props: { debateSessionId: '...' } }
          ],
          bottom: [
            { type: 'drafthouse-review-tracker', props: { debateSessionId: '...' } }
          ],
        }
      }
    ],
  }
}
```

The temporary shell encodes this tree in DOM. The `@casehub/ui` renderer encodes it in the `Component` model. The panels are identical in both — they're custom elements that accept `configure(props)`.

### Slot rules

- Left slot always holds the diff panel — it's the workspace anchor
- Right slot splits vertically between debate panel (top) and review tracker (bottom)
- Bottom slot is hidden by default — available for future panels (#52 context meter, agent status)
- Dividers between all slots are draggable (reusing existing divider logic)
- Panel toggles in the topbar show/hide each panel; hiding both right panels collapses the right slot entirely

### Shell responsibilities

| Responsibility | Detail |
|---|---|
| Panel lifecycle | Create via `registry.create(type, props)`, insert into slots, destroy |
| DebateEventBus | Calls `debateEventBus.connect(id)` when a session is active |
| Cross-panel events | Listen for `point-selected` on any panel, forward to all others; forward `location` to diff panel's `scrollToLocation()` |
| Topbar | Diff controls call methods on `<drafthouse-diff>`; panel toggles show/hide slots |
| URL state | `?a=path&b=path&debate=sessionId` — shell reads query params and configures panels on load |
| Session discovery | See §7a below |

### What the shell does NOT do

- Panel-internal rendering (each Web Component's job)
- State management (panels manage their own state)
- Backend communication (panels connect via DebateEventBus; diff panel fetches files directly)
- SSE connection management (DebateEventBus owns this)

### 7a. Session discovery lifecycle

Three paths for the shell to discover a debate session:

**1. URL parameter (primary path):** Page loads with `?debate=sessionId`. Shell configures panels immediately. This is the MCP-driven workflow — the LLM calls `start_debate`, gets a session ID, and the user opens `http://localhost:9001/?a=...&b=...&debate=<id>`.

**2. Polling (auto-discovery):** Page loads without `?debate=`. Shell polls `GET /api/debate/sessions` every 5 seconds. When a session appears, auto-configures panels and stops polling. This handles the case where a debate starts while the page is already open.

**3. Multiple sessions:** If `GET /api/debate/sessions` returns more than one active session, the shell shows a session picker in the right slot instead of auto-connecting. User selects which session to display.

Polling is pragmatic for now. When `@casehub/ui` ships an event bus or the SSE endpoint is extended with session lifecycle events, polling is replaced.

### Shell size and migration

The shell is estimated at ~250 lines of layout management. When `@casehub/ui` ships `split()` and workspace layout primitives:
1. Replace the shell's fixed-slot DOM with the `Component` tree shown above
2. Panels don't change — they're custom elements, they slot in identically
3. Cross-panel event routing moves to `@casehub/ui`'s event mechanism (if it provides one) or stays as DOM events
4. `DebateEventBus` is independent — it stays unchanged

---

## 8. File Organisation

All UI files live at the project root (served by `UiResource` via `-Dui.dir`):

```
index.html                      → workspace shell (~250 lines)
styles.css                      → shell + shared CSS custom properties (`:root` tokens)
panels/
  panel-registry.js             → PanelRegistry class + singleton export
  debate-event-bus.js           → DebateEventBus class + singleton export
  drafthouse-diff.js            → <drafthouse-diff> Web Component
  drafthouse-debate.js          → <drafthouse-debate> Web Component
  drafthouse-review-tracker.js  → <drafthouse-review-tracker> Web Component
```

Each panel file is a self-contained ES module loaded via `<script type="module">`. No bundler. The shell `index.html` imports them:

```html
<script type="module" src="panels/panel-registry.js"></script>
<script type="module" src="panels/debate-event-bus.js"></script>
<script type="module" src="panels/drafthouse-diff.js"></script>
<script type="module" src="panels/drafthouse-debate.js"></script>
<script type="module" src="panels/drafthouse-review-tracker.js"></script>
```

### UiResource modification required

The current `UiResource` has two issues that prevent serving `panels/*.js`:

1. **`@Path("{file}")` captures only a single path segment.** A request to `/panels/drafthouse-diff.js` has two segments and won't match. Fix: change to `@Path("{path:.*}")`.

2. **Media type detection only knows `.css` → `text/css`, defaulting to `text/html`.** JavaScript files would be served with the wrong content type. Fix: add `.js` → `application/javascript` and `.mjs` → `application/javascript`.

This is a ~5-line implementation change in `UiResource.serveFile()`.

---

## 9. Architectural Context — Document Work Phases

DraftHouse supports three phases of document authoring/review. This section documents the architectural reasoning for what's in scope (Phase 3) and what's deferred (Phases 1 and 2), not the implementation of those phases.

**Phase 1 — Brainstorming.** Options explored, assumptions challenged, design converged. Currently text-only via superpowers. The debate panel can render structured conversations when driven by MCP tools, but no purpose-built brainstorming UI is in scope. Deferred to #53.

**Phase 2 — Document building.** Mostly invisible. The document builds in the background. If the agent needs input, it surfaces a question back into the conversation flow (rendered in the debate panel). No special UI.

**Phase 3 — Review.** The core DraftHouse workflow and the primary consumer of this redesign. The review tracker shows structured review points. The debate panel shows negotiation. The diff panel shows the document evolving. All three coordinate via `pointId` + `location`. The review cycle: raise → respond (agree/counter/dispute/qualify/flag) → resolve → strikethrough.

---

## 10. Testing Strategy

### Shadow DOM migration approach

All existing E2E tests use selectors that don't penetrate Shadow DOM (`#render-a`, `#body-a`, `[data-diff-chunk]`, `#topbar`, `#btn-sync`, `#btn-swap`). All panels use `attachShadow({ mode: 'open' })` — Playwright can query open Shadow DOM with the `>>>` piercing combinator.

**Migration approach:**
- Add a `shadowLocator(page, hostType, selector)` helper to `PlaywrightFixtures`:
  ```java
  public static Locator shadowLocator(Page page, String hostType, String selector) {
      return page.locator(hostType).locator(">>> " + selector);
  }
  ```
- Selectors inside the diff panel migrate: `page.locator("#render-a")` → `shadowLocator(page, "drafthouse-diff", "#render-a")`
- Topbar selectors (`#topbar`, `#btn-sync`, `#btn-swap`) remain unchanged — topbar is shell DOM (light DOM), not inside any Shadow DOM
- `PlaywrightFixtures.waitForRender()` migrates: `page.waitForSelector("[data-diff-chunk]")` → `page.locator("drafthouse-diff").locator(">>> [data-diff-chunk]").first().waitFor()`

**Affected test classes (all 9 E2E tests):**
- `HappyPathE2ETest` — `#render-a`, `#render-b` selectors
- `DiffRenderingE2ETest` — `[data-diff-chunk]`, `.diff-del`, `.diff-ins`
- `WordDiffE2ETest` — `.diff-word-a`, `.diff-word-b`
- `ScrollSyncE2ETest` — `#body-a`, `#body-b` scroll state
- `SwapPanelsE2ETest` — `#render-a`, `#render-b`, `#label-a`, `#label-b`
- `NavigationE2ETest` — `#btn-prev`, `#btn-next`, `#diff-counter`
- `DiffSummaryE2ETest` — `#diff-summary`
- `DiffLegendE2ETest` — `#diff-legend`
- `SubAgentE2ETest` — may need debate panel selectors (new)

The migration is mechanical — same selectors, wrapped in `shadowLocator()`.

### Panel contract tests

- Each panel: `customElements.get()` returns the constructor after registration
- `configure(props)` sets internal state correctly
- `connectedCallback()` / `disconnectedCallback()` lifecycle (subscribes/unsubscribes from DebateEventBus)
- Shadow DOM renders content (query `shadowRoot`)

### DebateEventBus tests

- Single EventSource per session (connect twice with same ID → one connection)
- Multiple subscribers receive the same events
- Unsubscribe removes the subscriber; last unsubscribe closes the EventSource
- Reconnect event clears subscriber state
- Heartbeats filtered out

### Diff panel

- Existing E2E tests adapted with `shadowLocator()` — same visual behaviour
- Diff navigation methods (`nextDiff()`, `prevDiff()`) work via public API
- `diff-updated` event fires with correct chunk data
- `selection-changed` event fires on text selection
- `scrollToLocation(location)` scrolls to matching heading

### Debate panel

- Receives events from DebateEventBus subscription (not own EventSource)
- `DebateStreamEntry` events render correct visual treatment per `EntryType`
- SUB_TASK entries show their related `pointId`
- Round grouping renders correctly
- Auto-scroll: scrolls on new entry unless user has scrolled up
- Session discovery: shows session list when no `debateSessionId`

### Review tracker

- Status derivation: correct `ReviewStatus` for each `EntryType` (aligned with Java `DebateChannelProjection` mappings)
- Progress bar: correct count of resolved vs total
- Sorting: open first, then ACTIVE, then AGREED/DECLINED
- Filter toggles: show/hide resolved works
- Reconnect: clears and re-derives state from catch-up payload

### Cross-panel coordination

- `point-selected` event carries `{ pointId, round, location }`
- Shell routes event from any panel to all others
- Diff panel's `scrollToLocation()` called with the `location` from the event
- Clicking a point in tracker highlights in debate panel

### Workspace shell

- Panel toggles show/hide correct slots
- Dividers resize correctly
- URL state: `?a=...&b=...&debate=...` configures all panels on load
- Session discovery: polling finds new sessions, auto-configures panels
- Session picker shown when multiple sessions active

---

## 11. Deferred Concerns

- **Brainstorming UI** (#53) — richer option exploration beyond text-only terminal
- **Selection-scoped conversations** (#54) — the `selection-changed` event is wired but no consumer exists yet
- **`@casehub/ui` migration** — swap the shell when layout primitives ship; panels unchanged
- **`@casehub/ui` ComponentTypeMetadata extraction** — DraftHouse's registry is the first draft; extract into shared package when other apps adopt
- **Claudony adoption** — Claudony migrates its dashboard to use the same Web Component panel model
- **Other CaseHub app adoption** — Devtown, AML, Clinical, Life adopt the same model
- **Context meter panel** (#52) — `<drafthouse-context-meter>` as a future panel in the bottom slot
- **Agent status panel** — shows which agents are active, their roles, and current activity
- **DnD layout** — free-form panel arrangement when `@casehub/ui` ships the visual builder
- **Panel toolbar contributions** — panels declare their own toolbar items instead of relying on the shell topbar
- **Server-side ReviewState SSE** — if client-side status derivation proves insufficient, add an endpoint that emits folded `ReviewState` snapshots
