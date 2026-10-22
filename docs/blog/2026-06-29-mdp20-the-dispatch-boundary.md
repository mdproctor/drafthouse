---
layout: post
title: "The Dispatch Boundary"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-drafthouse, casehub-agency]
tags: [extraction, casehub-agency, agent-dispatch, design-decision]
series: issue-80-extract-p4-agent-dispatch
---

*Part of a series on [#80 — Extract P4 channel agent dispatch SPI to casehub-agency](https://github.com/casehubio/drafthouse/issues/80). Previous: [The First Blocks](2026-06-29-mdp19-the-first-blocks.md).*

P1–P3 were utility extractions — move the class, update imports, done. P4 was the first one that forced a design question: where does the boundary between the reusable pattern and the app-specific wiring actually sit?

DraftHouse's `ChannelAgentDispatcher` does three things: routes a sub-task request to the matching handler (first-match on `handles()` predicates), invokes an LLM via `DebateAgentProvider`, and posts the result back to the channel via `MessageService`. The routing loop is generic. The LLM invocation is generic. But the error dispatch — encoding a `SUB_TASK_ERROR` with the debate protocol's metadata format and posting it via `MessageService` — is entirely DraftHouse-specific.

The first decision was the dependency boundary. `MessageService` lives in `casehub-qhorus` (runtime), but blocks only depends on `casehub-qhorus-api`. Taking a `MessageService` parameter would pull the entire qhorus runtime into blocks as a compile dependency. Instead, the dispatcher takes `Consumer<MessageDispatch>` for posting messages and `Function<AgentTask, String>` for LLM invocation. DraftHouse passes `messageService::dispatch` and `debateAgentProvider::analyse` as method references. The blocks class never sees the runtime types.

The second decision was error dispatch. The base class logs the error. DraftHouse overrides `onError()` to encode the debate protocol's `SUB_TASK_ERROR` metadata header (entryType, subTaskId, taskType, agent) and post it to the channel. A future app — say, an AML case review — would override the same method with its own error format. The pattern is: blocks owns the routing loop; apps own the protocol encoding.

The CDI wrinkle was minor but predictable. Quarkus needs a no-args constructor to proxy `@ApplicationScoped` beans. The blocks base class is a plain library class, not a CDI bean — but subclasses will be. A protected no-args constructor in the base avoids the `DeploymentException` without exposing an invalid construction path to callers.

DraftHouse's dispatcher went from 126 lines of self-contained logic to a 70-line subclass that wires debate-specific concerns onto a 90-line generic base. The six concrete handlers (`VerifyHandler`, `ArbitrateHandler`, etc.) didn't change at all — they implement `ChannelAgentHandler` from blocks now, but the interface is identical. Twenty-six files touched, all import changes.
