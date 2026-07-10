import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface DebateStreamEntry {
  entryType: string;
  content: string;
  round: number;
  agentRole: string;
  pointId?: string;
  location?: string;
}

interface DerivedPoint {
  pointId: string;
  status: string;
  summary: string;
  location: string | undefined;
  round: number;
  raiseRound: number | null;
  fixRound: number | null;
  verifyRound: number | null;
  trail: string;
  isQualifyActive: boolean;
}

const ENTRY_TO_STATUS: Record<string, string> = {
  RAISE: 'OPEN',
  AGREE: 'AGREED',
  COUNTER: 'ACTIVE',
  DISPUTE: 'DISPUTED',
  QUALIFY: 'ACTIVE',
  FLAG_HUMAN: 'PENDING_HUMAN',
  DECLINED: 'DECLINED',
  VERIFIED: 'VERIFIED',
  DEFERRED: 'DEFERRED',
};

const STATUS_ORDER: Record<string, number> = {
  OPEN: 0,
  PENDING_HUMAN: 1,
  ACTIVE: 2,
  DISPUTED: 3,
  AGREED: 4,
  DECLINED: 5,
  VERIFIED: 6,
  DEFERRED: 7,
};

const STATUS_ICON: Record<string, string> = {
  OPEN: '○',
  ACTIVE: '⟳',
  AGREED: '✓',
  PENDING_HUMAN: '⚑',
  DECLINED: '✓',
  DISPUTED: '✕',
  VERIFIED: '✓✓',
  DEFERRED: '⏸',
};

const AGENT_SHORT: Record<string, string> = { REV: 'REV', IMP: 'IMP', FAC: 'FAC' };
const ACTION_SHORT: Record<string, string> = {
  RAISE: 'raised', AGREE: 'agreed', COUNTER: 'countered', DISPUTE: 'disputed',
  QUALIFY: 'qualified', FLAG_HUMAN: 'flagged', DECLINED: 'declined',
};

const RESOLVED_STATUSES = new Set(['AGREED', 'DECLINED', 'VERIFIED', 'DEFERRED']);

@customElement('review-tracker')
export class ReviewTracker extends LitElement {
  @state() private _entries: DebateStreamEntry[] = [];
  @state() private _hideResolved = false;
  @state() private _selectedPointId: string | null = null;

  private _configured = false;
  private _cleanups: (() => void)[] = [];

  configure(props: Record<string, unknown>): void {
    this._configured = true;
    if (props.debateSessionId !== undefined) {
      this._entries = [];
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<DebateStreamEntry[]>(document, 'debate-entries', (payload) => {
        this._entries = [...this._entries, ...payload];
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._entries = [];
      }),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private _derivePoints(): DerivedPoint[] {
    const statusEntries = this._entries.filter(e =>
      e.pointId && ENTRY_TO_STATUS[e.entryType],
    );

    const byPointId = new Map<string, DebateStreamEntry[]>();
    for (const entry of statusEntries) {
      if (!byPointId.has(entry.pointId!)) {
        byPointId.set(entry.pointId!, []);
      }
      byPointId.get(entry.pointId!)!.push(entry);
    }

    const points: DerivedPoint[] = [];
    for (const [pointId, entries] of byPointId) {
      const lastEntry = entries[entries.length - 1]!;
      const status = ENTRY_TO_STATUS[lastEntry.entryType]!;
      const raiseEntry = entries.find(e => e.entryType === 'RAISE');

      const summary = raiseEntry
        ? raiseEntry.content.split('\n')[0]!.substring(0, 120)
        : lastEntry.content.split('\n')[0]!.substring(0, 120);

      const trail = this._buildAgentTrail(entries);
      const isQualifyActive = lastEntry.entryType === 'QUALIFY';

      const fixEntry = entries.find(e => e.entryType === 'QUALIFY' || e.entryType === 'COUNTER');
      const verifyEntry = entries.find(e => e.entryType === 'VERIFIED' || e.entryType === 'AGREE');

      points.push({
        pointId,
        status,
        summary,
        location: raiseEntry?.location || lastEntry.location,
        round: lastEntry.round,
        raiseRound: raiseEntry?.round ?? null,
        fixRound: fixEntry?.round ?? null,
        verifyRound: verifyEntry?.round ?? null,
        trail,
        isQualifyActive,
      });
    }

    points.sort((a, b) => (STATUS_ORDER[a.status] ?? 99) - (STATUS_ORDER[b.status] ?? 99));
    return points;
  }

  private _buildAgentTrail(entries: DebateStreamEntry[]): string {
    const segments: string[] = [];
    let currentRound: number | null = null;

    for (const entry of entries) {
      if (entry.round !== currentRound) {
        if (currentRound !== null) {
          segments.push(`round ${entry.round}`);
        }
        currentRound = entry.round;
      }
      const agent = AGENT_SHORT[entry.agentRole] || entry.agentRole;
      const action = ACTION_SHORT[entry.entryType] || entry.entryType;
      segments.push(`${agent} ${action}`);
    }

    return segments.join(' → ');
  }

  private _onPointClick(point: DerivedPoint): void {
    const wasSelected = this._selectedPointId === point.pointId;
    this._selectedPointId = wasSelected ? null : point.pointId;

    const eventName = wasSelected ? 'point-deselected' : 'point-selected';
    this.dispatchEvent(new CustomEvent(eventName, {
      bubbles: true,
      detail: {
        pointId: point.pointId,
        round: point.round,
        raiseRound: point.raiseRound,
        fixRound: point.fixRound,
        verifyRound: point.verifyRound,
        location: point.location,
      },
    }));
  }

  private _onFilterChange(e: Event): void {
    this._hideResolved = (e.target as HTMLInputElement).checked;
  }

  static override styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
    }

    .tracker-container {
      flex: 1;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
    }

    .placeholder {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--muted);
      font-size: 13px;
      text-align: center;
      padding: 40px;
    }

