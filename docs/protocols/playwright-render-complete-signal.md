---
id: PP-20260529-99002c
title: "Use [data-diff-chunk] as the Playwright render-complete signal for DraftHouse UI"
type: rule
scope: repo
applies_to: "All Playwright E2E tests in server/src/test/java/io/casehub/drafthouse/e2e/"
severity: important
refs:
  - docs/superpowers/specs/2026-05-29-quarkus-playwright-e2e-design.md
violation_hint: "Waiting for content elements (p, h2, pre) races with diff annotation — tests may assert before annotateRendered() has run, finding zero .diff-del/.diff-ins/.diff-word-a marks even though the page rendered."
created: 2026-05-29
---

`PlaywrightFixtures.waitForRender(page)` waits for `[data-diff-chunk]`, the attribute set by `annotateRendered()` inside `updateDiffMap()` after both markdown rendering and LCS diff annotation are complete. Do not substitute earlier signals (page load, content elements, DOM non-empty) — they fire before diff annotation and produce flaky assertions on diff-specific selectors. All fixture pairs must produce at least one diff, because `[data-diff-chunk]` only appears on non-equal chunks; identical-content pairs cause `waitForRender` to timeout.
