# Word-Level Diff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Highlight the specific changed words within each `mod` diff block, using DOM-walking to preserve inline formatting.

**Architecture:** Word-level LCS diff on `el.textContent` produces changed character ranges; `TreeWalker` splits existing text nodes at those boundaries and wraps changed portions in `<mark class="diff-word-a/b">`. Only `mod` chunks receive word highlights; `del`/`ins` and `<pre>` blocks use block-level styling only. `annotateWordDiffs(chunks)` is called from `updateDiffMap()` after `annotateRendered()`. Cleanup is automatic: `syncPanelDOM` replaces `innerHTML` on every reload.

**Tech Stack:** Vanilla JS (ES2022), `document.createTreeWalker`, `DocumentFragment`, Playwright (E2E tests)

---

## File Map

| File | Change |
|---|---|
| `index.html` | Add `tokenize()`, `wordDiff()`, `applyWordHighlights()`, `annotateWordDiffs()`; update `updateDiffMap()` |
| `styles.css` | Add `mark.diff-word-a` and `mark.diff-word-b` rules |
| `electron-tests/e2e/word-diff.spec.js` | New — 5 Playwright E2E tests |

---

## Task 1: Write Failing Playwright Tests

**Files:**
- Create: `electron-tests/e2e/word-diff.spec.js`

- [ ] **Step 1.1: Create the test file**

```js
// electron-tests/e2e/word-diff.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('word-level diff', () => {
  let app, window, originalContentB;

  test.beforeAll(async () => {
    ({ app, window } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('changed words are highlighted in mod chunk elements', async () => {
    const count = await window.evaluate(() =>
      document.querySelectorAll('#render-a mark.diff-word-a').length
    );
    expect(count).toBeGreaterThan(0);
  });

  test('unchanged words within changed blocks have no mark', async () => {
    const hasUnmarked = await window.evaluate(() => {
      const walker = document.createTreeWalker(
        document.getElementById('render-a'), NodeFilter.SHOW_TEXT
      );
      let node;
      while ((node = walker.nextNode())) {
        if (node.parentNode.tagName !== 'MARK' && node.data.trim().length > 0) return true;
      }
      return false;
    });
    expect(hasUnmarked).toBe(true);
  });

  test('pre blocks have no word highlights', async () => {
    const count = await window.evaluate(() =>
      document.querySelectorAll('#render-a pre mark, #render-b pre mark').length
    );
    expect(count).toBe(0);
  });

  test('word highlights appear on both panels for mod chunks', async () => {
    const countB = await window.evaluate(() =>
      document.querySelectorAll('#render-b mark.diff-word-b').length
    );
    expect(countB).toBeGreaterThan(0);
  });

  test('word highlights persist and update correctly after swap', async () => {
    await window.evaluate(() => swapPanels());
    const countA = await window.evaluate(() =>
      document.querySelectorAll('#render-a mark.diff-word-a').length
    );
    const countB = await window.evaluate(() =>
      document.querySelectorAll('#render-b mark.diff-word-b').length
    );
    expect(countA).toBeGreaterThan(0);
    expect(countB).toBeGreaterThan(0);
    await window.evaluate(() => swapPanels()); // restore
  });
});
```

- [ ] **Step 1.2: Run to confirm all 5 fail**

```bash
cd /Users/mdproctor/claude/md-compare && ./node_modules/.bin/playwright test electron-tests/e2e/word-diff.spec.js --reporter=list
```