    .progress-bar-container {
      padding: 12px 16px;
      background: var(--chrome);
      border-bottom: 1px solid var(--border);
      flex-shrink: 0;
    }

    .progress-label {
      font-size: 11px;
      color: var(--muted);
      margin-bottom: 6px;
      font-weight: 600;
    }

    .progress-bar {
      height: 8px;
      background: var(--border-light);
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: var(--approve);
      transition: width 0.3s;
    }

    .filter-bar {
      padding: 8px 16px;
      background: var(--bg);
      border-bottom: 1px solid var(--border-light);
      flex-shrink: 0;
    }

    .filter-toggle {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      color: var(--sepia);
      cursor: pointer;
      user-select: none;
    }

    .filter-toggle input[type="checkbox"] { cursor: pointer; }

    .points-list {
      flex: 1;
      padding: 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .point-item {
      padding: 10px 12px;
      border: 1px solid var(--border-light);
      border-radius: 3px;
      background: white;
      cursor: pointer;
      transition: all 0.15s;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .point-item:hover:not(.selected) {
      border-color: var(--accent);
      box-shadow: 0 1px 3px rgba(0,0,0,0.05);
    }

    .point-header {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .point-icon {
      font-size: 14px;
      width: 16px;
      text-align: center;
      flex-shrink: 0;
    }

    .point-summary {
      flex: 1;
      font-size: 13px;
      color: var(--ink);
      line-height: 1.4;
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }

    .point-location {
      font-size: 10px;
      color: var(--muted);
      font-family: 'SFMono-Regular', Consolas, monospace;
    }

    .point-trail {
      font-size: 10px;
      color: var(--muted);
      margin-top: 2px;
    }

    .point-item.status-open { border-left: 3px solid var(--ink); }
    .point-item.status-active { border-left: 3px solid var(--warn); }
    .point-item.status-agreed { border-left: 3px solid var(--approve); opacity: 0.6; }
    .point-item.status-agreed .point-summary { text-decoration: line-through; }
    .point-item.status-pending_human { border: 2px solid var(--warn); background: #fff8f0; }
    .point-item.status-declined { border-left: 3px solid var(--muted); opacity: 0.6; }
    .point-item.status-declined .point-summary { text-decoration: line-through; }
    .point-item.status-disputed { border-left: 3px solid var(--error); }
    .point-item.qualify-active { border-left: 3px solid var(--accent); }
    .point-item.selected {
      outline: 2px solid var(--accent, #4a6a8a);
      outline-offset: -2px;
      background: rgba(74, 106, 138, 0.08);
    }
  `;

  override render() {
    if (!this._configured) {
      return html`<div class="placeholder"><div>Waiting for debate session…</div></div>`;
    }

    const points = this._derivePoints();
    const resolvedCount = points.filter(p => RESOLVED_STATUSES.has(p.status)).length;
    const total = points.length;
    const pctWidth = total > 0 ? `${(resolvedCount / total) * 100}%` : '0%';

    const visiblePoints = this._hideResolved
      ? points.filter(p => !RESOLVED_STATUSES.has(p.status))
      : points;

    return html`
      <div class="tracker-container">
        <div class="progress-bar-container">
          <div class="progress-label">${resolvedCount} of ${total} resolved</div>
          <div class="progress-bar">
            <div class="progress-fill" style="width:${pctWidth}"></div>
          </div>
        </div>

        <div class="filter-bar">
          <label class="filter-toggle">
            <input type="checkbox" .checked=${this._hideResolved} @change=${this._onFilterChange}>
            Hide resolved
          </label>
        </div>

        <div class="points-list">
          ${visiblePoints.length === 0
            ? html`<div class="placeholder">${total === 0 ? 'No review points yet' : 'All points resolved'}</div>`
            : visiblePoints.map(point => this._renderPoint(point))}
        </div>
      </div>
    `;
  }

  private _renderPoint(point: DerivedPoint) {
    const statusClass = point.status.toLowerCase();
    const classes = [`point-item`, `status-${statusClass}`];
    if (point.isQualifyActive) classes.push('qualify-active');
    if (this._selectedPointId === point.pointId) classes.push('selected');

    return html`
      <div class=${classes.join(' ')} @click=${() => this._onPointClick(point)}>
        <div class="point-header">
          <span class="point-icon">${STATUS_ICON[point.status] || '·'}</span>
          <div class="point-summary">${point.summary}</div>
        </div>
        ${point.location ? html`<div class="point-location">${point.location}</div>` : nothing}
        <div class="point-trail">${point.trail}</div>
      </div>
    `;
  }
}
