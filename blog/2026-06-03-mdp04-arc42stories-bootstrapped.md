---
layout: post
title: "Architecture record, finally written"
date: 2026-06-03
type: phase-update
entry_type: note
subtype: diary
projects: [drafthouse]
tags: [arc42stories, documentation, qhorus]
---

DraftHouse had been carrying a note in LAYER-LOG.md since the scaffold phase:
"migration planned — layer entries will move to ARC42STORIES.MD when that
document is bootstrapped." Today it got bootstrapped.

The format question came up first: generate the document section by section,
or the whole thing in one pass? I went with one pass. Arc42stories has
cross-references throughout — Chapter entries name Layer entries, the Layer ×
Chapter matrix has to be consistent with both, §12 risks reference the same
GitHub issues that appear in Layer entries. Write them separately and the
references drift. With the full document in context they stay coherent.

The quality gate protocol requires three checks after generation. The second
one — run `find` for every class named in §9.4 Key files — caught two errors.
`SummaryProjector` was documented in `server/runtime/` but is actually in
`server/api/`. And `ClaudeAgentSdkDebateAgentProvider` has a deeper package
path than I'd written.

The SummaryProjector placement is the interesting one. It implements
`ChannelProjection<ReviewState>` — the Qhorus SPI for incremental state
folding — but has no CDI annotations at all. Qhorus discovers it at startup
via the Java SPI mechanism, not via CDI injection. That means it can live in
`server/api/`, the pure-Java module with no Quarkus compile-scope dependencies.
Which is exactly where you want it if the domain is supposed to be portable
for Claudony embedding.

I'd assumed it would be a CDI bean in `server/runtime/` because every other
Qhorus integration point is. That assumption was wrong, and the quality gate
caught it before it hardened into a misleading permanent record. Claude flagged
both path errors during the systematic check.

The document itself: 2 Journeys, 5 Chapters (3 complete, 2 pending), 3 §9.4
Layer entries migrated from LAYER-LOG, ADR-0001, §1–§13 full structure.
LAYER-LOG.md stays around — the primary-record-declaration protocol is clear
that it isn't retired until ARC42STORIES.MD has been reviewed and verified
complete against it.

Issue #30 closed.