Expected: all 5 fail — `mark.diff-word-a` selector finds nothing (functions don't exist yet).

- [ ] **Step 1.3: Commit red tests**

```bash
git -C /Users/mdproctor/claude/md-compare add electron-tests/e2e/word-diff.spec.js
git -C /Users/mdproctor/claude/md-compare commit -m "test(word-diff): add failing Playwright E2E tests

Refs #11"
```

---

## Task 2: Implement Word-Level Diff

**Files:**
- Modify: `index.html`
- Modify: `styles.css`

- [ ] **Step 2.1: Add word-diff CSS to `styles.css` (append after the `#diff-summary::after` block)**

```css
mark.diff-word-a { background: rgba(239,68,68,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }
mark.diff-word-b { background: rgba(34,197,94,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }
```

- [ ] **Step 2.2: Add `tokenize()` to `index.html` — insert after the `// ── Critique panel stub` comment and before `function toggleCritique()`**

```js
// ── Word-level diff ──────────────────────────────────────────────────
function tokenize(text) {
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
```

- [ ] **Step 2.3: Add `wordDiff()` immediately after `tokenize()`**

```js
function wordDiff(textA, textB) {
  const ta = tokenize(textA).filter(t => t.word);
  const tb = tokenize(textB).filter(t => t.word);
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
```

- [ ] **Step 2.4: Add `applyWordHighlights()` immediately after `wordDiff()`**

```js
function applyWordHighlights(el, changedRanges, markClass) {
  if (!changedRanges.length) return;
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const nodes = [];
  let node, off = 0;
  while ((node = walker.nextNode())) {
    nodes.push({ node, start: off, end: off + node.length });
    off += node.length;
  }
  for (let i = nodes.length - 1; i >= 0; i--) {
    const { node, start, end } = nodes[i];
    const overlaps = changedRanges.filter(([rs, re]) => re > start && rs < end);
    if (!overlaps.length) continue;
    const segs = [];
    let pos = start;
    for (const [rs, re] of overlaps) {
      if (rs > pos) segs.push({ t: node.data.slice(pos - start, rs - start), ch: false });
      segs.push({ t: node.data.slice(Math.max(rs, start) - start, Math.min(re, end) - start), ch: true });
      pos = re;
    }
    if (pos < end) segs.push({ t: node.data.slice(pos - start), ch: false });
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
    node.parentNode.replaceChild(frag, node);
  }
}
```

- [ ] **Step 2.5: Add `annotateWordDiffs()` immediately after `applyWordHighlights()`**

```js
function annotateWordDiffs(chunks) {
  chunks.forEach((c, ci) => {
    if (c.op !== 'mod') return;
    const elA = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`);
    const elB = $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
    if (!elA || !elB || elA.tagName === 'PRE' || elB.tagName === 'PRE') return;
    const { rangesA, rangesB } = wordDiff(elA.textContent, elB.textContent);
    applyWordHighlights(elA, rangesA, 'diff-word-a');
    applyWordHighlights(elB, rangesB, 'diff-word-b');
  });
}
```

- [ ] **Step 2.6: Update `updateDiffMap()` to call `annotateWordDiffs(chunks)` after `annotateRendered`**

Find in `updateDiffMap()`:
```js
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
  currentChunkIdx = -1;
```

Replace with:
```js
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
  annotateWordDiffs(chunks);
  currentChunkIdx = -1;
```

- [ ] **Step 2.7: Run full test suite**

```bash
cd /Users/mdproctor/claude/md-compare && ./node_modules/.bin/playwright test --reporter=list
```

Expected: 33 pass (28 existing + 5 new), 2 skip.

Common failure modes if tests fail:
- `mark.diff-word-a` not found → check `annotateWordDiffs` is called after `annotateRendered` in `updateDiffMap`, and CSS class matches `'diff-word-a'`
- `pre mark` test fails → check `elA.tagName === 'PRE'` guard in `annotateWordDiffs`
- Swap test fails → `swapPanels()` calls `updateDiffMap()` which re-runs `annotateWordDiffs` — verify the chain

- [ ] **Step 2.8: Commit**

```bash
git -C /Users/mdproctor/claude/md-compare add index.html styles.css
git -C /Users/mdproctor/claude/md-compare commit -m "feat(word-diff): highlight changed words within mod diff blocks

DOM-walking word-level LCS diff within annotated mod blocks.
Preserves inline formatting (bold, italic, code, links) by splitting
text nodes at changed-word boundaries rather than replacing innerHTML.
pre blocks and del/ins chunks use block-level styling only.

Closes #11"
git -C /Users/mdproctor/claude/md-compare push -u origin issue-11-word-level-diff
```
