// panels/drafthouse-review-tracker.js
// <drafthouse-review-tracker> — Status-derived checklist Web Component
// Subscribes to pages-event, derives ReviewStatus per pointId from DebateStreamEntry sequence.
// Progress bar, sorted display (open → active → resolved), show/hide resolved filter.

// Entry type → ReviewStatus mapping (aligned with Java ReviewStatus enum)
const ENTRY_TO_STATUS = {
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

// Status sort order (OPEN first → PENDING_HUMAN → ACTIVE → DISPUTED → AGREED → DECLINED)
const STATUS_ORDER = {
  OPEN: 0,
  PENDING_HUMAN: 1,
  ACTIVE: 2,
  DISPUTED: 3,
  AGREED: 4,
  DECLINED: 5,
  VERIFIED: 6,
  DEFERRED: 7,
};

// Status icons
const STATUS_ICON = {
  OPEN: '○',
  ACTIVE: '⟳',
  AGREED: '✓',
  PENDING_HUMAN: '⚑',
  DECLINED: '✓',
  DISPUTED: '✕',
  VERIFIED: '✓✓',
  DEFERRED: '⏸',
};

// Review tracker styles
const styles = new CSSStyleSheet();
styles.replaceSync(`
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

  .filter-toggle input[type="checkbox"] {
    cursor: pointer;
  }

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

  /* Status-specific styling */
  .point-item.status-open {
    border-left: 3px solid var(--ink);
  }

  .point-item.status-active {
    border-left: 3px solid var(--warn);
  }

  .point-item.status-agreed {
    border-left: 3px solid var(--approve);
    opacity: 0.6;
  }

  .point-item.status-agreed .point-summary {
    text-decoration: line-through;
  }

  .point-item.status-pending_human {
    border: 2px solid var(--warn);
    background: #fff8f0;
  }

  .point-item.status-declined {
    border-left: 3px solid var(--muted);
    opacity: 0.6;
  }

  .point-item.status-declined .point-summary {
    text-decoration: line-through;
  }

  .point-item.status-disputed {
    border-left: 3px solid var(--error);
  }

  /* QUALIFY gets blue accent (vs COUNTER amber) */
  .point-item.qualify-active {
    border-left: 3px solid var(--accent);
  }

  .point-item.selected {
    outline: 2px solid var(--accent, #4a6a8a);
    outline-offset: -2px;
    background: rgba(74, 106, 138, 0.08);
  }
`);

class DraftHouseReviewTracker extends HTMLElement {
  #shadow = null;
  #props = null;
  #unsubscribe = null;
  #entries = [];
  #hideResolved = false;
  #selectedPointId = null;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(props) {
    this.#props = props;
    if (this.isConnected) {
      this.#initialize();
    }
  }

  connectedCallback() {
    if (this.#props) {
      this.#initialize();
    } else {
      this.#renderPlaceholder();
    }
  }

  disconnectedCallback() {
    if (this.#unsubscribe) {
      this.#unsubscribe();
      this.#unsubscribe = null;
    }
  }

  #initialize() {
    this.#entries = [];
    this.#render();

    if (this.#unsubscribe) {
      this.#unsubscribe();
    }

    const listener = (e) => {
      const { topic, payload } = e.detail;
      if (topic === 'debate-entries') {
        this.#entries.push(...payload);
        this.#render();
      } else if (topic === 'reconnected') {
        this.#entries = [];
        this.#render();
      }
    };

    document.addEventListener('pages-event', listener);
    this.#unsubscribe = () => document.removeEventListener('pages-event', listener);
  }

  #renderPlaceholder() {
    this.#shadow.innerHTML = `
      <div class="placeholder">
        <div>Waiting for debate session…</div>
      </div>
    `;
  }

  #render() {
    const points = this.#derivePoints();
    const resolved = points.filter(p => p.status === 'AGREED' || p.status === 'DECLINED' || p.status === 'VERIFIED' || p.status === 'DEFERRED');
    const total = points.length;
    const resolvedCount = resolved.length;

    const container = document.createElement('div');
    container.className = 'tracker-container';

    // Progress bar
    const progressContainer = document.createElement('div');
    progressContainer.className = 'progress-bar-container';

    const progressLabel = document.createElement('div');
    progressLabel.className = 'progress-label';
    progressLabel.textContent = `${resolvedCount} of ${total} resolved`;
    progressContainer.appendChild(progressLabel);

    const progressBar = document.createElement('div');
    progressBar.className = 'progress-bar';
    const progressFill = document.createElement('div');
    progressFill.className = 'progress-fill';
    progressFill.style.width = total > 0 ? `${(resolvedCount / total) * 100}%` : '0%';
    progressBar.appendChild(progressFill);
    progressContainer.appendChild(progressBar);

    container.appendChild(progressContainer);

    // Filter toggle
    const filterBar = document.createElement('div');
    filterBar.className = 'filter-bar';
    const filterToggle = document.createElement('label');
    filterToggle.className = 'filter-toggle';

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = this.#hideResolved;
    checkbox.addEventListener('change', (e) => {
      this.#hideResolved = e.target.checked;
      this.#render();
    });

    filterToggle.appendChild(checkbox);
    filterToggle.appendChild(document.createTextNode('Hide resolved'));
    filterBar.appendChild(filterToggle);
    container.appendChild(filterBar);

    // Points list
    const pointsList = document.createElement('div');
    pointsList.className = 'points-list';

    const visiblePoints = this.#hideResolved
      ? points.filter(p => p.status !== 'AGREED' && p.status !== 'DECLINED' && p.status !== 'VERIFIED' && p.status !== 'DEFERRED')
      : points;

    if (visiblePoints.length === 0) {
      const placeholder = document.createElement('div');
      placeholder.className = 'placeholder';
      placeholder.textContent = points.length === 0
        ? 'No review points yet'
        : 'All points resolved';
      pointsList.appendChild(placeholder);
    } else {
      for (const point of visiblePoints) {
        const pointEl = this.#createPointElement(point);
        pointsList.appendChild(pointEl);
      }
    }

    container.appendChild(pointsList);

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(container);
  }

  // Client fold: filter → group by pointId → map last entryType → ReviewStatus
  #derivePoints() {
    // Filter: only entries that affect status (RAISE, AGREE, COUNTER, etc.)
    // Sub-task entries (SUB_TASK_*) carry pointId but don't change status
    const statusEntries = this.#entries.filter(e =>
      e.pointId &&
      ENTRY_TO_STATUS[e.entryType]
    );

    // Group by pointId
    const byPointId = new Map();
    for (const entry of statusEntries) {
      if (!byPointId.has(entry.pointId)) {
        byPointId.set(entry.pointId, []);
      }
      byPointId.get(entry.pointId).push(entry);
    }

    // Map each pointId to derived point
    const points = [];
    for (const [pointId, entries] of byPointId) {
      const lastEntry = entries[entries.length - 1];
      const status = ENTRY_TO_STATUS[lastEntry.entryType];
      const raiseEntry = entries.find(e => e.entryType === 'RAISE');

      // Extract summary (first line, truncate to 120 chars)
      const summary = raiseEntry
        ? raiseEntry.content.split('\n')[0].substring(0, 120)
        : lastEntry.content.split('\n')[0].substring(0, 120);

      // Build agent trail
      const trail = this.#buildAgentTrail(entries);

      // Check if QUALIFY was last status-changing entry
      const isQualifyActive = lastEntry.entryType === 'QUALIFY';

      // Per-phase round numbers for timeline trail highlight
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

    // Sort: OPEN → PENDING_HUMAN → ACTIVE → DISPUTED → AGREED → DECLINED
    points.sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status]);

    return points;
  }

  #buildAgentTrail(entries) {
    const segments = [];
    let currentRound = null;

    for (const entry of entries) {
      if (entry.round !== currentRound) {
        if (currentRound !== null) {
          segments.push(`round ${entry.round}`);
        }
        currentRound = entry.round;
      }

      const agent = this.#formatAgentShort(entry.agentRole);
      const action = this.#formatActionShort(entry.entryType);
      segments.push(`${agent} ${action}`);
    }

    return segments.join(' → ');
  }

  #formatAgentShort(agentRole) {
    const labels = { REV: 'REV', IMP: 'IMP', FAC: 'FAC' };
    return labels[agentRole] || agentRole;
  }

  #formatActionShort(entryType) {
    const labels = {
      RAISE: 'raised',
      AGREE: 'agreed',
      COUNTER: 'countered',
      DISPUTE: 'disputed',
      QUALIFY: 'qualified',
      FLAG_HUMAN: 'flagged',
      DECLINED: 'declined',
    };
    return labels[entryType] || entryType;
  }

  #createPointElement(point) {
    const el = document.createElement('div');
    const statusClass = point.status.toLowerCase();
    el.className = `point-item status-${statusClass}`;
    if (this.#selectedPointId === point.pointId) el.classList.add('selected');

    // Add qualify-active class if QUALIFY was the last action
    if (point.isQualifyActive) {
      el.classList.add('qualify-active');
    }

    // Header: icon + summary
    const header = document.createElement('div');
    header.className = 'point-header';

    const icon = document.createElement('span');
    icon.className = 'point-icon';
    icon.textContent = STATUS_ICON[point.status] || '·';
    header.appendChild(icon);

    const summary = document.createElement('div');
    summary.className = 'point-summary';
    summary.textContent = point.summary;
    header.appendChild(summary);

    el.appendChild(header);

    // Location reference
    if (point.location) {
      const location = document.createElement('div');
      location.className = 'point-location';
      location.textContent = point.location;
      el.appendChild(location);
    }

    // Agent trail
    const trail = document.createElement('div');
    trail.className = 'point-trail';
    trail.textContent = point.trail;
    el.appendChild(trail);

    // Click handler — toggle selection
    el.addEventListener('click', () => {
      const wasSelected = this.#selectedPointId === point.pointId;
      this.#selectedPointId = wasSelected ? null : point.pointId;

      // Update selected styling on all items
      this.#shadow.querySelectorAll('.point-item').forEach(item => item.classList.remove('selected'));
      if (!wasSelected) el.classList.add('selected');

      const eventName = wasSelected ? 'point-deselected' : 'point-selected';
      this.dispatchEvent(new CustomEvent(eventName, {
        bubbles: true,
        detail: {
          pointId: point.pointId,
          round: point.round,
          raiseRound: point.raiseRound,
          fixRound: point.fixRound,
          verifyRound: point.verifyRound,
          location: point.location
        }
      }));
    });

    return el;
  }
}

// Register custom element
customElements.define('drafthouse-review-tracker', DraftHouseReviewTracker);
