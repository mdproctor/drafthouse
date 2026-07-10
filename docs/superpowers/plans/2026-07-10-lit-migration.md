# Lit Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** TBD (file before Task 1)
**Issue group:** Single issue — one migration, one branch

**Goal:** Migrate all 6 DraftHouse panels from vanilla Web Components to Lit,
rename to blocks-ui-compatible tag names, convert `.js` → `.ts`.

**Architecture:** Bottom-up migration. Each panel is replaced one at a time —
vanilla and Lit coexist during the migration. Wire layer (pages-data
WebSocketSource) is already in place and unchanged. `onPagesEvent()` from
`@casehubio/pages-component` replaces raw `document.addEventListener('pages-event', ...)`.

**Tech Stack:** Lit 3.x, TypeScript, esbuild, `@casehubio/pages-component`,
`@casehubio/pages-runtime`, `@casehubio/pages-ui`

## Global Constraints

- `tsconfig.json` must have `experimentalDecorators: true` and
  `useDefineForClassFields: false` for Lit decorators
- CSS custom properties retain existing names (`--chrome`, `--border`,
  `--sepia`, `--ink`, `--muted`, `--accent`, etc.) — no renaming
- `configure()` method required on every panel for pages-runtime
  compatibility
- `_cleanups: (() => void)[]` pattern for all event subscriptions
- Verification gates after each panel: `npm run typecheck`, `npm run build`,
  `mvn test -pl runtime`, manual smoke test

---

### Task 1: Build Configuration and `lit` Dependency

**Files:**
- Modify: `server/runtime/src/main/webui/package.json`
- Modify: `server/runtime/src/main/webui/tsconfig.json`

**Interfaces:**
- Produces: `lit` available as import, decorators compile

- [ ] **Step 1: Add `lit` dependency**

```bash
cd server/runtime/src/main/webui && npm install lit
```

- [ ] **Step 2: Update `tsconfig.json`**

Add `experimentalDecorators` and `useDefineForClassFields` to compilerOptions:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "declaration": false,
    "noEmit": true,
    "allowJs": true,
    "experimentalDecorators": true,
    "useDefineForClassFields": false
  },
  "include": ["src"]
}
```

- [ ] **Step 3: Verify build still works**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: both pass — no panels changed yet, just config

- [ ] **Step 4: Commit**

```bash
git add server/runtime/src/main/webui/package.json server/runtime/src/main/webui/package-lock.json server/runtime/src/main/webui/tsconfig.json
git commit -m "chore: add lit dependency and enable experimental decorators

Refs #N"
```

---

### Task 2: Migrate `context-gauge` (Panel 1 — 154 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/context-gauge.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 10 import, line 88 inline HTML)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js`

**Interfaces:**
- Consumes: `pages-event` with topics `context-usage`, `reconnected`
- Produces: `<context-gauge>` custom element with `configure(props)` method

- [ ] **Step 1: Create `context-gauge.ts`**

Write new file `server/runtime/src/main/webui/src/panels/context-gauge.ts`:

