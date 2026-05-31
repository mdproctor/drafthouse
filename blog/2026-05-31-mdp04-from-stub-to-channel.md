---
title: "From 501 Stub to Channel Participant"
date: 2026-05-31
author: mdp
entry: mdp04
type: phase-update
phase: Phase 2 — Critique Backend
---

The `POST /api/critique` endpoint has been sitting as a 501 stub since we scaffolded
this project. It was always placeholder — the comment even said "Phase 2". Today we
started wiring Phase 2.

We didn't get to the interesting code. What we got was the foundation underneath it,
which turns out to matter a lot.

## The design went through three rounds

I came in thinking the critique backend was a question of which LLM SDK to use:
direct Claude API or LangChain4j. The first draft spec answered that — LangChain4j,
because it's what D2 already decided and because provider-agnosticism matters for a
tool. An SPI, to let both be explored independently during development.

A review caught the bigger problem. The spec designed an HTTP-RPC model — a `ReviewerBackend`
interface with a `critique(ctx)` method — when the whole declared architecture is Qhorus
channel messaging. Those two things don't compose: a `String critique(CritiqueContext)` is
a service call; what we actually want is a channel participant that receives QUERY messages
and dispatches RESPONSE messages with commitment tracking. Build the former and you throw
it away when channels arrive.

The second revision got the model right. The reviewer becomes a Qhorus `ChannelBackend` —
registered when the session channel is created via `ChannelInitialisedEvent`, receives
messages via `post()`, calls LangChain4j, dispatches RESPONSE or DECLINE. Session context
lives in Qhorus SharedData. `CritiqueResource` disappears; the MCP tool surface replaces it.

Round three fixed a handful of correctness issues the second reviewer caught: FAILURE doesn't
discharge QUERY obligations (that's ADR-0005, which I hadn't checked before writing the code
sketch), the `@SystemMessage` template syntax, the missing `ReviewSessionRegistry` interface
in `api/`. Worth catching at spec time rather than mid-implementation.

## LangChain4j 0.26.1 doesn't build with Quarkus 3.33+

This bit us during the Maven restructure. Adding the Anthropic extension dependency
failed at augmentation with an error about "Run time configuration cannot be consumed
in Build Steps" — which sounds like you've wired an extension incorrectly, not like a
version compatibility issue. Spent time auditing the pom before trying 1.9.1, which was
built against 3.33.1 and works on 3.34.3. The error message gives no hint it's a
library version problem. Submitted to the garden.

## Two issues closed, three to go

We got the Maven module split done — `server/` is now `api/` + `runtime/`, the Qhorus
datasource is wired with H2 + `MODE=PostgreSQL`, the compiler parameters flag is in
place. Nine minutes of test time confirmed the six existing tests still pass.

The domain model is written and tested: `ReviewSession`, `ReviewResult`, `DocumentSide`,
`ReviewSessionRegistry`. The compact constructors enforce the null-coherence invariant
(selectionSide and selectionText must be both null or both non-null) and the null content
guard on `ReviewResult`. Ten tests, all green.

The three remaining issues are the real work: the LangChain4j `@AiService` and `ChannelBackend`
wiring, the MCP tool surface, and the integration test that proves the QUERY→Commitment→RESPONSE
lifecycle actually fires. That's next session.
