# Scroll Sync — Heading Anchors with Percentage Fallback

**Issue:** #3  
**Branch:** `issue-3-scroll-sync-anchors`  
**Date:** 2026-05-25

## Problem

Current scroll sync is pure percentage: scroll A to 40% → apply 40% to B. This breaks when A and B have different content distributions — a long preamble in B means the user scrolls to a shared heading in A but B lands in the wrong section entirely.

## Approach

Piecewise linear interpolation between matched heading pairs, with boundary anchors that degrade to percentage sync when no headings match. Direct port of Sparge's `buildScrollAnchors()` / `interp()` pattern, adapted from same-doc-two-views to two-independent-documents.

## State

One new module-level variable alongside the existing state block:

```js
let scrollAnchors = [];  // [{a: scrollTopPx, b: scrollTopPx}], sorted by .a
```

Pixel values in content-absolute space (not percentages). Invariants: sorted by `.a`, no two entries share the same `.a` value.

## Core Functions

### `normHead(t)`

```js
function normHead(t) {
  return t.toLowerCase().replace(/[^\w\s]/g, '').trim().split(/\s+/).slice(0, 6).join(' ');
}
```

Lowercase, strip punctuation, first 6 words. Normalises minor punctuation and capitalisation differences between heading pairs.

### `buildScrollAnchors()`

```js
function buildScrollAnchors() {
  const bodyA = $('body-a'), bodyB = $('body-b');
  const maxA = bodyA.scrollHeight - bodyA.clientHeight;
  const maxB = bodyB.scrollHeight - bodyB.clientHeight;
  if (maxA <= 0 || maxB <= 0) { scrollAnchors = []; return; }

  const brA = bodyA.getBoundingClientRect();
  const brB = bodyB.getBoundingClientRect();

  const aHds = [...$('render-a').querySelectorAll('h2,h3,h4')]
    .map(el => ({ text: normHead(el.textContent),
                  pos: el.getBoundingClientRect().top - brA.top + bodyA.scrollTop }));
  const bHds = [...$('render-b').querySelectorAll('h2,h3,h4')]
    .map(el => ({ text: normHead(el.textContent),
                  pos: el.getBoundingClientRect().top - brB.top + bodyB.scrollTop }));

  const anchors = [{ a: 0, b: 0 }];
  for (const ah of aHds) {
    const match = bHds.find(bh => bh.text === ah.text)
               ?? bHds.find(bh => bh.text.startsWith(ah.text.slice(0, 18))
                                || ah.text.startsWith(bh.text.slice(0, 18)));
    if (match) anchors.push({ a: ah.pos, b: match.pos });
  }
  anchors.push({ a: maxA, b: maxB });

  anchors.sort((x, y) => x.a - y.a);
  scrollAnchors = anchors.filter((an, i) => i === 0 || an.a > anchors[i - 1].a);
}
```

**Matching:** exact text first, then 18-char prefix fallback (either direction). Handles headings that are slightly reworded or split across structural revisions. Unmatched headings are skipped — surrounding anchors carry the interpolation load.

**Boundary anchors:** `{a:0, b:0}` and `{a:maxA, b:maxB}` always present. With no heading matches, the two-anchor list is mathematically equivalent to percentage sync — no special-case fallback needed.

**Deduplication:** sort by `.a`, drop entries where `an.a === previous.a`. Prevents degenerate segments when a prefix match causes two A-headings to map to the same B-position.

**Position computation:** `el.getBoundingClientRect().top - body.getBoundingClientRect().top + body.scrollTop` gives content-absolute position, scroll-independent.

**Guard:** if either panel doesn't scroll (`max <= 0`), clear anchors and return. Sync handlers are already no-ops in this case.

### `interp(pos, fk, tk)`

```js
function interp(pos, fk, tk) {
  const a = scrollAnchors;
  if (a.length < 2) return pos;
  let i = a.length - 2;
  while (i > 0 && a[i][fk] > pos) i--;
  const lo = a[i], hi = a[i + 1];
  if (hi[fk] === lo[fk]) return lo[tk];
  return lo[tk] + Math.max(0, Math.min(1, (pos - lo[fk]) / (hi[fk] - lo[fk])))
                * (hi[tk] - lo[tk]);
}
```

