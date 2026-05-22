# Feature Backlog

Planned and completed features for md-compare.

## Done

- [x] Two-panel rendered markdown viewer (marked.js + highlight.js)
- [x] LCS line diff with canvas minimap (red=A-side, green=B-side)
- [x] Inline block diff markers (border-top/bottom on changed blocks, `.diff-del` / `.diff-ins`)
- [x] Click-to-scroll on minimap bars
- [x] Scroll sync toggle (percentage-based, `Cmd+S`)
- [x] Draggable panel divider
- [x] File picker (native dialog + drag-and-drop)
- [x] Editable panel labels
- [x] Live file watch (SSE EventSource, ref-counted per path)
- [x] Quarkus backend (FileResource, WatchResource, UiResource, CritiqueResource stub)
- [x] `java-server.js` process manager with crash recovery
- [x] Playwright E2E tests: happy path (8) + regression (4) + swap panels (7) + nav (7) + diff summary (4)
- [x] Phase 2 critique panel placeholder (layout ready, content empty)

## Done — recent

- [x] **Swap panels (A↔B)** — ⇄ button in topbar swaps paths, content, and labels atomically; disabled until both panels loaded
- [x] **Next/prev diff navigation** — ↑↓ topbar buttons + `n`/`p` keyboard; `N/M` counter; viewport-recalibrating nav; minimap click fixed to scroll both panels
- [x] **Diff summary** — `~N −N +N` topbar label shows modified/deleted/inserted block counts; CSS hover tooltip explains symbols

## Planned

### Diff viewer completeness
- [ ] **Improved scroll sync** — heading-anchor interpolation with percentage fallback (issue #3)
- [ ] **Diff legend** — small colour key (red = A only, green = B only) somewhere unobtrusive
- [ ] **Word-level diff** — within a changed block, highlight the exact words that differ (not just the whole paragraph)

### Phase 2 — LLM Critique

- [ ] Wire `POST /api/critique` to Claude API (streaming)
- [ ] Critique panel content: "what changed, why it's better/worse"
- [ ] LangChain4j integration in Quarkus server
- [ ] Streaming prose display in the critique panel

### Phase 3 — Interactive critique (longer term)

- [ ] Select a passage → request inline rewrite
- [ ] Generated version appears in right pane
