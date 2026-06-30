// panels/drafthouse-debate.js
// <drafthouse-debate> — Debate panel Web Component
// Subscribes to pages-event, renders conversation feed grouped by round.

// Debate panel styles
const styles = new CSSStyleSheet();
styles.replaceSync(`
  :host {
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;
  }

  .debate-container {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
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

  .round-divider {
    margin: 20px 0 12px;
    padding: 4px 10px;
    border-bottom: 1px solid var(--border);
    color: var(--muted);
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  .entry {
    padding: 10px 12px;
    border: 1px solid var(--border-light);
    border-radius: 3px;
    background: white;
    cursor: pointer;
    transition: all 0.15s;
  }

  .entry:hover {
    border-color: var(--accent);
    box-shadow: 0 1px 3px rgba(0,0,0,0.05);
  }

  .entry-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
    font-size: 11px;
    color: var(--muted);
  }

  .entry-agent {
    font-weight: 600;
    color: var(--sepia);
  }

  .entry-timestamp {
    margin-left: auto;
    font-size: 10px;
  }

  .entry-content {
    color: var(--ink);
    line-height: 1.5;
    white-space: pre-wrap;
    word-wrap: break-word;
  }

  .entry-meta {
    margin-top: 6px;
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  .badge {
    display: inline-block;
    padding: 2px 6px;
    border-radius: 2px;
    font-size: 9px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.3px;
  }

  .badge-priority-high {
    background: var(--error);
    color: white;
  }

  .badge-priority-medium {
    background: var(--warn);
    color: white;
  }

  .badge-priority-low {
    background: var(--muted);
    color: white;
  }

  .badge-scope {
    background: var(--accent-tint);
    color: var(--accent);
    border: 1px solid var(--accent);
  }

  .badge-location {
    background: var(--chrome);
    color: var(--sepia);
    border: 1px solid var(--border);
    font-family: 'SFMono-Regular', Consolas, monospace;
  }

  /* Entry type visual treatment */
  .entry-raise {
    border-left: 3px solid var(--ink);
  }

  .entry-agree {
    border-left: 3px solid var(--approve);
  }

  .entry-counter {
    border-left: 3px solid var(--warn);
  }

  .entry-dispute {
    border-left: 3px solid var(--error);
  }

  .entry-qualify {
    border-left: 3px solid var(--accent);
  }

  .entry-flag_human {
    border: 2px solid var(--warn);
    background: #fff8f0;
  }

  .entry-flag_human::before {
    content: '⚠️ HUMAN ATTENTION REQUIRED';
    display: block;
    margin-bottom: 6px;
    font-size: 10px;
    font-weight: 700;
    color: var(--warn);
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  .entry-declined {
    opacity: 0.5;
    text-decoration: line-through;
    background: var(--chrome);
  }

  .entry-memo {
    font-style: italic;
    color: var(--muted);
    border-left: 3px solid var(--border-light);
  }

  .entry-sub_task_request,
  .entry-sub_task_finding,
  .entry-sub_task_error {
    margin-left: 24px;
    border-left: 2px dashed var(--border);
    background: var(--chrome);
    font-size: 12px;
  }

  .entry-restart_context {
    border: none;
    background: none;
    padding: 8px 0;
    text-align: center;
    color: var(--muted);
    font-size: 11px;
    cursor: default;
  }

  .entry-restart_context:hover {
    border: none;
    box-shadow: none;
  }

  .entry-restart_context::before {
    content: '';
    display: block;
    width: 100%;
    height: 1px;
    border-top: 1px dashed var(--border);
    margin-bottom: 4px;
  }

  .entry-restart_context::after {
    content: '';
    display: block;
    width: 100%;
    height: 1px;
    border-top: 1px dashed var(--border);
    margin-top: 4px;
  }
`);