Piecewise linear between surrounding anchor pair. `Math.max(0, Math.min(1, ...))` clamps against float imprecision at segment edges. Call as `interp(bodyA.scrollTop, 'a', 'b')` for A→B, `interp(bodyB.scrollTop, 'b', 'a')` for B→A.

### `getScrollAnchors()` (test API)

```js
function getScrollAnchors() { return scrollAnchors; }
```

Named function declaration so it binds on the global object and is reachable from Playwright's `page.evaluate()`.

## Integration Changes

### `setupScrollSync()`

Replace `applyPercent(bodyX, scrollPercent(bodyY))` with `interp`:

```js
bodyA.addEventListener('scroll', () => {
  if (!syncEnabled || syncing) return;
  syncing = true;
  bodyB.scrollTop = scrollAnchors.length >= 2
    ? interp(bodyA.scrollTop, 'a', 'b')
    : scrollPercent(bodyA) * (bodyB.scrollHeight - bodyB.clientHeight);
  requestAnimationFrame(() => requestAnimationFrame(() => { syncing = false; }));
}, { passive: true });
// symmetric for bodyB
```

The `length < 2` branch is belt-and-suspenders; boundary anchors make it unreachable under normal operation.

### `toggleSync()`

```js
function toggleSync() {
  syncEnabled = !syncEnabled;
  $('btn-sync').classList.toggle('active', syncEnabled);
  if (syncEnabled) {
    $('body-b').scrollTop = scrollAnchors.length >= 2
      ? interp($('body-a').scrollTop, 'a', 'b')
      : scrollPercent($('body-a')) * ($('body-b').scrollHeight - $('body-b').clientHeight);
  }
}
```

### `updateDiffMap()`

Two additions:

1. Early-return path: add `scrollAnchors = [];` before returning (when either panel is empty).
2. Success path: call `buildScrollAnchors()` after all DOM mutations (`annotateWordDiffs` last), before `currentChunkIdx = -1`.

`swapPanels()` and the `ResizeObserver` both call `updateDiffMap()` already — no additional wiring needed for those rebuild triggers.

## Testing

New spec: `electron-tests/e2e/scroll-anchors.spec.js` (one describe, per protocol).

**Logic-level tests (via `page.evaluate`, no scrolling):**

Content injection: `evaluate(() => { renderMarkdown('a', contentA); renderMarkdown('b', contentB); })`. `renderMarkdown` triggers `updateDiffMap()` → `buildScrollAnchors()` automatically.

- `normHead` strips punctuation, lowercases, caps at 6 words
- `buildScrollAnchors` with no shared headings → `[{a:0,b:0}, {a:maxA,b:maxB}]`
- `buildScrollAnchors` with shared headings → interior anchors present, `.a` strictly increasing
- `interp` with `length < 2` → returns `pos` unchanged
- `interp` with `hi[fk] === lo[fk]` → returns `lo[tk]` (no divide-by-zero)

**Behavioural test (scroll divergence):**

Fixtures: A has short preamble then `## Anchor` then padding. B has long preamble then `## Anchor` then short tail. In A, `## Anchor` sits near top (~15% scroll). In B it sits near bottom (~75% scroll).

Test: inject fixtures via `renderMarkdown`, then `evaluate(() => { $('body-a').scrollTop = $('render-a').querySelector('h2').offsetTop; $('body-a').dispatchEvent(new Event('scroll')); })`, wait two rAF cycles (matching the `syncing` guard), read B's `scrollTop`. Assert B's position is closer to B's heading's `offsetTop` than to 15% of B's max scroll.

**Regression:** existing scroll-sync tests in `regression.spec.js` pass unchanged — boundary-only anchors reproduce percentage behaviour exactly.

## Acceptance Criteria (from issue)

- Scroll sync uses heading anchors when both panels share at least one matching heading
- Falls back gracefully to percentage when no headings match
- Anchors rebuild automatically when files reload or are swapped
- Existing scroll-sync Playwright tests continue to pass
- No visible scroll judder when crossing anchor boundaries
