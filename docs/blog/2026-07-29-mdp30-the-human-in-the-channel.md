---
title: "The Human in the Channel"
date: 2026-07-29
author: Mark Proctor
entry_type: note
subtype: diary
status: draft
projects:
  - casehub-drafthouse
tags:
  - drafthouse
  - hil
  - debate
  - qhorus
  - channel
---

# The Human in the Channel

DraftHouse already had FLAG_HUMAN — an agent could say "I need a person to look at
this." But FLAG_HUMAN is a stop-the-world signal. The agent punts to the human and
waits. The debate stalls. The whole model is: agents run, human observes, and if
something goes wrong enough, everything pauses.

I wanted the opposite. The human should be another participant in the channel,
posting alongside running agents — commenting on review points, overriding
assessments, raising new issues from the diff, reprioritising what matters.
Not stopping the debate. Joining it.

## The channel already knew about humans

The platform `ActorType` enum has had `HUMAN` since day one. Every message dispatch
carries an `actorType` field. The debate protocol encodes metadata with a sentinel
prefix. The projection folds messages into `ConversationState` regardless of who sent
them. The infrastructure was there — it just had no entry point for a human to post.

So the design question was narrow: where does the human action enter the system, and
what happens to it once it's there?

## Five actions, one REST resource

We settled on five things a human can do during a debate:

- **Comment** on an existing review point — adds context without changing status
- **Override** a point's status — terminal, authoritative, debate over on that point
- **Raise a new point** — the reverse of the current flow, human raises what the
  agents missed
- **Reprioritise** — change a point's priority when the reviewer's assessment is wrong
- **Batch accept/defer** — approve or defer all low-priority points at once

Each is a POST to `/api/debate/{id}/human/*`. The resource resolves the session,
registers the human as a Qhorus instance (lazily, on first action), dispatches to
the channel with `ActorType.HUMAN`, and writes to `decisions/human-round-{n}.md`
in the workspace directory.

The decisions files are the bridge to design-review agents. They read `decisions/`
at round start and incorporate human input into their prompts. That side — the
soredium skill changes — is out of scope for this branch. The channel-to-file
direction works; the file-to-prompt direction is next.

## Three new entry types

`COMMENT` is a response that doesn't change status. The projection's `statusAfter()`
returns null — the point stays wherever it was. The thread grows, the content is
visible, but the state machine doesn't move.

`HUMAN_OVERRIDE` is a new terminal status. Like VERIFIED or DEFERRED, but
explicitly human-authoritative. Once overridden, agents don't revisit it.

`REPRIORITISE` was the interesting one. Priority lives in `PointClassification`,
which is set at point creation and never changes. Updating it required a new
`ConversationFold.reprioritisePoint()` method in casehub-agency — the first
cross-module change for this feature. The method builds a new classification
with the updated priority, preserving scope and location, and adds a `ThreadEntry`
so the change is visible in the projected state. Claude caught during design review
that without the ThreadEntry, priority changes would be invisible to agents reading
`getDebateSummary`.

## The push problem that wasn't

I originally specced `trackAndPush` in the REST endpoints — the same pattern
DebateMcpTools uses to project the channel state and push updates to WebSocket
watchers. The design review killed this in round 1. `trackAndPush` does two
things: context tracking (how many chars has this session consumed?) and WebSocket
push. Context tracking is meaningless for human actions — humans don't have token
budgets. And the WebSocket push is already handled by `DebateChannelBackend.post()`,
which fires on every message dispatched to the channel regardless of origin.

The REST endpoints just dispatch to the channel. The channel backend handles the
rest. One of those cases where the right design is less code.

## The UI side

The review tracker panel gained action buttons — comment, override, and priority
dropdowns on every unresolved point. Inline text inputs appear below the point
when clicked, with Enter-to-submit. A batch bar appears at the top when two or
more low-priority points are unresolved: "Accept all / Defer all."

The channel feed got a warm-accent badge for human entries — `👤 Human` instead
of the blue/green agent badges. Human actions in the feed are visually distinct
from agent entries without needing a separate section.

Both panels needed their label maps cleaned up. The `FAC` (Facilitator) label had
no corresponding `AgentType` value — a stale artifact from an earlier design.
Adding `HUMAN` was the prompt to fix the rest: SUPERVISOR, MODERATOR, SELECTOR
all got their labels.

## What's next

The channel-to-file direction works. The human can act in DraftHouse and the
decisions appear in the workspace. The other direction — design-review agents
reading those decisions — requires soredium skill changes. That's a separate
branch, and it's the piece that closes the loop: human decides, agent adapts.
