---
layout: post
title: "The Pipeline That Watches Itself"
date: 2026-08-12
entry_type: note
subtype: diary
projects: [casehub-drafthouse]
tags: [pipeline, review, ui, mcp, lit]
---

Dimensional reviews — where coherence, structure, and robustness each get their own
reviewer — have been running for weeks. The problem was always the same: three
processes running in parallel, progress visible only in the terminal that launched
them. The human sits watching `tail -f` on three progress logs. When a HIL checkpoint
fires, the terminal session presents the options. The browser shows nothing.

We built the other side of that: a `<review-pipeline>` panel in DraftHouse that shows
what the terminal session is orchestrating. Not controls — the terminal keeps those.
A dashboard. Which dimensions are running. What round each one is on. How many issues
found at what priority. When a checkpoint is pending.

The interesting design tension was where to put the aggregation. Each dimension runs
in its own workspace with its own `progress.log`. The existing `WorkspaceWatcher`
tails one workspace and pushes events to the browser — but it's tightly coupled to
the debate channel infrastructure (session, message service, replay adapter). For a
pipeline, we only need the progress tailing.

So we extracted `PipelineWatcher` — about fifty lines that tail `progress.log`, parse
events via `ProgressLogParser`, and deliver them as `(dimension, event)` pairs to a
callback. That dimension tag turned out to be important: when three watchers run in
parallel, existing events like `ReviewTerminal` don't carry a dimension field. Without
the tag, the orchestrator can't tell which dimension just finished.

The server-side split follows the existing pattern — `PipelineSession` in `server/api/`
is a pure data holder (synchronized, since multiple watcher threads mutate it), while
`PipelineOrchestrator` in `server/runtime/` owns the state machine. Phase
auto-advancement is the interesting part: when all non-crosscutting dimensions report
round 1 complete, the orchestrator advances to `HIL_CHECKPOINT_1` without the calling
session needing to tell it. But HIL decisions (approve this dimension, kill that one)
are MCP calls from the terminal — `update_pipeline` with actions like
`dimension_refused` or `checkpoint_reached`. The server derives what it can; the
terminal tells it what it can't.

Three MCP tools: `start_pipeline` creates the session and spins up watchers,
`update_pipeline` handles checkpoint state, `load_decisions` feeds brainstorming
decisions into the panel. The panel itself renders four sections: a collapsible
decisions block at the top, a horizontal phase indicator, per-dimension cards with
progress bars and issue count pills, and — eventually — an accumulated findings feed.

The spec review caught twelve issues across coherence, structure, and robustness.
The biggest was the module boundary violation: `PipelineSession` was spec'd for
`server/api/` but its state-machine logic referenced `ProgressEvent` from
`server/runtime/`. The split into data model plus separate orchestrator was the fix.
`DimensionStatus` gained a `FAILED` state for crashed reviews. Checkpoint resolution
got explicit phase advancement triggers. Browser reconnect recovery got specified.

The panel lives in `@casehubio/agency-ui-document-workbench` alongside the other Lit
panels. Default hidden, auto-shown when a `pipeline-progress` event arrives. The
`workspace-status` topbar indicator also picks up pipeline events — showing a compact
"Pipeline: 2/3 dimensions running" when active.

What this opens up: the ordered mode path, where dimensions run sequentially with
cascading findings (structure informs coherence informs robustness). The phase header
already supports it — render dimension names instead of generic phase labels. And the
findings feed, which needs `PipelineFinding` data pushed per round. The structure is
there; the data path is a follow-on.
