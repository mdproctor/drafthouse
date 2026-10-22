---
title: "Panels Leave Home"
date: 2026-08-03
sequence: 31
tags: [extraction, blocks-ui, lit, components]
---

Extracted all 9 DraftHouse Lit panels into `@casehubio/agency-ui-document-workbench` —
a single package in blocks-ui that any CaseHub app can embed. The convergence analysis
against existing blocks-ui components (blocks-channel-feed, blocks-timeline) confirmed
they're genuinely different: debate feeds group by round with type-specific styling,
document timelines do pair selection for A/B comparison. Both interaction models are
too distinct for adapter callbacks to bridge.

The extraction itself was mechanical: CSS variables migrated to pages tokens with
fallback syntax for drafthouse-specific vars (sepia, chrome), four panels gained
`apiBaseUrl` for cross-origin embedding, `channel-feed` renamed to `debate-feed` to
avoid the existing `blocks-channel-feed` tag. The showcase gallery now has a
"Document Workbench" category with mock debate data driving all 9 panels.

DraftHouse drops 3,378 lines of local panel code and imports the package via an
esbuild alias. 578 E2E tests pass — the tag rename from `channel-feed` to
`debate-feed` required updating 35 Playwright selectors across 4 test files, which
the initial plan missed and only surfaced when the E2E suite ran.

The jsdom gotcha was the only real surprise: `ResizeObserver` isn't available, so
`document-diff` (which uses it for canvas minimap sizing) can't be DOM-mounted in
vitest. The fix is a no-op stub in `beforeAll`, but for components with imperative
`createRenderRoot`, even the stub isn't enough — jsdom's DOM manipulation causes
`NotFoundError` during cleanup. Testing the class API without mounting is the
pragmatic path.
