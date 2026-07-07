---
id: PP-20260707-0cb860
title: "Panel configure() must be idempotent — guard document-level listeners with an #initialized flag"
type: rule
scope: repo
applies_to: "all Web Component panels in server/runtime/src/main/webui/src/panels/ that register document.addEventListener in configure() or #initialize()"
severity: important
refs:
  - server/runtime/src/main/webui/src/panels/drafthouse-timeline.js
violation_hint: "Timeline renders 6 markers instead of 3, or debate entries appear twice in the feed — duplicate event listeners from double configure() call"
created: 2026-07-07
---

Web Component panels that register document-level event listeners (`pages-event`, custom events) must guard against duplicate registration when `configure()` is called multiple times. `pages-runtime` calls `configure()` on initial layout render, and application code (e.g. `connectDebateSession()` in `index.ts`) may call it again with updated props. Without an `#initialized` flag, listeners register twice and every event is processed/emitted in duplicate — doubling entries, markers, or state mutations with no error signal.
