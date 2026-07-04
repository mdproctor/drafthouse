const styles = new CSSStyleSheet();
styles.replaceSync(`
  :host {
    display: none;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    color: var(--sepia);
  }

  :host(.visible) {
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
`);

class DraftHouseContextGauge extends HTMLElement {
  #shadow = null;
  #unsubscribe = null;
  #windowSizeChars = null;
  #label = null;
  #fill = null;
  #wrapper = null;

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(_props) {}

  connectedCallback() {
    this.#render();
    const listener = (e) => {
      const { topic, payload } = e.detail;
      if (topic === 'context-usage') {
        this.#handleMeta(payload);
      } else if (topic === 'reconnected') {
        this.#reset();
      }
    };
    document.addEventListener('pages-event', listener);
    this.#unsubscribe = () => document.removeEventListener('pages-event', listener);
  }

  disconnectedCallback() {
    if (this.#unsubscribe) {
      this.#unsubscribe();
      this.#unsubscribe = null;
    }
  }

  #render() {
    const wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:contents';

    const label = document.createElement('span');
    label.className = 'gauge-label';
    label.textContent = 'Ctx: —';
    wrapper.appendChild(label);

    const bar = document.createElement('div');
    bar.className = 'gauge-bar';
    const fill = document.createElement('div');
    fill.className = 'gauge-fill fill-normal';
    fill.style.width = '0%';
    bar.appendChild(fill);
    wrapper.appendChild(bar);

    this.#shadow.innerHTML = '';
    this.#shadow.appendChild(wrapper);
    this.#label = label;
    this.#fill = fill;
    this.#wrapper = wrapper;
  }

  #handleMeta(data) {
    if (data.windowSizeChars != null) {
      this.#windowSizeChars = data.windowSizeChars;
    }

    this.classList.add('visible');

    const pct = data.effectivePercent;
    const clamped = Math.min(pct, 100);

    this.#label.textContent = `Ctx: ${Math.round(pct)}%`;
    this.#fill.style.width = clamped + '%';

    this.#fill.className = 'gauge-fill ' + (
      pct >= 80 ? 'fill-error' :
      pct >= 60 ? 'fill-warn' : 'fill-normal'
    );

    if (data.thresholdExceeded) {
      this.#wrapper.classList.add('threshold-exceeded');
    } else {
      this.#wrapper.classList.remove('threshold-exceeded');
    }

    const contribK = Math.round((data.serverContributionChars || 0) / 1000);
    const windowK = this.#windowSizeChars ? Math.round(this.#windowSizeChars / 1000) : '?';
    const agentStr = data.agentReportedPercent != null
      ? Math.round(data.agentReportedPercent) + '%'
      : '—';
    this.title = `Server contribution: ${contribK}k / ${windowK}k chars (${data.messageCount || 0} messages). Agent-reported: ${agentStr}`;
  }

  #reset() {
    this.classList.remove('visible');
    this.#windowSizeChars = null;
    if (this.#label) this.#label.textContent = 'Ctx: —';
    if (this.#fill) {
      this.#fill.style.width = '0%';
      this.#fill.className = 'gauge-fill fill-normal';
    }
    if (this.#wrapper) this.#wrapper.classList.remove('threshold-exceeded');
  }
}

customElements.define('drafthouse-context-gauge', DraftHouseContextGauge);
