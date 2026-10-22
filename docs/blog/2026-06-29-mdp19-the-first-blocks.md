---
layout: post
title: "The First Blocks"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-drafthouse, casehub-agency]
tags: [extraction, casehub-agency, refactoring]
---

The blocks repo has been sitting empty since its scaffold landed earlier this week. Today we put actual code in it — the first three utility patterns extracted from DraftHouse.

The three are small and independent. `ChannelMessageMeta` handles sentinel-prefixed key=value metadata headers in channel message bodies — the encoding scheme DraftHouse invented so structured data can ride alongside free-text LLM output without ambiguity. `ContextTracker` tracks cumulative LLM context window usage with threshold warnings. `BoundedProjectionDecorator` wraps any qhorus `ChannelProjection` and skips messages past a configurable bound — the "replay state at round N" pattern.

None of them required design work. The audit from a few days ago had already mapped every file, every line count, every dependency. The extraction was mechanical: create classes in `io.casehub.agency.channel`, write tests, wire DraftHouse as a consumer.

The one thing that wasn't mechanical was an invisible byte. DraftHouse's `META_SENTINEL` constant contains a SOH byte (U+0001) before the visible `DHMETA:` prefix — it's a collision-resistance measure, since LLMs never produce SOH. The Javadoc says "length 8" but the visible string is 7 characters. Claude rewrote `DebateProtocol.java` to delegate to the new blocks class, and the SOH byte silently vanished. The Read tool doesn't render it. The Write tool can't insert it. The file looked identical before and after.

Eight tests failed. Every `DebateStreamEntryTest` case returned null — `parseMeta()` couldn't find the sentinel. The fix was `xxd` to see the byte, then a Python script to write the literal `` back in. The garden already had an entry for this gotcha from a previous session; I added the "file rewrite drops it silently" variant.

DraftHouse's `DebateProtocol` is now a thin wrapper — three one-line methods delegating to `ChannelMessageMeta`. The `RoundBoundedProjection` inner class extends `BoundedProjectionDecorator` with a single constructor that supplies the debate-specific round extractor. Net result: DraftHouse lost 222 lines and gained a compile dependency on `casehub-agency`.

The interesting part is what this enables for the next extraction. P4 (channel agent dispatch) and P5 (the structured conversation protocol) are the ones Claudony is waiting for. P1–P3 established the repo with real, tested code and proved the extraction path works. The dependency wiring is in place. The hard work is the conversation protocol — that's where the generalisation decisions get non-trivial.
