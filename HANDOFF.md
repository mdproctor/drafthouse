# Handover — 2026-05-22

**Branch:** `main` (clean)

## Current state

All four diff viewer completeness features are shipped and merged to main. The diff viewer is fully functional: swap panels, next/prev navigation with position counter, diff summary label, and word-level word highlights within changed blocks.

33 Playwright E2E tests passing (2 scroll-sync skip when content fits viewport). JVM cold-start flakiness on full suite run is a known limitation — see issue #6 and the new `docs/protocols/playwright-jvm-warmup.md`.

GitHub repo: `mdproctor/md-compare`. Issues #2, #7, #9, #11 closed. Epic #1 nearly done — scroll sync (#3) remains.

## What was built this session

- **Swap panels** — `panels = { a, b }` state object replaces scattered `filePaths`/`contents`/`watcherRefs`; ⇄ Swap button in topbar
- **Next/prev diff navigation** — `n`/`p` keyboard + ↑↓ buttons; `N/M` counter; viewport-recalibrating; minimap click fixed to scroll both panels
- **Diff summary** — `~N −N +N` topbar label with CSS hover tooltip; `updateDiffSummary()` with ResizeObserver early-return guard
- **Word-level diff** — DOM-walking LCS on `textContent`; `TreeWalker` splits text nodes in reverse order; preserves inline formatting; skips `<pre>` and inline `<code>`; ResizeObserver mark-stripping fix

## Immediate next step

Start word-level diff feature enhancement OR scroll sync improvement:
- Scroll sync (issue #3): heading-anchor interpolation with % fallback — design is clear from earlier brainstorm
- Or: `work-start` for a new feature

## What's left

- Issue #3 — improved scroll sync · L · Med
- Issue #4 — Playwright test hardening (pageerror listener, fixture guard) · S · Low
- Issue #5 — syncPanelDOM re-parse efficiency, loadFile redundancy · S · Low
- Issue #6 — JVM cold-start shared-JVM fix (structural) · M · Med
- Issue #8 — nav test direction assertion, global-setup deduplication · S · Low
- Issue #10 — diff-summary test specificity, tokenize shape · XS · Low
- Issue #12 — word-diff test specificity, tokenize non-word shape · XS · Low
- Branch `issue-11-word-level-diff` still exists locally and remotely — not yet deleted (awaiting explicit permission)

## What's next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #3 | Scroll sync — heading anchors with % fallback | L | Med | Design brainstormed; spec not written yet |
| Phase 2 | Wire POST /api/critique to Claude API | XL | High | Requires API key and server changes |

## References

| Context | Where |
|---|---|
| Feature backlog | `docs/FEATURES.md` |
| Architecture + run commands | `CLAUDE.md` |
| JVM warmup protocol | `docs/protocols/playwright-jvm-warmup.md` |
| Word diff implementation | `index.html` — `tokenize()`, `wordDiff()`, `applyWordHighlights()`, `annotateWordDiffs()` |
| Design specs | `docs/superpowers/specs/` |
| GitHub repo | `mdproctor/md-compare` |
