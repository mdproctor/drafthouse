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
      onPagesEvent<DebateStreamEntry | DebateStreamEntry[]>(document, 'debate-entries', (payload) => {
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
    const newSnapshots = [...this._snapshots];
    for (const entry of entries) {
      if (entry.entryType === 'ROUND_SNAPSHOT') {
        newSnapshots.push({
          label: entry.content,
          round: entry.round,
          commitHash: entry.commitHash || '',
          documentPath: entry.documentPath || '',
        });
        added = true;
      }
    }
    if (added) {
      this._snapshots = newSnapshots;
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
