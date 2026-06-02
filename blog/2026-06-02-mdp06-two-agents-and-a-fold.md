---
layout: post
title: "Two Agents and a Fold"
date: 2026-06-02
type: phase-update
entry_type: note
subtype: diary
projects: [drafthouse]
tags: [review-manifest, qhorus, langchain4j, event-sourcing, channel-projection]
---

The design question was deceptively simple: how do you run a structured spec
review between two Claude agents without it becoming a mess of copy-pasted
prompts and manual back-and-forth? The answer took most of a session to work
out, and I spent another building it with Claude.

## The manifest

Two files. `debate.md` is the canonical record — append-only, anchored entries
with stable IDs, fully structured for machine citation. `summary.md` is what
the human actually reads: organised by point, not by round, showing the full
thread of each issue inline. The key distinction is that the summary is a
*projection* of the debate log, not a separate document someone maintains by
hand.

Session storage is a standalone git repo per review at
`~/.drafthouse/reviews/<session-id>/`. Each round is a commit. Time-travel is
just git checkout.

## The fold that matters

I wanted a deterministic summary generator — no LLM, same input always gives
the same output. The pattern that fell out is a direct application of event
sourcing: the debate log is an append-only event stream, the summary is a
materialised read model derived by folding over it.

What I didn't see coming was the Qhorus alignment. While we were designing
`SummaryProjector`, qhorus#230 shipped — `ChannelProjection<S>`, a pure
left-fold SPI: `identity()` for the empty state, `apply(state, MessageView)`
for one fold step. `SummaryProjector` implements it directly. The
`MessageType` mapping is clean: QUERY→raise, RESPONSE→agree/qualify,
DECLINE→dispute, HANDOFF→flag-human, EVENT→memo (the no-op that perfectly
matches "private working notes, not in the formal record").

qhorus#231 (incremental fold) fell in too: after each round, we fold only the
new events onto the existing `ReviewState`, not the full history. What started
as a local design decision is now native Qhorus infrastructure.

## Two providers, one interface

The reviewer and implementer agents sit behind a `DebateAgentProvider` SPI
with two implementations. `LangChain4jDebateAgentProvider @DefaultBean` — any
LLM, single API call per round, round-snippet output parsed leniently by
`RoundParser`. `ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)`
— real Claude Code sessions via `claude-agent-sdk-java`, MCP tools for
structured output, the full agentic loop.

The CDI priority pattern — `@DefaultBean` for any-LLM portability,
`@Alternative @Priority(1)` for Claude-specific capability — immediately looked
like a platform pattern, not a DraftHouse-specific one. Every CaseHub app that
dispatches AI agents faces the same choice. Claude filed casehubio/platform#55
for a new `casehub-platform-agent` module to own the Claude Agent SDK Quarkus
wiring: Mutiny/Reactor bridging, MCP tool registration, session timeout
handling — once, for everyone.

## Two things worth knowing

`ReviewState` wraps a `LinkedHashMap` and `ArrayList` in a record — mutable
fields in a pure fold state. Any caller can mutate the returned state from the
outside, breaking the left-fold contract. The fix is a compact constructor with
`Collections.unmodifiableMap()` and `List.copyOf()`. It's the kind of thing
that compiles and passes tests until something shares the state reference and
both copies diverge.

The other is simpler: `com.github.spring-ai-community:claude-agent-sdk-java:1.0.0`
does not exist on Maven Central. The GitHub presence, the 1.0.0 tag, the blog
posts citing it as a Maven coordinate — all point to Central. `mvn dependency:get`
just says "not found" with no hint that JitPack is the answer. The
`ClaudeAgentSdkDebateAgentProvider` is a stub for now, pending verification.

The platform contribution that surprised me: the cross-repo-issues-in-parent
protocol (PP-20260525-5b1efa) was too broad — "any issue that applies to
multiple repos" was catching single-repo SPI implementations with multiple
consumers. We tightened it to the actual intent: parent is for simultaneous
execution across 2+ repos where a blocker chain won't do. Simple rule, and one
worth getting right before it sends work to the wrong place.
