# Workspace UI Redesign — Modular Panels, Channel View, Review Tracker

Covers issue #51. Designs DraftHouse's transition from a monolithic diff viewer to a composable workspace of Web Component panels, aligned with the `@casehub/ui` `Component` contract for cross-product reuse.

**Architectural decisions made during brainstorming:**
- **Web Components, vanilla JS, zero build step** — no React, no framework. Shadow DOM encapsulation. Aligned with `@casehub/ui` direction (confirmed in the Melviz page model spec, issue melviz#8).
- **Full `@casehub/ui` `Component` shape from day one** (Approach A) — `{ type, props, slots, style, access }`. Migration to `@casehub/ui` becomes a dependency swap, not a rewrite.
- **Fixed-slot layout** (Approach A for layout) — named regions with show/hide and draggable dividers. The workspace shell is explicitly temporary — replaced by `@casehub/ui` layout primitives (`split()`, `grid()`) when they ship.
- **Review tracker derives status from the SSE stream** — no new backend state. The tracker folds the debate event stream and computes point status from EntryType sequences per pointId.
- **Cross-panel coordination via DOM events** — panels emit `CustomEvent`s, the shell routes them. No direct coupling between panels.

**Platform context:**
- All CaseHub frontend apps (DraftHouse, Claudony, Devtown, AML, Clinical, Life) will adopt this Web Component model. DraftHouse is the first mover.
- `@casehub/ui` extraction is planned but not yet shipped. DraftHouse targets the `Component` interface from the Melviz spec (`types.ts` — zero external deps) so that migration is additive.
- The Melviz spec (melviz#8) explicitly defers "Workspace layout primitives — `split()` (resizable panes) for IDE-like layouts" — DraftHouse's shell fills this gap temporarily.

---

## 1. Architecture Overview

```
@casehub/ui Component contract (type, props, slots, style, access)
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
│                                                                            │
│  Topbar: panel toggles, diff nav, session controls                        │
└────────────────────────────────────────────────────────────────────────────┘
        │
        ▼
  Quarkus backend (existing — no new endpoints)
  - GET /api/file?path=       (diff panel)
  - GET /api/watch?path=      (diff panel SSE file watch)
  - GET /api/debate/{id}/events  (debate + review tracker SSE)
  - GET /api/debate/sessions     (session discovery)
  - MCP tools                    (debate + review session lifecycle)
```

Each panel is a Web Component with Shadow DOM encapsulation. The workspace shell manages layout and cross-panel event routing. No new backend endpoints — all data flows exist from #50.

The existing `index.html` (~770 lines) is decomposed: diff logic moves into `<drafthouse-diff>`, debate rendering into `<drafthouse-debate>`, review tracking into `<drafthouse-review-tracker>`, and the shell becomes a thin orchestrator.

---

## 2. Panel Contract

Each panel is a Web Component that mirrors the `@casehub/ui` `Component` interface.

### Registration

```javascript
registry.register({
  type: 'drafthouse-diff',
  props: {
    pathA: { type: 'string' },
    pathB: { type: 'string' },
  },
  slots: {},
  label: 'Diff',
  icon: '⇄',
});
```

- `type` maps directly to `Component.type` and becomes the custom element name
- `props` maps to `Component.props` — the schema of what `configure()` accepts
- `slots` declared but empty for all three panels (leaf components)
- `label` and `icon` are shell-specific metadata for topbar toggles — not part of `Component`

### Web Component contract

Every panel implements:

| Method | Purpose |
|---|---|
| `constructor()` | `attachShadow({ mode: 'open' })`, initial DOM structure |
| `configure(props)` | Accept `Component.props`, (re)connect data sources |
| `connectedCallback()` | Start rendering, connect SSE, fetch data |
| `disconnectedCallback()` | Cleanup — close EventSource, cancel timers |

Optional: `static observedAttributes` and `attributeChangedCallback()` for HTML attribute binding.

### Theming across Shadow DOM

DraftHouse's existing CSS custom properties (`--bg`, `--chrome`, `--border`, `--ink`, `--sepia`, `--muted`, `--accent`, etc.) are defined on `:root`. Shadow DOM inherits CSS custom properties from the host document — panels use `var(--bg)` and get the Archive Room aesthetic without style injection.

When `@casehub/ui` ships its theming system, the property names swap (mechanical find-replace).

### Migration to `@casehub/ui`

When `@casehub/ui` extracts:
- The panel registry becomes `@casehub/ui`'s component type registry
- `configure(props)` stays the same — the `@casehub/ui` renderer calls it identically
- Shadow DOM theming migrates to `@casehub/ui`'s CSS custom property scheme
- The workspace shell is replaced by `@casehub/ui` layout primitives (`split()`, `grid()`)
- Panels don't change — they're custom elements that slot into any layout system

---

## 3. Diff Panel (`<drafthouse-diff>`)

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

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `diff-updated` | `{ chunks, totalA, totalB }` | Fired after every diff recalculation. Other panels can consume. |
| `selection-changed` | `{ side, startLine, endLine }` | Fired on text selection. Enables selection-scoped conversations (wired now, consumed later). |

### Design choice — topbar controls

The diff navigation buttons (sync, swap, prev/next, counter, summary, legend) stay in the shell topbar, calling methods on the `<drafthouse-diff>` element. The panel focuses on rendering; the shell owns the toolbar chrome. When `@casehub/ui` ships and panels can declare toolbar contributions, this can revisit. The panel's public method API is the stable interface either way.

---

## 4. Debate Panel (`<drafthouse-debate>`)

Renders the SSE debate event stream from #50. The `connectDebateSSE` / `disconnectDebateSSE` functions and `CustomEvent('debate-event')` dispatch already exist in `index.html` — this panel replaces them with a rendered conversation feed.

### Props

```javascript
{
  debateSessionId: string,   // UUID — connects to /api/debate/{id}/events
}
```

### Rendering

Each `DebateStreamEntry` from the SSE stream carries `entryType`, `agentRole`, `round`, `content`, `pointId`, `priority`, `scope`, `location`. The panel renders these as a conversation feed grouped by round.

### Visual treatment per entry type

| EntryType | Visual | Colour |
|---|---|---|
| `RAISE` | Bordered card, priority badge, scope tag | Neutral border |
| `AGREE` | Compact card | Green left border |
| `COUNTER` | Compact card | Amber left border |
| `DISPUTE` | Compact card | Red left border |
| `QUALIFY` | Compact card (partial agreement) | Blue left border |
| `FLAG_HUMAN` | Distinct callout with reason | Attention styling |
| `MEMO` | Muted, italic — reasoning notes | Grey |
| `SUB_TASK_REQUEST` | Indented, provenance label | Muted |
| `SUB_TASK_FINDING` | Indented, provenance: "fresh context" | Muted |
| `SUB_TASK_ERROR` | Indented, error styling | Red muted |
| `RESTART_CONTEXT` | Horizontal divider | Session branch marker |
| `DECLINED` | Strikethrough | Grey |

### Auto-scroll

New entries scroll into view automatically unless the user has scrolled up (reading history). A "jump to latest" affordance appears when the user is behind the latest entry.

### Session discovery

When mounted with no `debateSessionId`, the panel calls `GET /api/debate/sessions` and shows active sessions to connect to. If exactly one session is active, auto-connects.

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `point-selected` | `{ pointId, round }` | User clicked a review point. Cross-panel linking. |

---

## 5. Review Tracker (`<drafthouse-review-tracker>`)

A structured view of review points with status lifecycle and resolution tracking. Consumes the same SSE stream as the debate panel but presents a different view — not a conversation feed, but a tracked checklist of negotiated points.

### Props

```javascript
{
  debateSessionId: string,   // same session as debate panel — shared data, different view
}
```

### Status lifecycle

Status is **derived from the EntryType sequence per pointId**, not stored separately. The tracker folds the debate event stream and computes the current status of each point from its most recent entry type.

| Last EntryType for point | Status | Icon |
|---|---|---|
| `RAISE` (no response) | Open | `○` hollow circle |
| `AGREE` | Resolved — agreed | `✓` green, content strikethrough |
| `COUNTER` | In negotiation | `⟳` amber |
| `DISPUTE` | Contested | `✕` red |
| `QUALIFY` | Partially agreed | `⟳` blue |
| `FLAG_HUMAN` | Needs human | `⚑` attention |
| `DECLINED` | Declined | `✓` grey, content strikethrough |

### Why derived, not stored

- No new backend state or endpoints required
- The tracker and debate panel are always consistent — same SSE source of truth
- `restart_from_round` (session branching from #40) works correctly — the tracker re-derives status from the replayed stream
- Idempotent — reconnecting to SSE replays the full history and produces the same state

### Display

- Progress bar at top: `N of M resolved`
- Default sort: open points first, then in-negotiation, then resolved
- Filter toggles: show/hide resolved, show/hide by priority
- Each point shows: status icon, pointId, summary (first line of content), agent trail (e.g., "REV raised → IMP countered → round 2")

### Events emitted

| Event | Detail | Purpose |
|---|---|---|
| `point-selected` | `{ pointId, round }` | Clicking a point highlights it in debate panel and scrolls diff to its `location`. |

### Cross-panel coordination

The tracker, debate panel, and diff panel all understand `pointId`. Clicking a point in any panel highlights it in the others. Coordination is via DOM events on the workspace shell (shared parent), not direct coupling between panels.

---

## 6. Workspace Shell

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

### Slot rules

- Left slot always holds the diff panel — it's the workspace anchor
- Right slot splits vertically between debate panel (top) and review tracker (bottom)
- Bottom slot is hidden by default — available for future panels (#52 context meter, agent status)
- Dividers between all slots are draggable (reusing existing divider logic)
- Panel toggles in the topbar show/hide each panel; hiding both right panels collapses the right slot entirely

### Shell responsibilities

| Responsibility | Detail |
|---|---|
| Panel lifecycle | Create, configure, insert into slots, destroy |
| Cross-panel events | Listen for `point-selected` on any panel, forward to all others |
| Topbar | Diff controls call methods on `<drafthouse-diff>`; panel toggles show/hide slots |
| Session binding | When a debate session is active, auto-configure debate + review tracker with `debateSessionId` |
| URL state | `?a=path&b=path&debate=sessionId` — shell reads query params and configures panels on load |

### What the shell does NOT do

- Panel-internal rendering (each Web Component's job)
- State management (panels manage their own state)
- Backend communication (panels own their own data connections)

### Shell size and migration

The shell is estimated at ~200 lines of layout management. When `@casehub/ui` ships `split()` and workspace layout primitives:
1. Replace the shell's fixed-slot DOM with `@casehub/ui` layout components
2. Panels don't change — they're custom elements, they slot in identically
3. Cross-panel event routing moves to `@casehub/ui`'s event mechanism (if it provides one) or stays as DOM events

---

## 7. File Organisation

All UI files live at the project root (served by `UiResource` via `-Dui.dir`):

```
index.html                      → workspace shell (~200 lines)
styles.css                      → shell + shared CSS custom properties
panels/
  drafthouse-diff.js            → <drafthouse-diff> Web Component
  drafthouse-debate.js          → <drafthouse-debate> Web Component
  drafthouse-review-tracker.js  → <drafthouse-review-tracker> Web Component
  panel-registry.js             → PanelRegistry + registration contract
```

Each panel file is a self-contained ES module loaded via `<script type="module">`. No bundler. The shell `index.html` imports them:

```html
<script type="module" src="panels/panel-registry.js"></script>
<script type="module" src="panels/drafthouse-diff.js"></script>
<script type="module" src="panels/drafthouse-debate.js"></script>
<script type="module" src="panels/drafthouse-review-tracker.js"></script>
```

### Quarkus serving

`UiResource` already serves static files from the `ui.dir` system property. The `panels/` directory is served alongside `index.html` and `styles.css` with no configuration change.

---

## 8. Three Phases of Document Work

The workspace supports three phases of document authoring/review, each with distinct UI treatment:

### Phase 1 — Brainstorming

Options explored, assumptions challenged, design converged. Currently text-only (terminal via superpowers). The debate panel can render structured brainstorming conversations when driven by MCP tools, but no special brainstorming UI is in scope for #51.

### Phase 2 — Document building

Mostly invisible to the user. The document builds in the background. If the agent needs input, it surfaces a question back into the brainstorming/conversation flow (the debate panel renders it). No special UI for this phase — it's a natural continuation of Phase 1 when questions arise.

### Phase 3 — Review

The core DraftHouse workflow. The review tracker panel shows the structured list of review points. The debate panel shows the negotiation. The diff panel shows the document evolving. All three panels coordinate via `pointId` — clicking a point in any panel highlights it in the others.

The review cycle:
1. Reviewer raises points (appear in tracker as `○ Open`)
2. Implementor responds: agree, counter, dispute, qualify, flag-human
3. Tracker updates status per point in real time
4. Agreed points show as `✓` with strikethrough — implemented in the document
5. Cycles continue until all points resolved
6. Progress bar shows `N of M resolved`

---

## 9. Testing Strategy

### Panel contract
- Each panel: `customElements.get()` returns the constructor after registration
- `configure(props)` sets internal state correctly
- `connectedCallback()` / `disconnectedCallback()` lifecycle works (SSE connects/disconnects)
- Shadow DOM renders content (query `shadowRoot`)

### Diff panel
- Existing E2E tests adapted — same visual behaviour, different DOM structure (shadow DOM)
- Diff navigation methods (`nextDiff()`, `prevDiff()`) work via public API
- `diff-updated` event fires with correct chunk data
- `selection-changed` event fires on text selection

### Debate panel
- SSE connection established on `connectedCallback()`
- `DebateStreamEntry` events render correct visual treatment per `EntryType`
- Round grouping renders correctly
- Auto-scroll behaviour: scrolls on new entry unless user has scrolled up
- Session discovery: shows session list when no `debateSessionId`

### Review tracker
- Status derivation: correct status for each `EntryType` sequence
- Progress bar: correct count of resolved vs total
- Sorting: open first, then in-negotiation, then resolved
- Filter toggles: show/hide resolved works
- `restart_from_round`: replayed stream produces correct derived state

### Cross-panel coordination
- `point-selected` event from any panel routes to all others
- Clicking a point in tracker highlights in debate panel
- Clicking a point with `location` scrolls diff panel to that section

### Workspace shell
- Panel toggles show/hide correct slots
- Dividers resize correctly
- URL state: `?a=...&b=...&debate=...` configures all panels on load
- Session binding: debate session auto-configures both debate and review tracker

---

## 10. Deferred Concerns

- **Brainstorming UI** — richer option exploration beyond text-only terminal (visual option comparison, first-principles deep-dive rendering)
- **Selection-scoped conversations** — the `selection-changed` event is wired but no consumer exists yet
- **`@casehub/ui` migration** — swap the shell when layout primitives ship; panels unchanged
- **Claudony adoption** — Claudony migrates its dashboard to use the same Web Component panel model
- **Other CaseHub app adoption** — Devtown, AML, Clinical, Life adopt the same model
- **Context meter panel** (#52) — `<drafthouse-context-meter>` as a future panel in the bottom slot
- **Agent status panel** — shows which agents are active, their roles, and current activity
- **DnD layout** — free-form panel arrangement when `@casehub/ui` ships the visual builder
- **Panel toolbar contributions** — panels declare their own toolbar items instead of relying on the shell topbar
