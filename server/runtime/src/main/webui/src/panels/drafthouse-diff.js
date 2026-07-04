// panels/drafthouse-diff.js
// <drafthouse-diff> — Shadow DOM Web Component for side-by-side markdown diff.
// Extracted from index.html: LCS line diff, word-level highlights, canvas minimap,
// heading-based scroll sync, file watch SSE, drag-and-drop, diff navigation.

import { marked } from "marked";


// ── Adopted stylesheet for panel-internal structure ─────────────────
const sheet = new CSSStyleSheet();
sheet.replaceSync(/* css */`
  :host {
    display: flex;
    flex: 1;
    overflow: hidden;
    min-height: 0;
  }

  /* ── Panel columns ── */
  .panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-width: 0;
  }

  /* ── Drag divider + diff minimap ── */
  #divider {
    width: 20px;
    background: var(--chrome, #ede7d9);
    cursor: col-resize;
    flex-shrink: 0;
    position: relative;
    border-left: 1px solid var(--border, #c8baa0);
    border-right: 1px solid var(--border, #c8baa0);
  }
  #diff-map {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    display: block;
  }

  /* ── Panel header (file picker strip) ── */
  .panel-header {
    flex-shrink: 0;
    background: var(--chrome, #ede7d9);
    border-bottom: 1px solid var(--border, #c8baa0);
    padding: 5px 10px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .panel-label {
    font-weight: 700;
    font-size: 12px;
    color: var(--ink, #2a2218);
    background: none;
    border: 1px solid transparent;
    border-radius: 2px;
    padding: 2px 6px;
    width: 56px;
    font-family: Georgia, serif;
    font-style: italic;
    transition: border-color .15s;
  }
  .panel-label:hover { border-color: var(--border-light, #ddd4c0); }
  .panel-label:focus { outline: none; border-color: var(--accent, #4a6a8a); background: var(--bg, #f4f0e8); }
  .panel-path {
    flex: 1;
    font-size: 10px;
    color: var(--muted, #8a7a5a);
    font-family: 'SFMono-Regular', Consolas, monospace;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .panel-path.loaded { color: var(--sepia, #5a4a30); }

  /* ── Buttons ── */
  button {
    background: var(--chrome, #ede7d9);
    color: var(--sepia, #5a4a30);
    border: 1px solid var(--border, #c8baa0);
    border-radius: 2px;
    padding: 5px 12px;
    cursor: pointer;
    font-size: 11px;
    display: flex;
    align-items: center;
    gap: 5px;
    white-space: nowrap;
    transition: all .15s;
  }
  button:hover { background: var(--bg, #f4f0e8); border-color: var(--muted, #8a7a5a); color: var(--ink, #2a2218); }
  button:disabled { opacity: .35; cursor: default; }

  /* ── Panel body ── */
  .panel-body {
    flex: 1;
    overflow: auto;
    position: relative;
  }

  /* ── Empty state ── */
  .panel-empty {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    color: var(--muted, #8a7a5a);
    text-align: center;
    padding: 40px;
    pointer-events: none;
  }
  .panel-empty.hidden { display: none; }
  .panel-empty-icon { font-size: 32px; opacity: .4; }
  .panel-empty-hint { font-size: 12px; line-height: 1.6; }

  /* ── Drop-target highlight ── */
  .panel-body.drag-over { outline: 2px dashed var(--accent, #4a6a8a); outline-offset: -4px; }

  /* ── Rendered markdown content ── */
  .md-wrap {
    padding: 28px 36px;
    max-width: 820px;
    margin: 0 auto;
    line-height: 1.75;
  }
  .md-wrap h1, .md-wrap h2 {
    border-bottom: 1px solid var(--border, #c8baa0);
    padding-bottom: .3em;
    margin: 1.5em 0 .6em;
    color: var(--ink, #2a2218);
  }
  .md-wrap h3, .md-wrap h4 { margin: 1.2em 0 .4em; color: var(--accent, #4a6a8a); }
  .md-wrap p   { margin: .7em 0; color: var(--sepia, #5a4a30); }
  .md-wrap a   { color: var(--accent, #4a6a8a); }
  .md-wrap code {
    background: var(--chrome, #ede7d9);
    border: 1px solid var(--border, #c8baa0);
    border-radius: 2px;
    padding: 2px 6px;
    font-size: .875em;
    color: var(--sepia, #5a4a30);
  }
  .md-wrap pre {
    background: var(--chrome, #ede7d9);
    border: 1px solid var(--border, #c8baa0);
    border-radius: 2px;
    padding: 0;
    overflow-x: auto;
    margin: 1em 0;
  }
  .md-wrap pre code {
    background: none;
    border: none;
    padding: 16px;
    display: block;
    color: var(--ink, #2a2218);
    font-size: .875em;
  }
  .md-wrap blockquote {
    border-left: 3px solid var(--border, #c8baa0);
    margin: 1em 0;
    padding: .5em 1em;
    color: var(--muted, #8a7a5a);
  }
  .md-wrap ul, .md-wrap ol { margin: .7em 0 .7em 1.5em; }
  .md-wrap li  { margin: .3em 0; }
  .md-wrap img {
    max-width: 100%;
    border-radius: 2px;
    margin: 1em 0;
    border: 1px solid var(--border, #c8baa0);
  }
  .md-wrap table { border-collapse: collapse; width: 100%; margin: 1em 0; }
  .md-wrap th, .md-wrap td { border: 1px solid var(--border, #c8baa0); padding: 6px 13px; }
  .md-wrap th  { background: var(--chrome, #ede7d9); }

  /* ── Inline diff markers ── */
  .diff-del {
    border-top: 2px solid #ef4444;
    border-bottom: 2px solid #ef4444;
    background: rgba(239, 68, 68, 0.05);
  }
  .diff-ins {
    border-top: 2px solid #22c55e;
    border-bottom: 2px solid #22c55e;
    background: rgba(34, 197, 94, 0.05);
  }

  /* ── Word-level diff highlights ── */
  mark.diff-word-a { background: rgba(239,68,68,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }
  mark.diff-word-b { background: rgba(34,197,94,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }

  /* ── Unified diff blocks ── */
  .diff-unified-del {
    border-left: 3px solid #ef4444;
    padding: 4px 12px 4px 28px;
    margin: 4px 0;
    background: rgba(239, 68, 68, 0.06);
    position: relative;
  }
  .diff-unified-ins {
    border-left: 3px solid #22c55e;
    padding: 4px 12px 4px 28px;
    margin: 4px 0;
    background: rgba(34, 197, 94, 0.06);
    position: relative;
  }
  .diff-unified-del::before {
    content: '−';
    position: absolute;
    left: 10px;
    top: 4px;
    color: #ef4444;
    font-weight: 700;
    font-size: 11px;
  }
  .diff-unified-ins::before {
    content: '+';
    position: absolute;
    left: 10px;
    top: 4px;
    color: #22c55e;
    font-weight: 700;
    font-size: 11px;
  }
`);

