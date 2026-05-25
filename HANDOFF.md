# Handover — 2026-05-25 (session 2)

**Branch:** `main` (clean)

## Current state

Issue #3 (scroll sync) complete — heading-anchor interpolation with percentage
fallback, two infrastructure bug fixes, code review fixes. Branch rebased onto
main and closed. 54 Playwright E2E tests passing, 2 scroll-sync skipped.

## What was built this session

- **Scroll-anchor sync** — `normHead`, `buildScrollAnchors`, `interp` for
  piecewise linear interpolation between matched heading pairs. Boundary anchors
  at `{0,0}` and `{maxA,maxB}` degrade to percentage sync with no special case.
  Matching: exact text first, 18-char prefix fallback (requires heading ≥18 chars),
  B-heading consumption tracking via `Set` to prevent duplicates.
- **RAF polling fix** — Chromium suppresses `requestAnimationFrame` in hidden
  Electron windows (`show: false`). Switched `waitForFunction` to `polling: 100`
  in `helpers.js` and `global-setup.js`.
- **ready-to-show race fix** — `mainWindow.once('ready-to-show', ...)` registered
  after `await loadURL()` misses the event when CDN resources cause first paint
  before `did-finish-load`. Moved handler registration before `loadURL` in `main.js`.
- **Code review fixes** — prefix length guard (≥18 chars), `usedB` Set for
  B-heading consumption, removed dead `applyPercent` function.
- **8 new tests** in `scroll-anchors.spec.js` — normHead, boundary anchors,
  interior anchors, interp edge cases, prefix guard, B-consumption, behavioural
  sync divergence.

## Garden entries

Two gotchas submitted and pushed to `~/.hortora/garden/electron/`:
- `GE-20260525-5f6efe` — Playwright `waitForFunction` hangs in hidden Electron windows (RAF suppression)
- `GE-20260525-6accc3` — Electron `ready-to-show` handler missed after `await loadURL`

## Blog entries

- `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md` (from prior session)
- `blog/2026-05-25-mdp02-scroll-sync-two-invisible-bugs.md` (this session)

## What's left

- Issue #6 — JVM cold-start shared-JVM fix (structural) · M · Med
- Branch `issue-11-word-level-diff` still exists locally and remotely —
  delete pending explicit permission
- Phase 2 — wire POST /api/critique to Claude API · XL · High

## References

| Context | Where |
|---|---|
| Feature backlog | `docs/FEATURES.md` |
| Scroll-anchor design spec | `docs/superpowers/specs/2026-05-25-scroll-sync-anchors-design.md` |
| Playwright protocols | `docs/protocols/` (4 protocols) |
| Blog entries | `blog/` (2 entries) |
| GitHub repo | `mdproctor/md-compare` |
