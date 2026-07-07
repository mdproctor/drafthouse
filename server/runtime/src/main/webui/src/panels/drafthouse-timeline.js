// panels/drafthouse-timeline.js
// <drafthouse-timeline> — Shadow DOM Web Component for document version timeline.
// Renders ROUND_SNAPSHOT entries as clickable markers on a horizontal timeline strip.
// Adjacent comparison by default, shift-click for non-adjacent. Emits
// timeline-comparison-changed so the diff panel can load snapshot content.

const styles = new CSSStyleSheet();
styles.replaceSync(`
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
  .marker.trail-raise, .marker.trail-verify {
    opacity: 0.6;
  }
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
  .marker.selected .marker-dot {
    background: var(--accent, #4a6a8a);
  }

  .marker-label {
    font-size: 10px;
    color: var(--muted, #8a7a5a);
    margin-top: 1px;
  }
  .marker.selected .marker-label {
    color: var(--ink, #2a2218);
    font-weight: 600;
  }

  .hidden { display: none; }
`);

class DraftHouseTimeline extends HTMLElement {
  #shadow;
  #snapshots = [];       // { label, round, commitHash, documentPath }
  #selectedA = null;     // index
  #selectedB = null;     // index
  #sessionId = null;
  #trailHighlight = null; // { raiseRound, fixRound, verifyRound }
  #initialized = false;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(props) {
    if (props.sessionId) this.#sessionId = props.sessionId;
    this.#initialize();
  }

  #initialize() {
    // Guard: configure() may be called multiple times (pages-runtime init +
    // connectDebateSession). Listeners must only be registered once.
    if (this.#initialized) return;
    this.#initialized = true;

    const listener = (e) => {
      const { topic, payload } = e.detail;
      if (topic === 'debate-entries') {
        this.#handleEntries(Array.isArray(payload) ? payload : [payload]);
      } else if (topic === 'reconnected') {
        this.#snapshots = [];
        this.#selectedA = null;
        this.#selectedB = null;
        this.#render();
      }
    };
    document.addEventListener('pages-event', listener);

    // Listen for point-selected/deselected from review tracker
    document.addEventListener('point-selected', (e) => {
      this.#trailHighlight = {
        raiseRound: e.detail.raiseRound,
        fixRound: e.detail.fixRound,
        verifyRound: e.detail.verifyRound,
      };
      // Jump diff to the fix round
      if (e.detail.fixRound != null) {
        const fixIndex = this.#snapshots.findIndex(
          s => s.round === e.detail.fixRound);
        if (fixIndex >= 0) {
          const prevIndex = Math.max(0, fixIndex - 1);
          this.#selectedA = prevIndex;
          this.#selectedB = fixIndex;
          this.#emitComparison();
        }
      }
      this.#render();
    });

    document.addEventListener('point-deselected', () => {
      this.#trailHighlight = null;
      this.#render();
    });

    this.#render();
  }

  #handleEntries(entries) {
    let added = false;
    for (const entry of entries) {
      if (entry.entryType === 'ROUND_SNAPSHOT') {
        this.#snapshots.push({
          label: entry.content,
          round: entry.round,
          commitHash: entry.commitHash,
          documentPath: entry.documentPath,
        });
        added = true;
      }
    }
    if (added) {
      // Default: select last two for adjacent comparison
      if (this.#snapshots.length >= 2 && this.#selectedA == null) {
        this.#selectedA = this.#snapshots.length - 2;
        this.#selectedB = this.#snapshots.length - 1;
        this.#emitComparison();
      } else if (this.#snapshots.length === 1 && this.#selectedA == null) {
        this.#selectedA = 0;
        this.#selectedB = 0;
      }
      this.#render();
    }
  }

  #handleClick(index, shiftKey) {
    if (shiftKey && this.#selectedA != null) {
      // Shift-click: set second endpoint
      this.#selectedB = index;
    } else if (this.#selectedA === index && this.#selectedB != null) {
      // Clicking already-selected marker clears selection
      this.#selectedA = null;
      this.#selectedB = null;
    } else {
      // Single click: set as one end, pair with adjacent
      this.#selectedA = index;
      this.#selectedB = Math.min(index + 1, this.#snapshots.length - 1);
      if (this.#selectedA === this.#selectedB && index > 0) {
        this.#selectedA = index - 1;
        this.#selectedB = index;
      }
    }
    // Ensure A < B
    if (this.#selectedA != null && this.#selectedB != null && this.#selectedA > this.#selectedB) {
      [this.#selectedA, this.#selectedB] = [this.#selectedB, this.#selectedA];
    }
    this.#emitComparison();
    this.#render();
  }

  #emitComparison() {
    if (this.#selectedA == null || this.#selectedB == null) return;
    this.dispatchEvent(new CustomEvent('timeline-comparison-changed', {
      bubbles: true,
      composed: true,
      detail: {
        sessionId: this.#sessionId,
        indexA: this.#selectedA,
        indexB: this.#selectedB,
        labelA: this.#snapshots[this.#selectedA]?.label || `Snapshot ${this.#selectedA}`,
        labelB: this.#snapshots[this.#selectedB]?.label || `Snapshot ${this.#selectedB}`,
      }
    }));
  }

  #render() {
    if (this.#snapshots.length === 0) {
      this.#shadow.innerHTML = '';
      this.classList.add('hidden');
      return;
    }
    this.classList.remove('hidden');

    const track = document.createElement('div');
    track.className = 'timeline-track';

    this.#snapshots.forEach((snap, i) => {
      if (i > 0) {
        const conn = document.createElement('div');
        conn.className = 'connector';
        if (this.#selectedA != null && this.#selectedB != null
            && i > this.#selectedA && i <= this.#selectedB) {
          conn.classList.add('active');
        }
        track.appendChild(conn);
      }

      const marker = document.createElement('div');
      marker.className = 'marker';
      if (i === this.#selectedA || i === this.#selectedB) marker.classList.add('selected');

      // Trail highlight
      if (this.#trailHighlight) {
        if (snap.round === this.#trailHighlight.fixRound) marker.classList.add('trail-fix');
        if (snap.round === this.#trailHighlight.raiseRound) marker.classList.add('trail-raise');
        if (snap.round === this.#trailHighlight.verifyRound) marker.classList.add('trail-verify');
      }

      const dot = document.createElement('div');
      dot.className = 'marker-dot';
      marker.appendChild(dot);

      const label = document.createElement('div');
      label.className = 'marker-label';
      // Use the label from the entry content (human-readable summary)
      label.textContent = snap.label.split(' — ')[0] || `Round ${snap.round}`;
      marker.appendChild(label);

      marker.addEventListener('click', (e) => this.#handleClick(i, e.shiftKey));
      track.appendChild(marker);
    });

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(track);
  }
}

customElements.define('drafthouse-timeline', DraftHouseTimeline);