```typescript
import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface ContextUsagePayload {
  windowSizeChars?: number;
  effectivePercent: number;
  thresholdExceeded?: boolean;
  serverContributionChars?: number;
  messageCount?: number;
  agentReportedPercent?: number | null;
}

@customElement('context-gauge')
export class ContextGauge extends LitElement {
  @state() private _pct = 0;
  @state() private _visible = false;
  @state() private _thresholdExceeded = false;
  @state() private _tooltipText = '';

  private _windowSizeChars: number | null = null;
  private _cleanups: (() => void)[] = [];

  configure(_props: Record<string, unknown>): void {}

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<ContextUsagePayload>(document, 'context-usage', (payload) => {
        this._handleMeta(payload);
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._reset();
      }),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private _handleMeta(data: ContextUsagePayload): void {
    if (data.windowSizeChars != null) {
      this._windowSizeChars = data.windowSizeChars;
    }
    this._visible = true;
    this._pct = data.effectivePercent;
    this._thresholdExceeded = data.thresholdExceeded ?? false;

    const contribK = Math.round((data.serverContributionChars || 0) / 1000);
    const windowK = this._windowSizeChars ? Math.round(this._windowSizeChars / 1000) : '?';
    const agentStr = data.agentReportedPercent != null
      ? Math.round(data.agentReportedPercent) + '%'
      : '—';
    this._tooltipText = `Server contribution: ${contribK}k / ${windowK}k chars (${data.messageCount || 0} messages). Agent-reported: ${agentStr}`;
  }

  private _reset(): void {
    this._visible = false;
    this._windowSizeChars = null;
    this._pct = 0;
    this._thresholdExceeded = false;
    this._tooltipText = '';
  }

  static override styles = css`
    :host {
      display: none;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      color: var(--sepia);
    }

    :host([visible]) {
      display: flex;
    }

    .gauge-label {
      white-space: nowrap;
      font-weight: 600;
    }

    .gauge-bar {
      width: 80px;
      height: 8px;
      background: var(--border-light);
      border-radius: 2px;
      overflow: hidden;
    }

    .gauge-fill {
      height: 100%;
      border-radius: 2px;
      transition: width 0.3s ease, background-color 0.3s ease;
    }

    .fill-normal { background: var(--accent); }
    .fill-warn { background: var(--warn); }
    .fill-error { background: var(--error); }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.6; }
    }

    .threshold-exceeded .gauge-label {
      animation: pulse 2s ease-in-out infinite;
      color: var(--error);
    }
  `;

  override render() {
    const clamped = Math.min(this._pct, 100);
    const fillClass = this._pct >= 80 ? 'fill-error' :
      this._pct >= 60 ? 'fill-warn' : 'fill-normal';

    return html`
      <div class=${this._thresholdExceeded ? 'threshold-exceeded' : ''} title=${this._tooltipText}>
        <span class="gauge-label">Ctx: ${this._visible ? Math.round(this._pct) + '%' : '—'}</span>
      </div>
      <div class="gauge-bar">
        <div class="gauge-fill ${fillClass}" style="width:${clamped}%"></div>
      </div>
    `;
  }

  override updated(): void {
    this.toggleAttribute('visible', this._visible);
  }
}
```

- [ ] **Step 2: Update `index.ts` — import and inline HTML**

In `index.ts`:
- Line 10: change `import "./panels/drafthouse-context-gauge.js";` → `import "./panels/context-gauge.js";`
- Line 88: change `<drafthouse-context-gauge></drafthouse-context-gauge>` → `<context-gauge></context-gauge>`
- Line 43: change `registerPanel("context-gauge", "drafthouse-context-gauge");` → `registerPanel("context-gauge", "context-gauge");`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/context-gauge.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js
git commit -m "feat: migrate context-gauge to Lit

Vanilla → LitElement, .js → .ts, drafthouse-context-gauge → context-gauge.
Uses onPagesEvent() from pages-component, _cleanups[] pattern.

Refs #N"
```

---

### Task 3: Migrate `doc-picker` (Panel 2 — 349 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/doc-picker.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 11 import, line 68 inline HTML, line 131 querySelector)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js`

**Interfaces:**
- Consumes: `pages-event` topics `documents-changed`, `comparison-changed`, `reconnected`
- Produces: `<doc-picker>` with `session-id` attribute and `configure()` method

- [ ] **Step 1: Create `doc-picker.ts`**

Write new file `server/runtime/src/main/webui/src/panels/doc-picker.ts`:

