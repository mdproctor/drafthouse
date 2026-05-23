// electron-tests/e2e/diff-summary.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('diff summary', () => {
  let app, window, jsErrors, originalContentB;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => {
    expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  // ── Diff summary E2E ────────────────────────────────────────────────────────

  test('summary shows breakdown after loading differing files', async () => {
    const text = await window.evaluate(() => document.getElementById('diff-summary').textContent);
    // Must be non-empty and match the full summary structure: ~N, −N, +N (one or more parts)
    expect(text.length).toBeGreaterThan(0);
    expect(text).toMatch(/^(~\d+)?( ?−\d+)?( ?\+\d+)?$/);
    expect(text).toMatch(/[~−+]/); // at least one part present
  });

  test('summary has tooltip attribute when non-empty', async () => {
    const tooltip = await window.evaluate(() => document.getElementById('diff-summary').dataset.tooltip);
    expect(tooltip).toContain('modified');
  });

  test('summary clears when files are identical', async () => {
    await window.evaluate(() => { panels.b.content = panels.a.content; updateDiffMap(); });
    const text = await window.evaluate(() => document.getElementById('diff-summary').textContent);
    expect(text).toBe('');
    const tooltip = await window.evaluate(() => document.getElementById('diff-summary').dataset.tooltip);
    expect(tooltip).toBe('');
    // Restore
    await window.evaluate(c => { panels.b.content = c; updateDiffMap(); }, originalContentB);
  });

  test('summary total chunk count is preserved after swap', async () => {
    const before = await window.evaluate(() => document.getElementById('diff-summary').textContent);
    await window.evaluate(() => swapPanels());
    const after = await window.evaluate(() => document.getElementById('diff-summary').textContent);
    const sum = s => (s.match(/\d+/g) || []).reduce((a, b) => a + +b, 0);
    expect(sum(after)).toBe(sum(before));
    // Restore
    await window.evaluate(() => swapPanels());
  });

  // ── tokenize unit tests (share the same JVM launch) ─────────────────────────

  test('tokenize splits word and non-word tokens with correct shape', async () => {
    const tokens = await window.evaluate(() => tokenize('hello world'));
    expect(tokens).toEqual([
      { text: 'hello', word: true, start: 0, end: 5 },
      { text: ' ', word: false },
      { text: 'world', word: true, start: 6, end: 11 },
    ]);
  });

  test('tokenize preserves leading whitespace as non-word token', async () => {
    const tokens = await window.evaluate(() => tokenize('  hi'));
    expect(tokens[0]).toEqual({ text: '  ', word: false });
    expect(tokens[1]).toMatchObject({ text: 'hi', word: true });
  });

  test('tokenize preserves trailing whitespace as non-word token', async () => {
    const tokens = await window.evaluate(() => tokenize('hi  '));
    expect(tokens[0]).toMatchObject({ text: 'hi', word: true });
    expect(tokens[1]).toEqual({ text: '  ', word: false });
  });

  test('tokenize word tokens carry start and end, non-word tokens do not', async () => {
    const tokens = await window.evaluate(() => tokenize('a b'));
    const [a, space, b] = tokens;
    expect(a).toHaveProperty('start');
    expect(a).toHaveProperty('end');
    expect(space).not.toHaveProperty('start');
    expect(space).not.toHaveProperty('end');
    expect(b).toHaveProperty('start');
    expect(b).toHaveProperty('end');
  });
});
