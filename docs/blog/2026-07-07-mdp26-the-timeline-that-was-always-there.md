---
layout: post
title: "The timeline that was always there"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [DraftHouse]
tags: [timeline, snapshots, web-components, design-review]
series: issue-98-document-timeline
---

Every design-review workspace already has a version history. Each implementor round
produces a git commit, and the tracker records `**Spec commit:** → abc123` per issue.
The timeline was sitting in the data — it just wasn't visible.

## Snapshots as first-class events

The design question was where the timeline state should live. Three options: channel
projection state, a separate REST resource, or client-side derivation from the event
stream.

I chose a hybrid. `ROUND_SNAPSHOT` is a new entry type in the channel message stream —
the replay adapter emits one at each round boundary with the commit hash and document
path. The projection intercepts and discards it (no conversation state change), but
the browser receives it through the existing WebSocket pipeline and builds the timeline
client-side.

The content itself stays server-side. A `Map<Integer, String>` of pre-loaded document
content sits on the session, served by `GET /api/debate/{id}/snapshot/{index}`. The
replay adapter calls `git show <hash>:<path>` at parse time so there's no git access
at request time.

## The model that earns its keep later

`DocumentSnapshot` carries a `SnapshotSource` — a sealed interface with one variant
today (`GitCommit`). The design review pushed `roundNumber` out of `DocumentSnapshot`
and into `GitCommit` where it belongs: round numbers are a property of the git-commit
source, not a generic snapshot concern. When live watching (#99) adds a `LiveRound`
variant, the timeline panel won't need to change — it operates on `DocumentSnapshot`,
not on source internals.

## A bug the test fixture found

The E2E tests build a real git repo in `@BeforeAll` — three commits, a tracker with
spec-commit references, reviewer and implementor response files. The fixture caught
a real bug: `configure()` on the timeline panel was not idempotent. `pages-runtime`
calls it on layout render, and `connectDebateSession()` calls it again with the session
ID. Without an `#initialized` guard, every event listener registered twice — the
timeline showed six markers instead of three.

That pattern is now a protocol (`panel-configure-idempotency`). Any panel that registers
document-level listeners in `configure()` needs the same guard.

The timeline strip is thin — 40 pixels above the diff panel. Click a marker to set one
end of the comparison; shift-click for non-adjacent. Click an issue in the review
tracker and the timeline highlights where it was raised, fixed, and verified. The
interaction model works because `point-selected` now carries `raiseRound`, `fixRound`,
and `verifyRound` — data that was already in the review tracker's entry sequence, just
not surfaced in the event.

The design review caught eleven issues across three rounds. The biggest was recognising
that `ConversationState` is a platform type in `casehub-agency` — adding timeline state
there would violate the application boundary. That forced the client-side approach,
which turned out cleaner: no platform changes, no projection state, the browser builds
what it needs from filtered events.