```typescript
import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface DocEntry {
  path: string;
  label?: string;
}

interface Comparison {
  pathA: string | null;
  pathB: string | null;
}

@customElement('doc-picker')
export class DocPicker extends LitElement {
  @property({ attribute: 'session-id' }) sessionId?: string;

  @state() private _documents: DocEntry[] = [];
  @state() private _currentComparison: Comparison | null = null;
  @state() private _open = false;
  @state() private _pendingA: string | null = null;
  @state() private _pendingB: string | null = null;
  @state() private _errorMessage: string | null = null;

  private _cleanups: (() => void)[] = [];
  private _errorTimeout: ReturnType<typeof setTimeout> | null = null;

  configure(_props: Record<string, unknown>): void {}

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<{ documents: DocEntry[] }>(document, 'documents-changed', (payload) => {
        this._documents = payload.documents || [];
        if (this._pendingA && !this._documents.some(d => d.path === this._pendingA)) {
          this._pendingA = null;
        }
        if (this._pendingB && !this._documents.some(d => d.path === this._pendingB)) {
          this._pendingB = null;
        }
        if (this._documents.length === 0) this._open = false;
      }),
      onPagesEvent<{ pathA: string | null; pathB: string | null }>(document, 'comparison-changed', (payload) => {
        if (payload.pathA == null && payload.pathB == null) {
          this._currentComparison = null;
        } else {
          this._currentComparison = { pathA: payload.pathA, pathB: payload.pathB };
        }
        this._pendingA = null;
        this._pendingB = null;
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._documents = [];
        this._currentComparison = null;
        this._pendingA = null;
        this._pendingB = null;
        this._open = false;
      }),
    );

    const onOutsideClick = (e: MouseEvent) => {
      if (!this.contains(e.target as Node)) {
        this._open = false;
      }
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') this._open = false;
    };
    document.addEventListener('click', onOutsideClick);
    document.addEventListener('keydown', onEscape);
    this._cleanups.push(
      () => document.removeEventListener('click', onOutsideClick),
      () => document.removeEventListener('keydown', onEscape),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
  }

  private _toggleOpen(e: Event): void {
    e.stopPropagation();
    this._open = !this._open;
  }

  private _handleSlotClick(slot: 'a' | 'b', path: string, e: Event): void {
    e.stopPropagation();
    if (this._currentComparison) {
      if (slot === 'a') {
        if (this._currentComparison.pathA === path) return;
        this._postComparison(path, this._currentComparison.pathB);
      } else {
        if (this._currentComparison.pathB === path) return;
        this._postComparison(this._currentComparison.pathA, path);
      }
    } else {
      if (slot === 'a') {
        this._pendingA = (this._pendingA === path) ? null : path;
        if (this._pendingA && this._pendingB) {
          this._postComparison(this._pendingA, this._pendingB);
          return;
        }
      } else {
        this._pendingB = (this._pendingB === path) ? null : path;
        if (this._pendingA && this._pendingB) {
          this._postComparison(this._pendingA, this._pendingB);
          return;
        }
      }
    }
  }

  private _postComparison(pathA: string | null, pathB: string | null): void {
    if (!this.sessionId) return;
    fetch(`/api/debate/${this.sessionId}/comparison`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pathA, pathB }),
    }).then(r => {
      if (!r.ok) this._showError();
    }).catch(() => this._showError());
  }

  private _showError(): void {
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
    this._errorMessage = 'Failed to update comparison';
    this._errorTimeout = setTimeout(() => {
      this._errorMessage = null;
      this._errorTimeout = null;
    }, 3000);
  }

  private _slotClass(slot: 'a' | 'b', docPath: string): string {
    const classes = ['slot-btn'];
    if (this._currentComparison) {
      const active = slot === 'a'
        ? this._currentComparison.pathA === docPath
        : this._currentComparison.pathB === docPath;
      if (active) classes.push('active');
    }
    const pending = slot === 'a' ? this._pendingA === docPath : this._pendingB === docPath;
    if (pending) classes.push('pending');
    return classes.join(' ');
  }

  static override styles = css`
    :host {
      display: none;
      position: relative;
      align-items: center;
      font-size: 12px;
    }

    :host([visible]) {
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

    .slot-btn:hover { background: var(--accent-light); }
    .slot-btn.active { background: var(--accent); color: #fff; border-color: var(--accent); }
    .slot-btn.pending { border-style: dashed; border-color: var(--accent); color: var(--accent); }

    .error-flash {
      padding: 4px 12px;
      font-size: 11px;
      color: var(--error, #c0392b);
    }
  `;

  override render() {
    const count = this._documents.length;
    return html`
      <span class="badge" @click=${this._toggleOpen}>\u{1F4C4} ${count}</span>
      ${this._open && count > 0 ? html`
        <div class="dropdown">
          <div class="header">Documents</div>
          ${this._documents.map(doc => {
            const parts = doc.path.split('/');
            const label = doc.label || parts[parts.length - 1];
            return html`
              <div class="doc-row">
                <span class="doc-label" title=${doc.path}>${label}</span>
                <button class=${this._slotClass('a', doc.path)}
                  @click=${(e: Event) => this._handleSlotClick('a', doc.path, e)}>A</button>
                <button class=${this._slotClass('b', doc.path)}
                  @click=${(e: Event) => this._handleSlotClick('b', doc.path, e)}>B</button>
              </div>
            `;
          })}
          ${this._errorMessage ? html`<div class="error-flash">${this._errorMessage}</div>` : nothing}
        </div>
      ` : nothing}
    `;
  }

  override updated(): void {
    this.toggleAttribute('visible', this._documents.length > 0);
  }
}
```

- [ ] **Step 2: Update `index.ts`**

- Line 11: change `import "./panels/drafthouse-doc-picker.js";` → `import "./panels/doc-picker.js";`
- Line 68: change `<drafthouse-doc-picker></drafthouse-doc-picker>` → `<doc-picker></doc-picker>`
- Line 131: change `document.querySelector('drafthouse-doc-picker')` → `document.querySelector('doc-picker')`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/doc-picker.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-doc-picker.js
git commit -m "feat: migrate doc-picker to Lit

Vanilla → LitElement, .js → .ts, drafthouse-doc-picker → doc-picker.
Attribute-driven sessionId via @property({ attribute: 'session-id' }).
Uses onPagesEvent(), _cleanups[] pattern.

Refs #N"
```

---

### Task 4: Migrate `document-timeline` (Panel 3 — 280 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/document-timeline.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 12 import, line 44 registerPanel, line 128 querySelector)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-timeline.js`

**Interfaces:**
- Consumes: `pages-event` topics `debate-entries`, `reconnected`; document events `point-selected`, `point-deselected`
- Produces: `<document-timeline>` custom element, dispatches `timeline-comparison-changed`

- [ ] **Step 1: Create `document-timeline.ts`**

Write new file `server/runtime/src/main/webui/src/panels/document-timeline.ts`:

```typescript
import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface Snapshot {
  label: string;
  round: number;
  commitHash: string;
  documentPath: string;
}

interface TrailHighlight {
  raiseRound: number | null;
  fixRound: number | null;
  verifyRound: number | null;
}

interface DebateStreamEntry {
  entryType: string;
  content: string;
  round: number;
  commitHash?: string;
  documentPath?: string;
}

@customElement('document-timeline')
export class DocumentTimeline extends LitElement {
  @property({ type: String }) sessionId?: string;

  @state() private _snapshots: Snapshot[] = [];
  @state() private _selectedA: number | null = null;
  @state() private _selectedB: number | null = null;
  @state() private _trailHighlight: TrailHighlight | null = null;

  private _cleanups: (() => void)[] = [];

  configure(props: Record<string, unknown>): void {
    if (props.sessionId !== undefined) this.sessionId = props.sessionId as string;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<DebateStreamEntry[]>(document, 'debate-entries', (payload) => {
        this._handleEntries(Array.isArray(payload) ? payload : [payload]);
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._snapshots = [];
        this._selectedA = null;
        this._selectedB = null;
      }),
    );

    const onPointSelected = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      this._trailHighlight = {
        raiseRound: detail.raiseRound,
        fixRound: detail.fixRound,
        verifyRound: detail.verifyRound,
      };
      if (detail.fixRound != null) {
        const fixIndex = this._snapshots.findIndex(s => s.round === detail.fixRound);
        if (fixIndex >= 0) {
          const prevIndex = Math.max(0, fixIndex - 1);
          this._selectedA = prevIndex;
          this._selectedB = fixIndex;
          this._emitComparison();
        }
      }
    };
    const onPointDeselected = () => {
      this._trailHighlight = null;
    };
    document.addEventListener('point-selected', onPointSelected);
    document.addEventListener('point-deselected', onPointDeselected);
    this._cleanups.push(
      () => document.removeEventListener('point-selected', onPointSelected),
      () => document.removeEventListener('point-deselected', onPointDeselected),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private _handleEntries(entries: DebateStreamEntry[]): void {
    let added = false;
    for (const entry of entries) {
      if (entry.entryType === 'ROUND_SNAPSHOT') {
        this._snapshots = [...this._snapshots, {
          label: entry.content,
          round: entry.round,
          commitHash: entry.commitHash || '',
          documentPath: entry.documentPath || '',
        }];
        added = true;
      }
    }
    if (added) {
      if (this._snapshots.length >= 2 && this._selectedA == null) {
        this._selectedA = this._snapshots.length - 2;
        this._selectedB = this._snapshots.length - 1;
        this._emitComparison();
      } else if (this._snapshots.length === 1 && this._selectedA == null) {
        this._selectedA = 0;
        this._selectedB = 0;
      }
    }
  }

  private _handleClick(index: number, shiftKey: boolean): void {
    if (shiftKey && this._selectedA != null) {
      this._selectedB = index;
    } else if (this._selectedA === index && this._selectedB != null) {
      this._selectedA = null;
      this._selectedB = null;
    } else {
      this._selectedA = index;
      this._selectedB = Math.min(index + 1, this._snapshots.length - 1);
      if (this._selectedA === this._selectedB && index > 0) {
        this._selectedA = index - 1;
        this._selectedB = index;
      }
    }
    if (this._selectedA != null && this._selectedB != null && this._selectedA > this._selectedB) {
      [this._selectedA, this._selectedB] = [this._selectedB, this._selectedA];
    }
    this._emitComparison();
  }

  private _emitComparison(): void {
    if (this._selectedA == null || this._selectedB == null) return;
    this.dispatchEvent(new CustomEvent('timeline-comparison-changed', {
      bubbles: true,
      composed: true,
      detail: {
        sessionId: this.sessionId,
        indexA: this._selectedA,
        indexB: this._selectedB,
        labelA: this._snapshots[this._selectedA]?.label || `Snapshot ${this._selectedA}`,
        labelB: this._snapshots[this._selectedB]?.label || `Snapshot ${this._selectedB}`,
      },
    }));
  }

  private _markerClass(index: number, snap: Snapshot): string {
    const classes = ['marker'];
    if (index === this._selectedA || index === this._selectedB) classes.push('selected');
    if (this._trailHighlight) {
      if (snap.round === this._trailHighlight.fixRound) classes.push('trail-fix');
      if (snap.round === this._trailHighlight.raiseRound) classes.push('trail-raise');
      if (snap.round === this._trailHighlight.verifyRound) classes.push('trail-verify');
    }
    return classes.join(' ');
  }

  private _connectorClass(index: number): string {
    const active = this._selectedA != null && this._selectedB != null
      && index > this._selectedA && index <= this._selectedB;
    return active ? 'connector active' : 'connector';
  }

  static override styles = css`
    :host {
      display: block;
      height: 40px;
      background: var(--chrome, #ede7d9);
      border-bottom: 1px solid var(--border, #c8baa0);
      padding: 4px 12px;
      font-family: 'SFMono-Regular', Consolas, monospace;
      font-size: 11px;
    }

    .timeline-track {
      display: flex;
      align-items: center;
      height: 100%;
      gap: 0;
      overflow-x: auto;
      overflow-y: hidden;
    }

    .marker {
      display: flex;
      flex-direction: column;
      align-items: center;
      cursor: pointer;
      padding: 2px 8px;
      border-radius: 4px;
      transition: background 0.15s;
      white-space: nowrap;
      flex-shrink: 0;
    }

    .marker:hover { background: var(--bg, #f4f0e8); }
    .marker.selected { background: var(--accent-bg, #dbe4ee); }
    .marker.trail-fix { font-weight: 700; }
    .marker.trail-raise, .marker.trail-verify { opacity: 0.6; }
    .marker.trail-raise::after, .marker.trail-verify::after {
      content: '';
      display: block;
      width: 4px;
      height: 4px;
      border-radius: 50%;
      background: var(--accent, #4a6a8a);
      margin-top: 2px;
    }

    .connector {
      flex: 1;
      min-width: 16px;
      max-width: 60px;
      height: 2px;
      background: var(--border, #c8baa0);
    }

    .connector.active {
      background: var(--accent, #4a6a8a);
      height: 3px;
    }

    .marker-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--muted, #8a7a5a);
      border: 2px solid var(--chrome, #ede7d9);
    }
    .marker.selected .marker-dot { background: var(--accent, #4a6a8a); }

    .marker-label {
      font-size: 10px;
      color: var(--muted, #8a7a5a);
      margin-top: 1px;
    }
    .marker.selected .marker-label { color: var(--ink, #2a2218); font-weight: 600; }

    .hidden { display: none; }
  `;

  override render() {
    if (this._snapshots.length === 0) {
      this.classList.add('hidden');
      return nothing;
    }
    this.classList.remove('hidden');

    return html`
      <div class="timeline-track">
        ${this._snapshots.map((snap, i) => html`
          ${i > 0 ? html`<div class=${this._connectorClass(i)}></div>` : nothing}
          <div class=${this._markerClass(i, snap)}
               @click=${(e: MouseEvent) => this._handleClick(i, e.shiftKey)}>
            <div class="marker-dot"></div>
            <div class="marker-label">${snap.label.split(' — ')[0] || `Round ${snap.round}`}</div>
          </div>
        `)}
      </div>
    `;
  }
}
```

- [ ] **Step 2: Update `index.ts`**

- Line 12: change `import "./panels/drafthouse-timeline.js";` → `import "./panels/document-timeline.js";`
- Line 44: change `registerPanel("document-timeline", "drafthouse-timeline");` → `registerPanel("document-timeline", "document-timeline");`
- Line 128: change `document.querySelector("drafthouse-timeline")` → `document.querySelector("document-timeline")`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-timeline.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/document-timeline.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-timeline.js
git commit -m "feat: migrate document-timeline to Lit

Vanilla → LitElement, .js → .ts, drafthouse-timeline → document-timeline.
Eliminates #initialized guard (PP-20260707-0cb860) — listeners now live in
connectedCallback(), not configure(). Uses onPagesEvent(), _cleanups[].

Refs #N"
```

---

### Task 5: Migrate `channel-feed` (Panel 4 — 479 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/channel-feed.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 8 import, line 41 registerPanel, line 121 querySelector)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-debate.js`

**Interfaces:**
- Consumes: `pages-event` topics `debate-entries`, `reconnected`
- Produces: `<channel-feed>` custom element, dispatches `point-selected`

- [ ] **Step 1: Create `channel-feed.ts`**

Write new file `server/runtime/src/main/webui/src/panels/channel-feed.ts`.
Port the full debate panel — all entry type styling, round grouping, agent
formatting, timestamp formatting, metadata badges, auto-scroll.

The file is 420+ lines of Lit — too large to inline here. The key structural
changes from vanilla:

- `class ChannelFeed extends LitElement` with `@customElement('channel-feed')`
- `@state() private _entries: DebateStreamEntry[] = []`
- `@state() private _debateSessionId: string | null = null`
- `configure(props)` sets `_debateSessionId` via `@state`
- `connectedCallback()` subscribes via `onPagesEvent()` + `_cleanups[]`
- `render()` returns `html` template with `.map()` for grouped entries
- Auto-scroll: check in `updated()` lifecycle, not manual DOM tracking
- `point-selected` dispatch: `@click` handler in template

All CSS moves to `static styles = css\`...\``, all DOM construction
moves to `html` template literals. No `innerHTML`.

- [ ] **Step 2: Update `index.ts`**

- Line 8: change `import "./panels/drafthouse-debate.js";` → `import "./panels/channel-feed.js";`
- Line 41: change `registerPanel("debate-feed", "drafthouse-debate");` → `registerPanel("debate-feed", "channel-feed");`
- Line 121: change `document.querySelector("drafthouse-debate")` → `document.querySelector("channel-feed")`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-debate.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/channel-feed.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-debate.js
git commit -m "feat: migrate channel-feed to Lit

Vanilla → LitElement, .js → .ts, drafthouse-debate → channel-feed.
Round-grouped conversation feed with auto-scroll. Uses onPagesEvent(),
_cleanups[] pattern.

Refs #N"
```

---

### Task 6: Migrate `review-tracker` (Panel 5 — 538 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/review-tracker.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 9 import, line 42 registerPanel, line 122 querySelector)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js`

**Interfaces:**
- Consumes: `pages-event` topics `debate-entries`, `reconnected`
- Produces: `<review-tracker>` custom element, dispatches `point-selected`, `point-deselected`

- [ ] **Step 1: Create `review-tracker.ts`**

Write new file `server/runtime/src/main/webui/src/panels/review-tracker.ts`.
Port the full review tracker — status derivation engine, progress bar, filter
toggle, point items with status-specific styling, agent trail.

The file is 480+ lines of Lit. Key structural changes:

- `class ReviewTracker extends LitElement` with `@customElement('review-tracker')`
- Status constants (`ENTRY_TO_STATUS`, `STATUS_ORDER`, `STATUS_ICON`) stay as
  module-level `const` objects — not class members
- `@state() private _entries: DebateStreamEntry[] = []`
- `@state() private _hideResolved = false`
- `@state() private _selectedPointId: string | null = null`
- `@state() private _debateSessionId: string | null = null`
- `configure(props)` sets `_debateSessionId`
- `_derivePoints()` stays as a pure computation method — called in `render()`
- `render()` returns `html` template with `.map()` for sorted points
- Click handler toggles selection and dispatches `point-selected`/`point-deselected`

All CSS moves to `static styles = css\`...\``. All DOM construction
moves to `html` template literals.

- [ ] **Step 2: Update `index.ts`**

- Line 9: change `import "./panels/drafthouse-review-tracker.js";` → `import "./panels/review-tracker.js";`
- Line 42: change `registerPanel("review-tracker", "drafthouse-review-tracker");` → `registerPanel("review-tracker", "review-tracker");`
- Line 122: change `document.querySelector("drafthouse-review-tracker")` → `document.querySelector("review-tracker")`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/review-tracker.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js
git commit -m "feat: migrate review-tracker to Lit

Vanilla → LitElement, .js → .ts, drafthouse-review-tracker → review-tracker.
Status derivation engine, progress bar, sorted display, point selection.
Uses onPagesEvent(), _cleanups[] pattern.

Refs #N"
```

---

### Task 7: Migrate `document-diff` (Panel 6 — 1180 lines)

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/document-diff.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts` (line 7 import, line 40 registerPanel, lines 27/31/123/175/194 querySelector)
- Delete: `server/runtime/src/main/webui/src/panels/drafthouse-diff.js`

**Interfaces:**
- Consumes: `pages-event` topic `file-changed`; document event `timeline-comparison-changed`
- Produces: `<document-diff>` custom element with public method API (see spec §document-diff Public Method API)
- Dispatches: `selection-changed`, `diff-updated`

- [ ] **Step 1: Create `document-diff.ts`**

Write new file `server/runtime/src/main/webui/src/panels/document-diff.ts`.
This is the most complex panel — 1100+ lines.

Key structural changes:

- `class DocumentDiff extends LitElement` with `@customElement('document-diff')`
- `@property()` for: `apiPort`, `labelA`, `labelB`, `pathA`, `pathB`
- `@state()` for: `_syncEnabled`, `_viewMode`, `_currentDiffIdx`, `_chunks`
- `@query('#diff-map')` for canvas reference
- `this.renderRoot.querySelector(\`#render-\${side}\`)` for dynamic ID queries
- `configure(props)` sets `@property` values, stashes paths if not yet connected
- Public methods remain: `loadFile()`, `loadContent()`, `toggleSync()`, `nextDiff()`,
  `prevDiff()`, `swapPanels()`, `getDiffSummary()`, `scrollToLocation()`,
  `highlightSection()`, `clearHighlight()`, `selectFile()`, `setViewMode()`,
  `currentPath()`, `viewMode` getter, `syncEnabled` getter
- Canvas minimap rendering in `_drawDiffMap()` — called after render via `updated()`
- LCS diff algorithm stays as pure functions
- Word-level highlights stay as pure functions
- Scroll sync stays as pure functions
- Drag-to-resize attaches in `firstUpdated()`
- Drop zones attach in `firstUpdated()`
- AbortController lifecycle: abort on re-fetch, abort on disconnect
- `marked` import stays

All CSS moves to `static styles = css\`...\``. `_$(id)` helper becomes
`@query` or `this.renderRoot.querySelector()`.

`DiffSummary` interface:
```typescript
export interface DiffSummary {
  modified: number;
  deleted: number;
  inserted: number;
  currentIdx: number;
  totalDiffs: number;
}
```

- [ ] **Step 2: Update `index.ts`**

- Line 7: change `import "./panels/drafthouse-diff.js";` → `import "./panels/document-diff.js";`
- Line 40: change `registerPanel("diff-viewer", "drafthouse-diff");` → `registerPanel("diff-viewer", "document-diff");`
- Lines 27, 31: change `document.querySelector("drafthouse-diff")` → `document.querySelector("document-diff")`
- Line 123: change `document.querySelector("drafthouse-diff")` → `document.querySelector("document-diff")`
- Line 175: change `document.querySelector("drafthouse-diff")` → `document.querySelector("document-diff")`
- Line 194: change `const diffEl = document.querySelector("drafthouse-diff")` → `const diffEl = document.querySelector("document-diff")`

- [ ] **Step 3: Delete old file**

```bash
rm server/runtime/src/main/webui/src/panels/drafthouse-diff.js
```

- [ ] **Step 4: Verify**

Run: `cd server/runtime/src/main/webui && npm run typecheck && npm run build`
Expected: PASS

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All E2E tests pass — this is the critical gate. The diff panel is
the most feature-rich panel and the E2E suite exercises it heavily.

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/webui/src/panels/document-diff.ts server/runtime/src/main/webui/src/index.ts
git rm server/runtime/src/main/webui/src/panels/drafthouse-diff.js
git commit -m "feat: migrate document-diff to Lit

Vanilla → LitElement, .js → .ts, drafthouse-diff → document-diff.
Canvas minimap via @query, LCS diff, word highlights, scroll sync,
drag-resize in firstUpdated(). Public method API preserved.
Uses onPagesEvent(), _cleanups[] pattern, local fetch with AbortController.

Refs #N"
```

---

### Task 8: Protocol Retirement and CLAUDE.md Update

**Files:**
- Modify: `docs/protocols/panel-configure-idempotency.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: completion of Tasks 2–7

- [ ] **Step 1: Retire protocol PP-20260707-0cb860**

Add retirement note to `docs/protocols/panel-configure-idempotency.md`:

Add after the frontmatter, before the body paragraph:

```markdown
**Retired:** Lit migration (2026-07-10) eliminates this class of bug. Listeners
now live in `connectedCallback()` / `disconnectedCallback()`, not `configure()`.
`configure()` only sets `@property` values — idempotent by nature. See spec
`docs/superpowers/specs/2026-07-09-lit-migration-design.md`.
```

- [ ] **Step 2: Update CLAUDE.md**

Update the Key Directories table and Architecture section:
- Panel paths: `.js` → `.ts`, old names → new names
- Tag names in Architecture diagram: `<drafthouse-*>` → new bare names
- Note Lit as the component framework under Architectural Direction

- [ ] **Step 3: Commit**

```bash
git add docs/protocols/panel-configure-idempotency.md CLAUDE.md
git commit -m "docs: retire configure idempotency protocol, update CLAUDE.md

PP-20260707-0cb860 retired — Lit migration eliminates the #initialized
guard pattern. CLAUDE.md updated with new panel names and Lit framework.

Refs #N"
```