class DraftHouseDebate extends HTMLElement {
  #shadow = null;
  #props = null;
  #unsubscribe = null;
  #entries = [];
  #container = null;
  #shouldAutoScroll = true;

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
        if (this.#shouldAutoScroll) {
          this.#scrollToBottom();
        }
      } else if (topic === 'sse-reconnect') {
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
    const container = document.createElement('div');
    container.className = 'debate-container';

    if (this.#entries.length === 0) {
      container.innerHTML = '<div class="placeholder">No entries yet</div>';
    } else {
      this.#renderGroupedEntries(container);
    }

    // Track scroll position to determine auto-scroll behavior
    container.addEventListener('scroll', () => {
      const isNearBottom =
        container.scrollHeight - container.scrollTop - container.clientHeight < 50;
      this.#shouldAutoScroll = isNearBottom;
    });

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(container);
    this.#container = container;
  }

  #renderGroupedEntries(container) {
    let currentRound = null;

    for (const entry of this.#entries) {
      // Add round divider if round changed
      if (entry.round !== currentRound) {
        currentRound = entry.round;
        const divider = document.createElement('div');
        divider.className = 'round-divider';
        divider.textContent = `Round ${entry.round}`;
        container.appendChild(divider);
      }

      const entryEl = this.#createEntryElement(entry);
      container.appendChild(entryEl);
    }
  }

  #createEntryElement(entry) {
    const el = document.createElement('div');
    const typeClass = entry.entryType.toLowerCase();
    el.className = `entry entry-${typeClass}`;

    if (entry.entryType === 'RESTART_CONTEXT') {
      el.innerHTML = '<span>── session branched ──</span>';
      return el;
    }

    // Header: agent + timestamp
    const header = document.createElement('div');
    header.className = 'entry-header';

    const agent = document.createElement('span');
    agent.className = 'entry-agent';
    agent.textContent = this.#formatAgent(entry.agentRole);
    header.appendChild(agent);

    const typeLabel = document.createElement('span');
    typeLabel.textContent = this.#formatEntryType(entry.entryType);
    header.appendChild(typeLabel);

    if (entry.timestamp) {
      const timestamp = document.createElement('span');
      timestamp.className = 'entry-timestamp';
      timestamp.textContent = this.#formatTimestamp(entry.timestamp);
      header.appendChild(timestamp);
    }

    el.appendChild(header);

    // Content
    const content = document.createElement('div');
    content.className = 'entry-content';
    content.textContent = entry.content;
    el.appendChild(content);

    // Metadata badges (priority, scope, location)
    const meta = document.createElement('div');
    meta.className = 'entry-meta';

    if (entry.priority) {
      const priorityBadge = document.createElement('span');
      priorityBadge.className = `badge badge-priority-${entry.priority.toLowerCase()}`;
      priorityBadge.textContent = entry.priority;
      meta.appendChild(priorityBadge);
    }

    if (entry.scope) {
      const scopeBadge = document.createElement('span');
      scopeBadge.className = 'badge badge-scope';
      scopeBadge.textContent = entry.scope;
      meta.appendChild(scopeBadge);
    }

    if (entry.location) {
      const locationBadge = document.createElement('span');
      locationBadge.className = 'badge badge-location';
      locationBadge.textContent = entry.location;
      meta.appendChild(locationBadge);
    }

    if (entry.pointId && (entry.entryType === 'SUB_TASK_REQUEST' ||
                          entry.entryType === 'SUB_TASK_FINDING' ||
                          entry.entryType === 'SUB_TASK_ERROR')) {
      const pointBadge = document.createElement('span');
      pointBadge.className = 'badge';
      pointBadge.textContent = `→ ${entry.pointId.substring(0, 8)}`;
      meta.appendChild(pointBadge);
    }

    if (meta.children.length > 0) {
      el.appendChild(meta);
    }

    // Click handler for point selection
    if (entry.pointId && entry.entryType !== 'RESTART_CONTEXT') {
      el.addEventListener('click', () => {
        this.dispatchEvent(new CustomEvent('point-selected', {
          bubbles: true,
          detail: {
            pointId: entry.pointId,
            round: entry.round,
            location: entry.location
          }
        }));
      });
    }

    return el;
  }

  #formatAgent(agentRole) {
    const labels = {
      REV: 'Reviewer',
      IMP: 'Implementer',
      FAC: 'Facilitator'
    };
    return labels[agentRole] || agentRole;
  }

  #formatEntryType(type) {
    const labels = {
      RAISE: 'raised point',
      AGREE: 'agreed',
      COUNTER: 'countered',
      DISPUTE: 'disputed',
      QUALIFY: 'qualified',
      FLAG_HUMAN: 'flagged for human',
      DECLINED: 'declined',
      MEMO: 'memo',
      SUB_TASK_REQUEST: 'sub-task request',
      SUB_TASK_FINDING: 'sub-task finding',
      SUB_TASK_ERROR: 'sub-task error',
      RESTART_CONTEXT: 'restart'
    };
    return labels[type] || type;
  }

  #formatTimestamp(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now - date;
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins}m ago`;

    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;

    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  #scrollToBottom() {
    if (this.#container) {
      this.#container.scrollTop = this.#container.scrollHeight;
    }
  }
}

// Register custom element
customElements.define('drafthouse-debate', DraftHouseDebate);
