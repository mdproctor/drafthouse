# Lit Migration — Vanilla Web Components to Lit + blocks-ui Compatibility

**Date:** 2026-07-09
**Status:** Approved
**Issue:** TBD (file before implementation)

## Goal

Migrate all 6 DraftHouse panels from vanilla Web Components (`.js`, `attachShadow`,
`adoptedStyleSheets`) to Lit (`.ts`, `LitElement`, decorators, reactive properties).
Simultaneously rename and restructure for blocks-ui compatibility — bare descriptive
tag names, TypeScript, blocks-ui-compatible `configure()` pattern.

Components stay in DraftHouse for now. Promotion to blocks-ui happens separately
once blocks-ui is stable.

## What Changes

- 6 panel files: `.js` → `.ts`, vanilla → Lit
- Tag names: `drafthouse-*` → bare descriptive names (blocks-ui convention)
- `index.ts`: updated imports, tag references, `registerPanel()` calls, inline HTML
  templates, querySelector selectors (including Electron IPC bridge)
- `package.json`: `lit` added as dependency
- `tsconfig.json`: `experimentalDecorators: true`, `useDefineForClassFields: false`
- Protocol PP-20260707-0cb860: retired (listener lifecycle moved to
  `connectedCallback()`/`disconnectedCallback()` — no manual guard needed)

## What Does Not Change

- Wire layer (WebSocket, pages-event topics, event payloads)
- Server endpoints (REST, WebSocket, MCP tools)
- Layout DSL in `index.ts` (`rows()`, `split()`, `hostPanel()`)
- CustomEvent names and detail shapes dispatched between panels
- Canvas minimap, LCS diff algorithm, scroll sync logic (pure logic, unchanged)
- E2E test selectors (they use internal element IDs and `data-*` attributes that
  pierce shadow DOM — no tag-name selectors exist in the test suite)

## Build Configuration

### tsconfig.json

Add to `server/runtime/src/main/webui/tsconfig.json` `compilerOptions`:

```json
"experimentalDecorators": true,
"useDefineForClassFields": false
```

These are required for Lit's `@customElement`, `@property`, `@state`, and `@query`
decorators. Matches blocks-ui-core's configuration. esbuild 0.25.0 reads these
settings from tsconfig.json and transforms experimental decorators natively — no
plugin or pre-compilation step needed.

### Verification Gates

Each panel migration must pass before proceeding to the next:

1. `npm run typecheck` (`tsc --noEmit`) — type-checks all `.ts` files
2. `npm run build` (esbuild) — bundle compiles without errors
3. E2E test suite (`mvn test -pl server/runtime`) — behavioral regression check
4. Manual smoke test — panel renders and responds to events in browser

## Component Architecture

Each migrated panel follows the blocks-ui pattern:

```typescript
import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

@customElement('tag-name')
export class TagName extends LitElement {
  @property({ type: String }) sessionId?: string;   // props set via configure()
  @state() private _data: Item[] = [];               // internal reactive state

  private _cleanups: (() => void)[] = [];

  configure(props: Record<string, unknown>): void {
    if (props.sessionId !== undefined) this.sessionId = props.sessionId as string;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // pages-event subscriptions — one per topic, each returns an unsubscribe fn
    this._cleanups.push(
      onPagesEvent(document, 'debate-entries', (payload) => { /* ... */ }),
      onPagesEvent(document, 'reconnected', () => { /* ... */ }),
    );
    // raw DOM event subscriptions — wrap in the same cleanup pattern
    const onPointSelected = (e: Event) => { /* ... */ };
    document.addEventListener('point-selected', onPointSelected);
    this._cleanups.push(() => document.removeEventListener('point-selected', onPointSelected));
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  static override styles = css`/* Shadow DOM styles */`;

  override render() {
    return html`<!-- declarative template -->`;
  }
}
```

### Key Patterns

1. **`configure()` stays as a method** — pages-runtime calls it (in `activation.ts`)
   before the element is connected to the DOM. Setting Lit `@property` values before
   connection is safe — Lit queues the update until `connectedCallback()`. Re-calling
   `configure()` on an already-connected element (as `connectDebateSession()` does)
   is also safe — it just reassigns properties, triggering a normal reactive update.
   No `#initialized` guard needed because listeners live in `connectedCallback()`,
   not in `configure()`.

2. **Event subscriptions and cleanup** — all subscriptions (pages-events and raw DOM
   events) are normalized into a `_cleanups: (() => void)[]` array:
   - **pages-events:** one `onPagesEvent()` call per topic (from `@casehubio/pages-component`,
     already a dependency). Each returns an unsubscribe function pushed to `_cleanups`.
     5 of 6 panels subscribe to multiple pages-event topics.
   - **Raw DOM events:** `point-selected`, `point-deselected`, `timeline-comparison-changed`
     are plain `CustomEvent`s dispatched on `document` — not pages-events. These use
     `document.addEventListener()` in `connectedCallback()` with a matching
     `removeEventListener` wrapped as a `() => void` cleanup function.
   - **Teardown:** `disconnectedCallback()` iterates `_cleanups`, calls each function,
     then resets the array. One loop cleans up all event types uniformly.