// ── Component ───────────────────────────────────────────────────────
class DraftHouseDiff extends HTMLElement {
  // -- Internal state --
  _shadow = null;
  _panels = { a: { path: null, content: null, label: 'File A' },
              b: { path: null, content: null, label: 'File B' } };
  #pathA = null;
  #pathB = null;
  _syncEnabled = true;
  _viewMode = 'split';
  _syncing = false;
  _dragging = false;
  _lastChunks = [];
  _lastTotalA = 0;
  _lastTotalB = 0;
  _currentChunkIdx = -1;
  _scrollAnchors = [];
  _apiPort = null;
  _resizeObserver = null;
  _connected = false;

  constructor() {
    super();
    this._shadow = this.attachShadow({ mode: 'open' });
    this._shadow.adoptedStyleSheets = [sheet];
    this._shadow.innerHTML = `
      <div class="panel" id="panel-a">
        <div class="panel-header">
          <input class="panel-label" id="label-a" value="File A" title="Click to rename">
          <span class="panel-path" id="path-a">No file selected</span>
          <button id="choose-a">Choose…</button>
        </div>
        <div class="panel-body" id="body-a">
          <div class="panel-empty" id="empty-a">
            <div class="panel-empty-icon">📄</div>
            <div class="panel-empty-hint">Drop a .md file here<br>or click Choose…</div>
          </div>
          <div class="md-wrap" id="render-a"></div>
        </div>
      </div>
      <div id="divider"><canvas id="diff-map"></canvas></div>
      <div class="panel" id="panel-b">
        <div class="panel-header">
          <input class="panel-label" id="label-b" value="File B" title="Click to rename">
          <span class="panel-path" id="path-b">No file selected</span>
          <button id="choose-b">Choose…</button>
        </div>
        <div class="panel-body" id="body-b">
          <div class="panel-empty" id="empty-b">
            <div class="panel-empty-icon">📄</div>
            <div class="panel-empty-hint">Drop a .md file here<br>or click Choose…</div>
          </div>
          <div class="md-wrap" id="render-b"></div>
        </div>
      </div>
    `;
  }

  // -- Convenience DOM accessor --
  _$(id) { return this._shadow.getElementById(id); }

  // ── Public API ────────────────────────────────────────────────────

  configure(props) {
    if (props.apiPort != null) this._apiPort = props.apiPort;
    if (props.labelA) this._panels.a.label = props.labelA;
    if (props.labelB) this._panels.b.label = props.labelB;
    if (this._connected) {
      this._syncPanelMeta('a');
      this._syncPanelMeta('b');
      if (props.pathA) this.loadFile('a', props.pathA);
      if (props.pathB) this.loadFile('b', props.pathB);
    } else {
      // Stash for connectedCallback
      this._pendingPathA = props.pathA || null;
      this._pendingPathB = props.pathB || null;
    }
  }

