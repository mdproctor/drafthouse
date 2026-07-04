# Feature Backlog

Planned and completed features for DraftHouse.

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
- [x] Playwright E2E tests: diff (happy path, rendering, scroll sync, swap, nav, summary, word diff, legend) + debate panel (18) + review tracker (16) + cross-panel (4) + sub-agent (2) + doc-picker (1) = 379 total
- [x] ~~Phase 2 critique panel placeholder~~ — superseded by `<drafthouse-debate>` Web Component (#51)

## Done — recent

- [x] **Swap panels (A↔B)** — ⇄ button in topbar swaps paths, content, and labels atomically; disabled until both panels loaded
- [x] **Next/prev diff navigation** — ↑↓ topbar buttons + `n`/`p` keyboard; `N/M` counter; viewport-recalibrating nav; minimap click fixed to scroll both panels
- [x] **Diff summary** — `~N −N +N` topbar label shows modified/deleted/inserted block counts; CSS hover tooltip explains symbols
- [x] **Word-level diff** — changed words highlighted within mod blocks via DOM-walking LCS diff; preserves inline formatting (bold, italic, code, links)
- [x] **Web Component panel architecture** (#51) — decomposed monolithic index.html into Shadow DOM Web Components (`<drafthouse-diff>`, `<drafthouse-debate>`, `<drafthouse-review-tracker>`) with PanelRegistry and DebateEventBus. Targets `@casehub/ui` Component model.
- [x] **Debate event rendering** (#51) — SSE debate events rendered as conversation feed grouped by round, with visual treatment per EntryType (colour-coded borders, priority badges, scope tags)
- [x] **Review point tracker** (#51) — status-derived checklist with progress bar, agent trail, strikethrough on AGREED/DECLINED, show/hide resolved filter
- [x] **Workspace shell** (#51) — fixed-slot layout with panel toggles, session discovery polling, cross-panel point-selected event routing
- [x] **Keyboard shortcuts overlay** (#63) — `?` toggles overlay; Escape/backdrop dismiss; INPUT/TEXTAREA/SELECT/contenteditable guard with shadow DOM traversal
- [x] **Multi-document working sets** (#59) — `DocumentSet` on `DebateSession`; `add_document`, `remove_document`, `list_documents`, `set_comparison` MCP tools; REST + SSE for browser sync; topbar document dropdown
- [x] **Export debate summary** (#65) — `export_debate_summary` MCP tool writes summary to markdown file; same render path as `get_debate_summary`
- [x] **Multi-LLM reviewer registry** (#62) — `DraftHouseReviewerRegistry` (in-memory AgentRegistry), `ReviewerDescriptorSeeder` (4 built-in reviewer personas), `SimplePromptRenderer`, `agentId` on DebateSession/SessionInfo, `list_reviewers` and `get_reviewer_instructions` MCP tools, reviewer resolution in `start_debate` and `get_debate_summary`
- [x] **Document badge dropdown** (#85) — `<drafthouse-doc-picker>` Shadow DOM custom element in topbar; A/B slot assignment buttons per document; POST to `/api/debate/{id}/comparison`; pending state for first-time assignment; live updates via `documents-changed`/`comparison-changed` events
- [x] **WebSocket reconnection tests** (#88) — integration tests for full reconnection catch-up, stale subscription after session end, concurrent push from multiple producers (CyclicBarrier)

## Planned

### Diff viewer completeness
- [ ] **Improved scroll sync** — heading-anchor interpolation with percentage fallback (issue #3)

### Phase 2 — DraftHouse MVP

See research spec: `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`

- [ ] MCP tool surface (start_review, push_revision, get_cursor_context, get_diff, end_review)
- [ ] Qhorus channels for conversation threading
- [ ] Single LLM reviewer via LangChain4j
- [ ] Git worktree versioning (JGit)
- [x] Quarkus Playwright E2E tests — debate panel, review tracker, cross-panel coordination (#55)

### Post-MVP

- [ ] Selection-scoped conversation channels
- [x] Multi-LLM reviewers with personality library (#62) — registry, seeder, MCP tools, session discovery
- [ ] ReviewStrategy SPI
- [ ] GraalVM native image
- [ ] Unified diff view mode (#66)