3. **doc-picker uses attribute-driven property** — unlike other panels that receive
   all configuration via `configure()`, doc-picker's session ID is set via
   `setAttribute('session-id', ...)` in `index.ts`. In Lit, this maps to
   `@property({ attribute: 'session-id' }) sessionId?: string` with automatic
   attribute reflection. The empty `configure()` stub remains for interface
   compatibility with pages-runtime. No change to `index.ts`'s `setAttribute()` call.

4. **Panels that fetch** — `document-diff` uses a local fetch pattern inspired by
   `DataEndpointMixin` but limited to what the panel actually needs:
   - AbortController lifecycle (abort on re-fetch, abort on disconnect)
   - `loading`/`error` state properties
   - Fetch triggered by `loadFile()`/`loadContent()` calls, not by property changes
   
   The mixin's `willUpdate()`-driven re-fetch, microtask-deferred configure, and SSE
   subscription management are NOT needed — document-diff receives file change
   notifications via `pages-event`/WebSocket, not SSE. `doc-picker` uses plain
   `fetch()` for its single POST (mixin would be overkill).

5. **Panels that dispatch events** — same `CustomEvent` names, same `detail` shapes.
   Consumers are agnostic to whether the dispatcher is Lit or vanilla.

6. **CSS** — `static styles = css\`...\`` replaces `new CSSStyleSheet()` +
   `replaceSync()`. Same Shadow DOM encapsulation. CSS custom properties retain their
   existing names (`--chrome`, `--border`, `--sepia`, `--ink`, `--muted`, `--accent`,
   etc.) — these are defined by the host page theme, not renamed.

7. **DOM queries** — two strategies depending on usage:
   - **Static queries** to known elements (`#divider`, `#diff-map`, `#panel-a`):
     use `@query('#divider')` decorator. Lit caches the reference after first access.
   - **Dynamic queries** where the ID is computed (`render-${side}`, `body-${side}`,
     `label-${side}`): use `this.renderRoot.querySelector(\`#render-\${side}\`)`.
     `@query` cannot accept template literals.

## Migration Order (Bottom-Up)

### Panel 1: context-gauge (154 lines → ~120 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-context-gauge.js` |
| **To** | `panels/context-gauge.ts` |
| **Tag** | `<context-gauge>` |
| **Subscribes** | `context-usage`, `reconnected` |
| **Dispatches** | — |
| **Fetches** | — |
| **Props** | None (empty `configure()`) |
| **Notes** | Simple progress bar. Warm-up — validates Lit + `onPagesEvent()` pattern. |

### Panel 2: doc-picker (349 lines → ~300 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-doc-picker.js` |
| **To** | `panels/doc-picker.ts` |
| **Tag** | `<doc-picker>` |
| **Subscribes** | `documents-changed`, `comparison-changed`, `reconnected` |
| **Dispatches** | — |
| **Fetches** | `POST /api/debate/{id}/comparison` |
| **Props** | `sessionId` via `@property({ attribute: 'session-id' })` — attribute-driven, not `configure()` |
| **Notes** | Dropdown state, outside-click and Escape listeners in connected/disconnected. Adds fetch pattern. Attribute-driven integration — see §Component Architecture, pattern 3. |

### Panel 3: document-timeline (280 lines → ~250 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-timeline.js` |
| **To** | `panels/document-timeline.ts` |
| **Tag** | `<document-timeline>` |
| **Subscribes** | `debate-entries` (filters `ROUND_SNAPSHOT`), `reconnected`, `point-selected`, `point-deselected` (document events) |
| **Dispatches** | `timeline-comparison-changed` |
| **Props** | `sessionId` via `configure()` |
| **Notes** | Shift-click multi-selection, trail highlighting. Only panel with `#initialized` guard — eliminated by moving listeners from `configure()` → `connectedCallback()`. |

### Panel 4: channel-feed (479 lines → ~420 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-debate.js` |
| **To** | `panels/channel-feed.ts` |
| **Tag** | `<channel-feed>` |
| **Subscribes** | `debate-entries`, `reconnected` |
| **Dispatches** | `point-selected` |
| **Props** | Via `configure()` |
| **Notes** | Entries grouped by round. Auto-scroll logic in `updated()`. Potential blocks-ui promotion candidate — `blocks-ui/components/channel-activity` is currently a registration stub with no implementation. Promotion is future work. |

### Panel 5: review-tracker (538 lines → ~480 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-review-tracker.js` |
| **To** | `panels/review-tracker.ts` |
| **Tag** | `<review-tracker>` |
| **Subscribes** | `debate-entries`, `reconnected` |
| **Dispatches** | `point-selected`, `point-deselected` |
| **Props** | Via `configure()` |
| **Notes** | Status derivation engine (entries → ReviewStatus per pointId). `raiseRound`/`fixRound`/`verifyRound` enrichment. |

### Panel 6: document-diff (1180 lines → ~1100 lines)

