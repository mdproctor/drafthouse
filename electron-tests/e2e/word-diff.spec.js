// electron-tests/e2e/word-diff.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('word-level diff', () => {
  let app, window, jsErrors, originalContentB;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => {
    expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  // ── Word-diff E2E ────────────────────────────────────────────────────────────

  test('changed words are highlighted in mod chunk elements', async () => {
    const count = await window.evaluate(() =>
      document.querySelectorAll('#render-a mark.diff-word-a').length
    );
    expect(count).toBeGreaterThan(0);
  });

  test('unchanged words within changed blocks have no mark', async () => {
    const hasUnmarked = await window.evaluate(() => {
      const walker = document.createTreeWalker(
        document.getElementById('render-a'), 4 // NodeFilter.SHOW_TEXT
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

  test('known changed word is inside a mark in panel A', async () => {
    // Fixture A: "A rule engine evaluates business rules against a set of facts."
    // Fixture B: "A rule engine runs your business rules so developers..."
    // "evaluates" is in A but not B — must appear inside <mark.diff-word-a>
    const insideMark = await window.evaluate(() => {
      const marks = document.querySelectorAll('#render-a mark.diff-word-a');
      return Array.from(marks).some(m => m.textContent.includes('evaluates'));
    });
    expect(insideMark).toBe(true);
  });

  test('known unchanged word is not inside any mark', async () => {
    // "rule" appears in both A and B in the same position — must not be in a mark
    const insideMark = await window.evaluate(() => {
      const marks = document.querySelectorAll('#render-a mark');
      return Array.from(marks).some(m => /\brule\b/.test(m.textContent));
    });
    expect(insideMark).toBe(false);
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

  // ── tokenize edge cases (share the same JVM launch) ──────────────────────────

  test('tokenize of empty string returns empty array', async () => {
    const tokens = await window.evaluate(() => tokenize(''));
    expect(tokens).toEqual([]);
  });

  test('tokenize of whitespace-only string returns single non-word token', async () => {
    const tokens = await window.evaluate(() => tokenize('  '));
    expect(tokens).toEqual([{ text: '  ', word: false }]);
  });

  test('tokenize includes punctuation attached to word in the word token', async () => {
    // \S+ matches non-whitespace — punctuation attached to a word is part of that token
    const tokens = await window.evaluate(() => tokenize('hello,'));
    expect(tokens).toEqual([{ text: 'hello,', word: true, start: 0, end: 6 }]);
  });

  test('tokenize handles multiple consecutive spaces between words', async () => {
    const tokens = await window.evaluate(() => tokenize('a  b'));
    expect(tokens).toEqual([
      { text: 'a', word: true, start: 0, end: 1 },
      { text: '  ', word: false },
      { text: 'b', word: true, start: 3, end: 4 },
    ]);
  });
});
