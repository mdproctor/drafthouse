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