| | |
|---|---|
| **From** | `panels/drafthouse-diff.js` |
| **To** | `panels/document-diff.ts` |
| **Tag** | `<document-diff>` |
| **Subscribes** | `file-changed`, `timeline-comparison-changed` (document event) |
| **Dispatches** | `selection-changed`, `diff-updated` |
| **Fetches** | `GET /api/file?path=`, `GET /api/debate/{id}/snapshot/{index}` |
| **Props** | `apiPort`, `labelA`, `labelB`, `pathA`, `pathB` via `configure()` |
| **Notes** | Most complex panel. Canvas minimap via `@query('#diff-map')`. LCS diff, word-level highlights, scroll sync — pure logic unchanged. Drag-to-resize and drop zones attach in `firstUpdated()`. `marked` dependency stays. Local fetch pattern with AbortController (not full DataEndpointMixin). |

### document-diff Public Method API

`document-diff` exposes imperative public methods called from `index.ts` event handlers,
topbar wiring, keyboard shortcuts, and the Electron IPC bridge. These remain as public
methods — they are commands that cannot be modeled as property changes:

```typescript
interface DiffSummary {
  modified: number;
  deleted: number;
  inserted: number;
  currentIdx: number;   // -1 when no diff is focused
  totalDiffs: number;
}

interface DocumentDiffPanel extends HTMLElement {
  configure(props: Record<string, unknown>): void;
  loadFile(panel: 'a' | 'b', path: string): Promise<void>;
  loadContent(panel: 'a' | 'b', content: string, label?: string): void;
  toggleSync(): boolean;
  nextDiff(): DiffSummary;
  prevDiff(): DiffSummary;
  swapPanels(): void;
  getDiffSummary(): DiffSummary;
  scrollToLocation(location: string): void;
  highlightSection(location: string): void;
  clearHighlight(): void;
  selectFile(panel: 'a' | 'b'): Promise<void>;
  setViewMode(mode: 'split' | 'unified'): void;
  currentPath(slot: 'a' | 'b'): string | null;
  readonly viewMode: 'split' | 'unified';
  readonly syncEnabled: boolean;
}
```

`index.ts` casts querySelector results to this interface for type-safe method calls.

## index.ts Changes

All tag-name references in `index.ts` that require updating:

1. **Import paths** (lines 7–12): `./panels/drafthouse-*.js` → `./panels/<new-name>.js`
2. **`registerPanel()` calls** (lines 40–44): second argument tag names updated
3. **Inline HTML templates** (lines 68, 88): `<drafthouse-doc-picker>` and
   `<drafthouse-context-gauge>` in `html()` template strings — these are not
   registered via `hostPanel()` but embedded directly in HTML
4. **Electron IPC bridge** (lines 27, 31): `querySelector("drafthouse-diff")` → `querySelector("document-diff")`
5. **`connectDebateSession()`** (lines 121–131): 5 querySelector calls —
   `drafthouse-debate`, `drafthouse-review-tracker`, `drafthouse-diff`,
   `drafthouse-timeline`, `drafthouse-doc-picker`
6. **`pages-event` handler** (line 175): `querySelector("drafthouse-diff")` → `querySelector("document-diff")`
7. **Topbar wiring** (line 194): `querySelector("drafthouse-diff")` → `querySelector("document-diff")`

No structural changes to layout DSL.

## Coexistence During Migration

Vanilla and Lit custom elements coexist safely in the same bundle. Both use
`customElements.define()` (Lit's `@customElement` decorator is sugar for the same
call). esbuild bundles all panels into a single `app.js` regardless of whether they
are vanilla or Lit. If the migration stalls mid-sequence, the app continues to work
with a mix of migrated and unmigrated panels.

The bottom-up migration order is chosen so that simpler panels validate the patterns
before tackling complex ones, not because of runtime dependencies between panels.

## Dependencies

**Added:** `lit` (LitElement, html, css, decorators)

**Not added yet:** `@casehubio/blocks-ui-core` — the fetch pattern in `document-diff`
is implemented locally, replicating only the AbortController lifecycle and
loading/error state management from `DataEndpointMixin`. The full mixin (with
`willUpdate()`-driven re-fetch, microtask-deferred configure, and SSE management)
is not needed. Actual mixin import happens at blocks-ui promotion time.

## Protocol Retirement

PP-20260707-0cb860 (`panel configure() must be idempotent — guard with #initialized
flag`) is retired. The root cause was event listeners registered inside `configure()`,
which runs multiple times (pages-runtime init + `connectDebateSession()`). The Lit
migration eliminates this by moving all listener registration to
`connectedCallback()` / `disconnectedCallback()`:

- Listeners register exactly once on connection, unregister on disconnection
- `configure()` only sets `@property` values — idempotent by nature
- Re-calling `configure()` on a connected element triggers a reactive update, not
  duplicate listener registration

The protocol file receives a retirement note.

## CLAUDE.md Updates

After migration:
- Panel paths in Key Directories table: `.js` → `.ts`, old names → new names
- Tag names in Architecture section: `<drafthouse-*>` → new names
- Note Lit as the component framework