  connectedCallback() {
    this._connected = true;

    // Wire choose buttons
    this._$('choose-a').addEventListener('click', () => this.selectFile('a'));
    this._$('choose-b').addEventListener('click', () => this.selectFile('b'));

    // File change listener
    document.addEventListener('pages-event', this.#fileChangeHandler);

    // Wire label inputs
    for (const p of ['a', 'b']) {
      this._$(`label-${p}`).addEventListener('input', () => {
        this._panels[p].label = this._$(`label-${p}`).value;
      });
    }

    // Sync panel meta from stored props
    this._syncPanelMeta('a');
    this._syncPanelMeta('b');

    // Scroll sync
    this._setupScrollSync();

    // Drop zones (always — works in both Electron and browser when files have .path)
    this._setupDropZone('a');
    this._setupDropZone('b');

    // Divider drag
    this._setupDividerDrag();

    // Resize observer for diff map
    this._resizeObserver = new ResizeObserver(() => this._updateDiffMap());
    this._resizeObserver.observe(this._$('divider'));

    // Diff-map click handler
    this._$('diff-map').addEventListener('click', (e) => this._onDiffMapClick(e));

    // Selection-changed event (mouseup on rendered content)
    for (const side of ['a', 'b']) {
      this._$(`render-${side}`).addEventListener('mouseup', () => {
        const sel = this._shadow.getSelection
          ? this._shadow.getSelection()
          : document.getSelection();
        if (!sel || sel.isCollapsed) return;
        const range = sel.getRangeAt(0);
        const render = this._$(`render-${side}`);
        if (!render.contains(range.startContainer)) return;
        // Approximate line numbers from child element positions
        const children = [...render.children];
        let startLine = 0, endLine = 0;
        for (let i = 0; i < children.length; i++) {
          if (children[i].contains(range.startContainer) ||
              children[i] === range.startContainer) startLine = i;
          if (children[i].contains(range.endContainer) ||
              children[i] === range.endContainer) endLine = i;
        }
        let reportedSide = side.toUpperCase();
        if (this._viewMode === 'unified') {
          let node = range.startContainer;
          while (node && node !== render) {
            if (node.classList) {
              if (node.classList.contains('diff-unified-del')) { reportedSide = 'A'; break; }
              if (node.classList.contains('diff-unified-ins')) { reportedSide = 'B'; break; }
            }
            node = node.parentNode;
          }
        }
        this.dispatchEvent(new CustomEvent('selection-changed', {
          bubbles: true,
          detail: { side: reportedSide, startLine, endLine, selectedText: sel.toString() },
        }));
      });
    }

    // Load pending files from configure()
    if (this._pendingPathA) this.loadFile('a', this._pendingPathA);
    if (this._pendingPathB) this.loadFile('b', this._pendingPathB);
    this._pendingPathA = null;
    this._pendingPathB = null;
  }

  disconnectedCallback() {
    this._connected = false;
    document.removeEventListener('pages-event', this.#fileChangeHandler);
    if (this._resizeObserver) {
      this._resizeObserver.disconnect();
      this._resizeObserver = null;
    }
  }

  #fileChangeHandler = (e) => {
    const { topic, payload } = e.detail;
    if (topic !== 'file-changed') return;
    const path = payload.path;
    if (this.#pathA && path === this.#pathA) this.loadFile('a', path);
    if (this.#pathB && path === this.#pathB) this.loadFile('b', path);
  };

  // ── Public methods (called by shell) ──────────────────────────────

  toggleSync() {
    this._syncEnabled = !this._syncEnabled;
    if (this._syncEnabled) {
      const bodyA = this._$('body-a'), bodyB = this._$('body-b');
      bodyB.scrollTop = this._scrollAnchors.length >= 2
        ? this._interp(bodyA.scrollTop, 'a', 'b')
        : this._scrollPercent(bodyA) * (bodyB.scrollHeight - bodyB.clientHeight);
    }
    return this._syncEnabled;
  }

  get syncEnabled() { return this._syncEnabled; }

  nextDiff() {
    const idx = this._nonEqIndices();
    if (!idx.length) return;
    if (this._currentChunkIdx === -1) {
      this._currentChunkIdx = idx[0];
    } else if (this._chunkOutOfView(this._currentChunkIdx)) {
      const bodyA = this._$('body-a');
      const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
      const found = idx.find(ci => {
        const el = this._$('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                   this._$('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
        return el && el.getBoundingClientRect().top >= centre;
      });
      this._currentChunkIdx = found ?? idx[0];
    } else {
      const pos = idx.indexOf(this._currentChunkIdx);
      this._currentChunkIdx = idx[(pos + 1) % idx.length];
    }
    this._scrollToChunk(this._currentChunkIdx);
    return this.getDiffSummary();
  }

  prevDiff() {
    const idx = this._nonEqIndices();
    if (!idx.length) return;
    if (this._currentChunkIdx === -1) {
      this._currentChunkIdx = idx[idx.length - 1];
    } else if (this._chunkOutOfView(this._currentChunkIdx)) {
      const bodyA = this._$('body-a');
      const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
      const found = [...idx].reverse().find(ci => {
        const el = this._$('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                   this._$('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
        return el && el.getBoundingClientRect().bottom <= centre;
      });
      this._currentChunkIdx = found ?? idx[idx.length - 1];
    } else {
      const pos = idx.indexOf(this._currentChunkIdx);
      this._currentChunkIdx = idx[(pos - 1 + idx.length) % idx.length];
    }
    this._scrollToChunk(this._currentChunkIdx);
    return this.getDiffSummary();
  }

  swapPanels() {
    if (!this._panels.a.path || !this._panels.b.path) return;
    [this._panels.a, this._panels.b] = [this._panels.b, this._panels.a];
    for (const p of ['a', 'b']) {
      this._syncPanelMeta(p);
      this._syncPanelContent(p);
    }
    this._$('body-a').scrollTop = 0;
    this._$('body-b').scrollTop = 0;
    this._updateDiffMap();
  }

  getDiffSummary() {
    const modified = this._lastChunks.filter(c => c.op === 'mod').length;
    const deleted = this._lastChunks.filter(c => c.op === 'del').length;
    const inserted = this._lastChunks.filter(c => c.op === 'ins').length;
    const idx = this._nonEqIndices();
    const pos = idx.indexOf(this._currentChunkIdx);
    return {
      modified, deleted, inserted,
      currentIdx: pos >= 0 ? pos : -1,
      totalDiffs: idx.length,
    };
  }

  scrollToLocation(location) {
    if (!location) return;
    const ref = location.startsWith('§') ? location.slice(1).trim() : location.trim();
    if (!ref) return;
    const numMatch = ref.match(/^(\d+)(?:\.(\d+))?$/);
    for (const side of ['a', 'b']) {
      const headings = [...this._shadow.querySelectorAll(
        `#render-${side} h1, #render-${side} h2, #render-${side} h3, #render-${side} h4`
      )];
      let target = null;
      if (numMatch) {
        const major = parseInt(numMatch[1], 10);
        const minor = numMatch[2] ? parseInt(numMatch[2], 10) : null;
        const topLevel = headings.filter(h => h.tagName === 'H2' || h.tagName === 'H1');
        if (major >= 1 && major <= topLevel.length) {
          if (minor === null) {
            target = topLevel[major - 1];
          } else {
            const start = headings.indexOf(topLevel[major - 1]);
            const nextTop = topLevel[major] ? headings.indexOf(topLevel[major]) : headings.length;
            const subHeadings = headings.slice(start + 1, nextTop);
            if (minor >= 1 && minor <= subHeadings.length) {
              target = subHeadings[minor - 1];
            }
          }
        }
      } else {
        const lower = ref.toLowerCase();
        target = headings.find(h => h.textContent.toLowerCase().includes(lower));
      }
      if (target) {
        const body = this._$(`body-${side}`);
        const delta = target.getBoundingClientRect().top - body.getBoundingClientRect().top - 24;
        body.scrollBy({ top: delta, behavior: 'instant' });
      }
    }
  }

  async selectFile(panel) {
    const isElectron = typeof window.compare !== 'undefined';
    const path = isElectron
      ? await window.compare.selectFile()
      : prompt('Enter file path:');
    if (path) await this.loadFile(panel, path);
  }

  currentPath(slot) {
    return this._panels[slot]?.path || null;
  }

  get viewMode() { return this._viewMode; }

  setViewMode(mode) {
    if (mode !== 'split' && mode !== 'unified') return;
    if (mode === this._viewMode) return;
    this._viewMode = mode;

    const panelB = this._$('panel-b');
    const divider = this._$('divider');

    if (mode === 'unified') {
      panelB.style.display = 'none';
      divider.style.display = 'none';
    } else {
      panelB.style.display = '';
      divider.style.display = '';
      this._syncPanelContent('a');
      this._syncPanelContent('b');
    }
    this._updateDiffMap();
  }

  async loadFile(panel, path) {
    this._panels[panel].path = path;
    this._panels[panel].label = path.split('/').pop();
    this._syncPanelMeta(panel);
    if (panel === 'a') this.#pathA = path;
    if (panel === 'b') this.#pathB = path;
    try {
      const content = await this._fetchFile(path);
      this._renderMarkdown(panel, content);
    } catch (err) {
      this._panels[panel].content = null;
      this._syncPanelContent(panel);
      this._$(`render-${panel}`).innerHTML =
        `<p style="color:var(--error, #8a2a2a);padding:24px">Could not read file: ${err.message}</p>`;
    }
    this._updateSwapButton();
  }

  // ── Private: API helpers ──────────────────────────────────────────

  _apiUrl(path) {
    return this._apiPort ? `http://127.0.0.1:${this._apiPort}${path}` : path;
  }

  async _fetchFile(filePath) {
    const res = await fetch(this._apiUrl(`/api/file?path=${encodeURIComponent(filePath)}`));
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.text();
  }


  // ── Private: panel UI sync ────────────────────────────────────────

  _syncPanelMeta(panel) {
    const s = this._panels[panel];
    this._$(`label-${panel}`).value = s.label;
    this._$(`path-${panel}`).textContent = s.path || 'No file selected';
    this._$(`path-${panel}`).classList.toggle('loaded', !!s.path);
  }

  _syncPanelContent(panel) {
    const s = this._panels[panel];
    if (s.content) {
      this._$(`render-${panel}`).innerHTML = marked.parse(s.content);
      this._$(`empty-${panel}`).classList.add('hidden');
    } else {
      this._$(`render-${panel}`).innerHTML = '';
      this._$(`empty-${panel}`).classList.remove('hidden');
    }
  }

  _renderMarkdown(panel, content) {
    this._panels[panel].content = content;
    this._syncPanelContent(panel);
    this._updateDiffMap();
  }

  _renderUnified(aLines, bLines, chunks) {
    const renderA = this._$('render-a');
    const renderB = this._$('render-b');

    // Clear render-b to prevent stale data-diff-chunk attributes from
    // poisoning _chunkOutOfView, nextDiff/prevDiff, and scrollToLocation
    renderB.innerHTML = '';
    this._$('empty-b').classList.add('hidden');

    // Hide empty-a since we are rendering content
    this._$('empty-a').classList.add('hidden');

    let html = '';
    chunks.forEach((c, ci) => {
      if (c.op === 'eq') {
        // B-side lines — content identical; convention: show newer version
        const text = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += marked.parse(text);
      } else if (c.op === 'del') {
        const text = aLines.slice(c.aStart, c.aEnd).join('\n');
        html += `<div class="diff-unified-del" data-diff-chunk="${ci}">${marked.parse(text)}</div>`;
      } else if (c.op === 'ins') {
        const text = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += `<div class="diff-unified-ins" data-diff-chunk="${ci}">${marked.parse(text)}</div>`;
      } else if (c.op === 'mod') {
        const delText = aLines.slice(c.aStart, c.aEnd).join('\n');
        const insText = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += `<div class="diff-unified-del" data-diff-chunk="${ci}">${marked.parse(delText)}</div>`;
        html += `<div class="diff-unified-ins" data-diff-chunk="${ci}">${marked.parse(insText)}</div>`;
      }
    });
    renderA.innerHTML = html;

    // Word-level highlights inside mod blocks
    chunks.forEach((c, ci) => {
      if (c.op !== 'mod') return;
      const els = renderA.querySelectorAll(`[data-diff-chunk="${ci}"]`);
      if (els.length < 2) return;
      const elDel = els[0]; // diff-unified-del
      const elIns = els[1]; // diff-unified-ins
      if (elDel.tagName === 'PRE' || elIns.tagName === 'PRE') return;
      const { rangesA, rangesB } = this._wordDiff(elDel.textContent, elIns.textContent);
      this._applyWordHighlights(elDel, rangesA, 'diff-word-a');
      this._applyWordHighlights(elIns, rangesB, 'diff-word-b');
    });
  }

  _updateSwapButton() {
    // Shell owns the swap button — nothing to do here. The shell can
    // query getDiffSummary() or check paths to enable/disable its own button.
  }

  // ── Private: diff engine (LCS line diff) ──────────────────────────

  _lineDiff(textA, textB) {
    const a = textA.split('\n');
    const b = textB.split('\n');
    const m = a.length, n = b.length;
    const dp = Array.from({ length: m + 1 }, () => new Uint32Array(n + 1));
    for (let i = m - 1; i >= 0; i--)
      for (let j = n - 1; j >= 0; j--)
        dp[i][j] = a[i] === b[j] ? dp[i+1][j+1] + 1 : Math.max(dp[i+1][j], dp[i][j+1]);

    const raw = [];
    let i = 0, j = 0;
    while (i < m || j < n) {
      const last = raw[raw.length - 1];
      if (i < m && j < n && a[i] === b[j]) {
        if (last?.op === 'eq') { last.aEnd++; last.bEnd++; }
        else raw.push({ op: 'eq', aStart: i, aEnd: i+1, bStart: j, bEnd: j+1 });
        i++; j++;
      } else if (i < m && (j >= n || dp[i+1][j] >= dp[i][j+1])) {
        if (last?.op === 'del') { last.aEnd++; }
        else raw.push({ op: 'del', aStart: i, aEnd: i+1, bStart: j, bEnd: j });
        i++;
      } else {
        if (last?.op === 'ins') { last.bEnd++; }
        else raw.push({ op: 'ins', aStart: i, aEnd: i, bStart: j, bEnd: j+1 });
        j++;
      }
    }
    const chunks = [];
    for (let k = 0; k < raw.length; k++) {
      if (raw[k].op === 'del' && raw[k+1]?.op === 'ins') {
        chunks.push({ op: 'mod', aStart: raw[k].aStart, aEnd: raw[k].aEnd,
                                 bStart: raw[k+1].bStart, bEnd: raw[k+1].bEnd });
        k++;
      } else { chunks.push(raw[k]); }
    }
    return { a, b, chunks };
  }

  // ── Private: word-level diff ──────────────────────────────────────

  _tokenize(text) {
    const tokens = [];
    let pos = 0;
    for (const m of text.matchAll(/\S+/g)) {
      if (m.index > pos) tokens.push({ text: text.slice(pos, m.index), word: false });
      tokens.push({ text: m[0], word: true, start: m.index, end: m.index + m[0].length });
      pos = m.index + m[0].length;
    }
    if (pos < text.length) tokens.push({ text: text.slice(pos), word: false });
    return tokens;
  }

  _wordDiff(textA, textB) {
    const ta = this._tokenize(textA).filter(t => t.word);
    const tb = this._tokenize(textB).filter(t => t.word);
    const m = ta.length, n = tb.length;
    const dp = Array.from({ length: m + 1 }, () => new Uint32Array(n + 1));
    for (let i = m - 1; i >= 0; i--)
      for (let j = n - 1; j >= 0; j--)
        dp[i][j] = ta[i].text === tb[j].text ? dp[i+1][j+1] + 1
                                              : Math.max(dp[i+1][j], dp[i][j+1]);
    const rangesA = [], rangesB = [];
    let i = 0, j = 0;
    while (i < m || j < n) {
      if (i < m && j < n && ta[i].text === tb[j].text) { i++; j++; }
      else if (j >= n || (i < m && dp[i+1][j] >= dp[i][j+1])) {
        rangesA.push([ta[i].start, ta[i].end]); i++;
      } else {
        rangesB.push([tb[j].start, tb[j].end]); j++;
      }
    }
    return { rangesA, rangesB };
  }

  _applyWordHighlights(el, changedRanges, markClass) {
    if (!changedRanges.length) return;
    const walker = document.createTreeWalker(el, 4); // NodeFilter.SHOW_TEXT
    const nodes = [];
    let node, off = 0;
    while ((node = walker.nextNode())) {
      if (node.parentNode.tagName === 'CODE') { off += node.length; continue; }
      nodes.push({ node, start: off, end: off + node.length });
      off += node.length;
    }
    for (let i = nodes.length - 1; i >= 0; i--) {
      const { node: n, start, end } = nodes[i];
      const overlaps = changedRanges.filter(([rs, re]) => re > start && rs < end);
      if (!overlaps.length) continue;
      const segs = [];
      let pos = start;
      for (const [rs, re] of overlaps) {
        if (rs > pos) segs.push({ t: n.data.slice(pos - start, rs - start), ch: false });
        segs.push({ t: n.data.slice(Math.max(rs, start) - start, Math.min(re, end) - start), ch: true });
        pos = re;
      }
      if (pos < end) segs.push({ t: n.data.slice(pos - start), ch: false });
      const frag = document.createDocumentFragment();
      for (const s of segs) {
        if (s.ch) {
          const mark = document.createElement('mark');
          mark.className = markClass;
          mark.textContent = s.t;
          frag.appendChild(mark);
        } else {
          frag.appendChild(document.createTextNode(s.t));
        }
      }
      n.parentNode.replaceChild(frag, n);
    }
  }

  _annotateWordDiffs(chunks) {
    // Strip any existing word-diff marks before re-annotating
    this._shadow.querySelectorAll('mark.diff-word-a, mark.diff-word-b').forEach(m => {
      m.replaceWith(document.createTextNode(m.textContent));
    });
    chunks.forEach((c, ci) => {
      if (c.op !== 'mod') return;
      const elA = this._$('render-a').querySelector(`[data-diff-chunk="${ci}"]`);
      const elB = this._$('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
      if (!elA || !elB || elA.tagName === 'PRE' || elB.tagName === 'PRE') return;
      const { rangesA, rangesB } = this._wordDiff(elA.textContent, elB.textContent);
      this._applyWordHighlights(elA, rangesA, 'diff-word-a');
      this._applyWordHighlights(elB, rangesB, 'diff-word-b');
    });
  }

  // ── Private: canvas minimap ───────────────────────────────────────

  _drawDiffMap(totalA, totalB, chunks) {
    const canvas  = this._$('diff-map');
    const divider = this._$('divider');
    const h = divider.clientHeight, w = divider.clientWidth;
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext('2d');
    const mid = Math.floor(w / 2);
    ctx.fillStyle = '#ede7d9'; ctx.fillRect(0, 0, w, h);
    for (const c of chunks) {
      if (c.op === 'eq') continue;
      if (c.op === 'del' || c.op === 'mod') {
        const y1 = Math.floor((c.aStart / totalA) * h);
        const y2 = Math.ceil( (c.aEnd   / totalA) * h);
        ctx.fillStyle = '#ef4444';
        ctx.fillRect(1, y1, mid - 2, Math.max(2, y2 - y1));
      }
      if (c.op === 'ins' || c.op === 'mod') {
        const y1 = Math.floor((c.bStart / totalB) * h);
        const y2 = Math.ceil( (c.bEnd   / totalB) * h);
        ctx.fillStyle = '#22c55e';
        ctx.fillRect(mid + 1, y1, w - mid - 2, Math.max(2, y2 - y1));
      }
    }
    ctx.fillStyle = '#c8baa0'; ctx.fillRect(mid, 0, 1, h);
  }

  _annotateRendered(panel, content, chunks) {
    const render   = this._$(`render-${panel}`);
    const elements = [...render.children];
    const startKey = panel === 'a' ? 'aStart' : 'bStart';
    const endKey   = panel === 'a' ? 'aEnd'   : 'bEnd';
    elements.forEach(el => {
      el.removeAttribute('data-diff-chunk');
      el.classList.remove('diff-del', 'diff-ins');
    });
    const tokens = marked.lexer(content);
    let line = 0, elIdx = 0;
    for (const token of tokens) {
      const rawLines = token.raw.split('\n').length - 1;
      const tokenEnd = line + rawLines;
      if (token.type !== 'space') {
        const el = elements[elIdx++];
        if (el) {
          const endForCheck = Math.max(tokenEnd, line + 1);
          const ci = chunks.findIndex(c =>
            c.op !== 'eq' && c[startKey] < endForCheck && c[endKey] > line);
          if (ci >= 0) {
            el.dataset.diffChunk = ci;
            el.classList.add(panel === 'a' ? 'diff-del' : 'diff-ins');
          }
        }
      }
      line = tokenEnd;
    }
  }

  _updateDiffMap() {
    if (!this._panels.a.content || !this._panels.b.content) {
      this._lastChunks = [];
      this._scrollAnchors = [];
      return;
    }
    const { a, b, chunks } = this._lineDiff(this._panels.a.content, this._panels.b.content);
    this._lastChunks = chunks;
    this._lastTotalA = a.length;
    this._lastTotalB = b.length;

    if (this._viewMode === 'unified') {
      this._renderUnified(a, b, chunks);
    } else {
      this._drawDiffMap(a.length, b.length, chunks);
      this._annotateRendered('a', this._panels.a.content, chunks);
      this._annotateRendered('b', this._panels.b.content, chunks);
      this._annotateWordDiffs(chunks);
      this._buildScrollAnchors();
    }

    this._currentChunkIdx = -1;
    this.dispatchEvent(new CustomEvent('diff-updated', {
      bubbles: true,
      detail: { chunks, totalA: a.length, totalB: b.length },
    }));
  }

  // ── Private: diff navigation helpers ──────────────────────────────

  _nonEqIndices() {
    return this._lastChunks.reduce((acc, c, i) => {
      if (c.op !== 'eq') acc.push(i);
      return acc;
    }, []);
  }

  _chunkOutOfView(ci) {
    for (const p of ['a', 'b']) {
      const el = this._$(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
      if (!el) continue;
      const br = this._$(`body-${p}`).getBoundingClientRect();
      const er = el.getBoundingClientRect();
      return er.bottom < br.top || er.top > br.bottom;
    }
    return true;
  }

  _scrollToChunk(ci) {
    for (const p of ['a', 'b']) {
      const el = this._$(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
      if (!el) continue;
      const body = this._$(`body-${p}`);
      const delta = el.getBoundingClientRect().top - body.getBoundingClientRect().top - 24;
      if (Math.abs(delta) > 1) body.scrollBy({ top: delta, behavior: 'instant' });
    }
  }

  // ── Private: scroll anchors ───────────────────────────────────────

  _normHead(t) {
    return t.toLowerCase().replace(/[^\w\s]/g, '').trim().split(/\s+/).slice(0, 6).join(' ');
  }

  _scrollPercent(el) {
    const max = el.scrollHeight - el.clientHeight;
    return max <= 0 ? 0 : Math.min(1, el.scrollTop / max);
  }

  _interp(pos, fk, tk) {
    const a = this._scrollAnchors;
    if (a.length < 2) return pos;
    let i = a.length - 2;
    while (i > 0 && a[i][fk] > pos) i--;
    const lo = a[i], hi = a[i + 1];
    if (hi[fk] === lo[fk]) return lo[tk];
    return lo[tk] + Math.max(0, Math.min(1, (pos - lo[fk]) / (hi[fk] - lo[fk])))
                  * (hi[tk] - lo[tk]);
  }

  _buildScrollAnchors() {
    const bodyA = this._$('body-a'), bodyB = this._$('body-b');
    const maxA = bodyA.scrollHeight - bodyA.clientHeight;
    const maxB = bodyB.scrollHeight - bodyB.clientHeight;
    if (maxA <= 0 || maxB <= 0) { this._scrollAnchors = []; return; }

    const brA = bodyA.getBoundingClientRect();
    const brB = bodyB.getBoundingClientRect();

    const aHds = [...this._$('render-a').querySelectorAll('h2,h3,h4')]
      .map(el => ({ text: this._normHead(el.textContent),
                    pos: el.getBoundingClientRect().top - brA.top + bodyA.scrollTop }));
    const bHds = [...this._$('render-b').querySelectorAll('h2,h3,h4')]
      .map(el => ({ text: this._normHead(el.textContent),
                    pos: el.getBoundingClientRect().top - brB.top + bodyB.scrollTop }));

    const anchors = [{ a: 0, b: 0 }];
    const usedB = new Set();
    for (const ah of aHds) {
      let bi = bHds.findIndex((bh, i) => !usedB.has(i) && bh.text === ah.text);
      if (bi < 0) bi = bHds.findIndex((bh, i) => !usedB.has(i) && (
        (ah.text.length >= 18 && bh.text.startsWith(ah.text.slice(0, 18)))
        || (bh.text.length >= 18 && ah.text.startsWith(bh.text.slice(0, 18)))));
      if (bi >= 0) {
        usedB.add(bi);
        anchors.push({ a: ah.pos, b: bHds[bi].pos });
      }
    }
    anchors.push({ a: maxA, b: maxB });

    anchors.sort((x, y) => x.a - y.a);
    this._scrollAnchors = anchors.filter((an, i) => i === 0 || an.a > anchors[i - 1].a);
  }

  // ── Private: scroll sync ──────────────────────────────────────────

  _setupScrollSync() {
    const bodyA = this._$('body-a'), bodyB = this._$('body-b');
    bodyA.addEventListener('scroll', () => {
      if (!this._syncEnabled || this._syncing) return;
      this._syncing = true;
      bodyB.scrollTop = this._scrollAnchors.length >= 2
        ? this._interp(bodyA.scrollTop, 'a', 'b')
        : this._scrollPercent(bodyA) * (bodyB.scrollHeight - bodyB.clientHeight);
      requestAnimationFrame(() => requestAnimationFrame(() => { this._syncing = false; }));
    }, { passive: true });
    bodyB.addEventListener('scroll', () => {
      if (!this._syncEnabled || this._syncing) return;
      this._syncing = true;
      bodyA.scrollTop = this._scrollAnchors.length >= 2
        ? this._interp(bodyB.scrollTop, 'b', 'a')
        : this._scrollPercent(bodyB) * (bodyA.scrollHeight - bodyA.clientHeight);
      requestAnimationFrame(() => requestAnimationFrame(() => { this._syncing = false; }));
    }, { passive: true });
  }

  // ── Private: divider drag ─────────────────────────────────────────

  _setupDividerDrag() {
    const divider = this._$('divider');
    divider.addEventListener('mousedown', () => { this._dragging = true; });
    // These go on document since the mouse can leave the shadow root during drag
    document.addEventListener('mousemove', e => {
      if (!this._dragging) return;
      const r = this.getBoundingClientRect();
      const pct = Math.max(20, Math.min(80, (e.clientX - r.left) / r.width * 100));
      const pa = this._$('panel-a'), pb = this._$('panel-b');
      pa.style.flex = 'none'; pa.style.width = pct + '%';
      pb.style.flex = '1';    pb.style.width = '';
    });
    document.addEventListener('mouseup', () => { this._dragging = false; });
  }

  // ── Private: drop zones ───────────────────────────────────────────

  _setupDropZone(panel) {
    const body = this._$(`body-${panel}`);
    body.addEventListener('dragover', e => {
      e.preventDefault();
      e.stopPropagation();
      body.classList.add('drag-over');
    });
    body.addEventListener('dragleave', e => {
      e.preventDefault();
      e.stopPropagation();
      body.classList.remove('drag-over');
    });
    body.addEventListener('drop', e => {
      e.preventDefault();
      e.stopPropagation();
      body.classList.remove('drag-over');
      const file = e.dataTransfer.files[0];
      if (file && file.path) this.loadFile(panel, file.path);
    });
  }

  // ── Private: diff-map click ───────────────────────────────────────

  _onDiffMapClick(e) {
    if (!this._lastChunks.length) return;
    const canvas   = this._$('diff-map');
    const yFrac    = e.offsetY / canvas.height;
    const leftSide = e.offsetX < canvas.width / 2;
    const total    = leftSide ? this._lastTotalA : this._lastTotalB;
    const startKey = leftSide ? 'aStart' : 'bStart';
    const endKey   = leftSide ? 'aEnd'   : 'bEnd';
    const clickLine = Math.floor(yFrac * total);
    const ci = this._lastChunks.findIndex(c =>
      c.op !== 'eq' && c[startKey] <= clickLine && c[endKey] > clickLine);
    if (ci < 0) return;
    this._scrollToChunk(ci);
    this._currentChunkIdx = ci;
  }
}

// ── Registration ────────────────────────────────────────────────────
customElements.define('drafthouse-diff', DraftHouseDiff);
