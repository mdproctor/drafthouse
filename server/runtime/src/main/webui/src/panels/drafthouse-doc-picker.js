const styles = new CSSStyleSheet();
styles.replaceSync(`
  :host {
    display: none;
    position: relative;
    align-items: center;
    font-size: 12px;
  }

  :host(.visible) {
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

  .slot-btn:hover {
    background: var(--accent-light);
  }

  .slot-btn.active {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
  }

  .slot-btn.pending {
    border-style: dashed;
    border-color: var(--accent);
    color: var(--accent);
  }

  .error-flash {
    padding: 4px 12px;
    font-size: 11px;
    color: var(--error, #c0392b);
  }
`);

class DraftHouseDocPicker extends HTMLElement {
  #shadow = null;
  #unsubscribe = null;
  #documents = [];
  #currentComparison = null;
  #sessionId = null;
  #open = false;
  #pendingA = null;
  #pendingB = null;
  #outsideClickHandler = null;
  #escapeHandler = null;
  #errorTimeout = null;

  static get observedAttributes() {
    return ['session-id'];
  }

  constructor() {
    super();
    this.#shadow = this.attachShadow({ mode: 'open' });
    this.#shadow.adoptedStyleSheets = [styles];
  }

  configure(_props) {}

  attributeChangedCallback(name, _oldVal, newVal) {
    if (name === 'session-id') {
      this.#sessionId = newVal;
    }
  }

  connectedCallback() {
    this.#render();

    const listener = (e) => {
      const { topic, payload } = e.detail;
      if (topic === 'documents-changed') {
        this.#documents = payload.documents || [];
        if (this.#pendingA && !this.#documents.some(d => d.path === this.#pendingA)) {
          this.#pendingA = null;
        }
        if (this.#pendingB && !this.#documents.some(d => d.path === this.#pendingB)) {
          this.#pendingB = null;
        }
        if (this.#documents.length === 0) {
          this.#open = false;
        }
        this.#render();
      } else if (topic === 'comparison-changed') {
        if (payload.pathA == null && payload.pathB == null) {
          this.#currentComparison = null;
        } else {
          this.#currentComparison = { pathA: payload.pathA, pathB: payload.pathB };
        }
        this.#pendingA = null;
        this.#pendingB = null;
        this.#render();
      } else if (topic === 'reconnected') {
        this.#documents = [];
        this.#currentComparison = null;
        this.#pendingA = null;
        this.#pendingB = null;
        this.#open = false;
        this.#render();
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
    this.#removeGlobalListeners();
    if (this.#errorTimeout) clearTimeout(this.#errorTimeout);
  }

  #render() {
    const count = this.#documents.length;
    this.classList.toggle('visible', count > 0);

    this.#shadow.innerHTML = '';

    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = '\u{1F4C4} ' + count;
    badge.addEventListener('click', (e) => {
      e.stopPropagation();
      this.#open = !this.#open;
      this.#render();
    });
    this.#shadow.appendChild(badge);

    if (!this.#open || count === 0) {
      this.#removeGlobalListeners();
      return;
    }

    const dropdown = document.createElement('div');
    dropdown.className = 'dropdown';

    const header = document.createElement('div');
    header.className = 'header';
    header.textContent = 'Documents';
    dropdown.appendChild(header);

    for (const doc of this.#documents) {
      const row = document.createElement('div');
      row.className = 'doc-row';

      const label = document.createElement('span');
      label.className = 'doc-label';
      label.title = doc.path;
      const parts = doc.path.split('/');
      label.textContent = doc.label || parts[parts.length - 1];
      row.appendChild(label);

      const btnA = document.createElement('button');
      btnA.className = 'slot-btn';
      btnA.textContent = 'A';
      if (this.#currentComparison && this.#currentComparison.pathA === doc.path) {
        btnA.classList.add('active');
      } else if (this.#pendingA === doc.path) {
        btnA.classList.add('pending');
      }
      btnA.addEventListener('click', (e) => {
        e.stopPropagation();
        this.#handleSlotClick('a', doc.path);
      });
      row.appendChild(btnA);

      const btnB = document.createElement('button');
      btnB.className = 'slot-btn';
      btnB.textContent = 'B';
      if (this.#currentComparison && this.#currentComparison.pathB === doc.path) {
        btnB.classList.add('active');
      } else if (this.#pendingB === doc.path) {
        btnB.classList.add('pending');
      }
      btnB.addEventListener('click', (e) => {
        e.stopPropagation();
        this.#handleSlotClick('b', doc.path);
      });
      row.appendChild(btnB);

      dropdown.appendChild(row);
    }

    this.#shadow.appendChild(dropdown);
    this.#addGlobalListeners();
  }

  #handleSlotClick(slot, path) {
    if (this.#currentComparison) {
      // Comparison exists — single-click swap
      if (slot === 'a') {
        if (this.#currentComparison.pathA === path) return; // identity no-op
        this.#postComparison(path, this.#currentComparison.pathB);
      } else {
        if (this.#currentComparison.pathB === path) return; // identity no-op
        this.#postComparison(this.#currentComparison.pathA, path);
      }
    } else {
      // No comparison — pending state
      if (slot === 'a') {
        this.#pendingA = (this.#pendingA === path) ? null : path; // toggle
        if (this.#pendingA && this.#pendingB) {
          this.#postComparison(this.#pendingA, this.#pendingB);
          return;
        }
      } else {
        this.#pendingB = (this.#pendingB === path) ? null : path; // toggle
        if (this.#pendingA && this.#pendingB) {
          this.#postComparison(this.#pendingA, this.#pendingB);
          return;
        }
      }
      this.#render();
    }
  }

  #postComparison(pathA, pathB) {
    if (!this.#sessionId) return;
    fetch(`/api/debate/${this.#sessionId}/comparison`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pathA, pathB }),
    }).then(r => {
      if (!r.ok) {
        this.#showError();
      }
    }).catch(() => {
      this.#showError();
    });
  }

  #showError() {
    if (this.#errorTimeout) clearTimeout(this.#errorTimeout);
    const existing = this.#shadow.querySelector('.error-flash');
    if (existing) existing.remove();
    const dropdown = this.#shadow.querySelector('.dropdown');
    if (!dropdown) return;
    const err = document.createElement('div');
    err.className = 'error-flash';
    err.textContent = 'Failed to update comparison';
    dropdown.appendChild(err);
    this.#errorTimeout = setTimeout(() => {
      err.remove();
      this.#errorTimeout = null;
    }, 3000);
  }

  #addGlobalListeners() {
    if (this.#outsideClickHandler) return;
    this.#outsideClickHandler = (e) => {
      if (!this.contains(e.target) && !this.#shadow.contains(e.target)) {
        this.#open = false;
        this.#render();
      }
    };
    this.#escapeHandler = (e) => {
      if (e.key === 'Escape') {
        this.#open = false;
        this.#render();
      }
    };
    document.addEventListener('click', this.#outsideClickHandler);
    document.addEventListener('keydown', this.#escapeHandler);
  }

  #removeGlobalListeners() {
    if (this.#outsideClickHandler) {
      document.removeEventListener('click', this.#outsideClickHandler);
      this.#outsideClickHandler = null;
    }
    if (this.#escapeHandler) {
      document.removeEventListener('keydown', this.#escapeHandler);
      this.#escapeHandler = null;
    }
  }
}

customElements.define('drafthouse-doc-picker', DraftHouseDocPicker);
