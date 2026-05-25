// electron-tests/e2e/scroll-anchors.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('scroll anchors', () => {
  let app, window, jsErrors;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(
      process.env.TEST_FILE_A,
      process.env.TEST_FILE_B
    ));
  });

  test.afterAll(async () => {
    if (jsErrors) expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  // ── normHead ─────────────────────────────────────────────────────────

  test('normHead normalises punctuation, case, and caps at 6 words', async () => {
    const r1 = await window.evaluate(() => normHead('Hello, World!'));
    expect(r1).toBe('hello world');

    const r2 = await window.evaluate(
      () => normHead('One Two Three Four Five Six Seven Eight')
    );
    expect(r2).toBe('one two three four five six');

    const r3 = await window.evaluate(() => normHead('  Leading   Spaces  '));
    expect(r3).toBe('leading spaces');
  });

  // ── buildScrollAnchors ───────────────────────────────────────────────

  test('buildScrollAnchors with no shared headings returns boundary-only anchors', async () => {
    const filler = Array.from({ length: 100 },
      (_, i) => `Paragraph ${i + 1} of filler content for scroll testing.`
    ).join('\n\n');
    const contentA = `# Title A\n\n${filler}`;
    const contentB = `# Title B\n\n${filler}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());
    expect(anchors.length).toBe(2);
    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[1].a).toBeGreaterThan(0);
    expect(anchors[1].b).toBeGreaterThan(0);
  });

  test('buildScrollAnchors with shared headings returns sorted interior anchors', async () => {
    const shortFill = Array.from({ length: 30 },
      (_, i) => `Short filler paragraph ${i + 1}.`
    ).join('\n\n');
    const longFill = Array.from({ length: 60 },
      (_, i) => `Long filler paragraph ${i + 1}.`
    ).join('\n\n');

    const contentA =
      `# Doc A\n\n${shortFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;
    const contentB =
      `# Doc B\n\n${longFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());

    expect(anchors.length).toBeGreaterThan(2);

    for (let i = 1; i < anchors.length; i++) {
      expect(anchors[i].a).toBeGreaterThan(anchors[i - 1].a);
    }

    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[anchors.length - 1].a).toBeGreaterThan(0);
    expect(anchors[anchors.length - 1].b).toBeGreaterThan(0);
  });
});
