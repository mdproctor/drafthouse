# Handover — 2026-05-25

**Branch:** `main` (clean)

## Current state

S/XS cleanup batch complete — issues #4, #5, #8, #10, #12 closed. Also
discovered and fixed a pre-existing bug: `annotateRendered` was silently
skipping all paragraphs (marked.js v9 paragraph tokens have `rawLines=0`),
so word-level diff only ever highlighted headings. Fixed with
`endForCheck = Math.max(tokenEnd, line + 1)`.

46 Playwright E2E tests passing (was 33), 2 scroll-sync skipped.
3 new Playwright protocols in `docs/protocols/`. Blog routing fixed —
md-compare entries now go to `blog/` in this repo.

## What was built this session

- **Test hardening** — `launchApp` returns `jsErrors`; path guard on
  `undefined` args; global pageerror listener; `if (jsErrors)` guard in
  all spec `afterAll` blocks
- **Code quality** — label input no longer calls `syncPanelDOM` on keystroke;
  `loadFile` redundant DOM lines removed; `onInitConfig` made async with await
- **Test specificity** — diff-summary regex tightened; tokenize unit + edge
  case tests; fixture-based word assertions in word-diff
- **annotateRendered bug fix** — paragraphs now correctly tagged for word-diff
- **Blog routing** — `blog/` directory added to project; `blog-routing.yaml`
  updated with `md-compare` destination

## Immediate next step

`work-start` for scroll sync (issue #3): heading-anchor interpolation with
% fallback — design already brainstormed, no spec written yet.

## What's left

- Issue #3 — improved scroll sync · L · Med
- Issue #6 — JVM cold-start shared-JVM fix (structural) · M · Med
- Branch `issue-11-word-level-diff` still exists locally and remotely —
  delete pending explicit permission

## What's next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #3 | Scroll sync — heading anchors with % fallback | L | Med | Design brainstormed; no spec yet |
| Phase 2 | Wire POST /api/critique to Claude API | XL | High | Requires API key + server changes |

## References

| Context | Where |
|---|---|
| Feature backlog | `docs/FEATURES.md` |
| Playwright protocols | `docs/protocols/` (4 protocols now) |
| Blog entry | `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md` |
| annotateRendered fix | `index.html` — `annotateRendered()` |
| GitHub repo | `mdproctor/md-compare` |
