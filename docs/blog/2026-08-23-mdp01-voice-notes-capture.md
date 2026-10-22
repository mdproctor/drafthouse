---
title: "Voice notes — capture to vault"
date: 2026-08-23
author: mdp
tags: [drafthouse, voice, notes, obsidian, pipeline]
---

A spoken thought decays faster than any other kind of input. You say something
useful while reading a document, while debugging, while walking — and unless
something captures it immediately, it's gone. The interesting question isn't
recording (MediaRecorder handles that). It's what happens between the raw audio
and something you can actually use later.

The boundary we drew is the note. Below the note is capture: audio, speech-to-text,
raw transcript. Above it is creation: editing, structuring, incorporating into a
document or a prompt. VoiceFacet owns everything below. NotesFacet owns the
transition — cleaning the transcript, extracting metadata, writing it to a vault
where it becomes a first-class artifact.

The two-facet split exists because capture and cleanup have fundamentally different
failure modes. VoiceFacet is a browser concern — MediaRecorder, audio encoding,
upload to `POST /api/voice/upload`. If it fails, you get no audio. NotesFacet is
a server-side pipeline — LangChain4j structured output for fidelity cleanup,
frontmatter extraction, vault storage. If it fails, you still have the raw
transcript. The `TranscriptReady` CDI event is the only coupling between them.
VoiceFacet fires it; NotesFacet consumes it. Either facet can evolve independently.

The cleanup pipeline is a fidelity ladder. Raw STT output has filler words,
false starts, repetition, and no punctuation. The first pass cleans that —
sentence boundaries, paragraph breaks, removing the verbal scaffolding that
makes spoken language intelligible in the moment but unreadable on the page.
The result is a faithful cleaned transcript: what you said, minus the noise.
An optional second pass can refine toward a goal — if the note was captured
during a code review, the pipeline can restructure it as review findings.
But the cleaned transcript is always preserved. Refinement adds; it never
replaces.

Notes land in an Obsidian-compatible vault. YAML frontmatter with source,
date, tags, and any extracted metadata. Standard markdown body. The vault
path is configurable, and the file structure follows Obsidian conventions,
so the notes are immediately browsable, searchable, and linkable in any
Obsidian workspace. No proprietary format, no export step.

The less obvious use case: notes aren't just document sources. A cleaned
transcript is a perfectly good LLM prompt. Capture a thought about what
you want to build, clean it up, hand it to an agent. Or use voice notes as
reference material for a debate session — load them into the working set
alongside the document under review.

The speech SPIs — `SpeechToTextService` and the audio encoding abstractions —
live in `casehub-agency`, not in DraftHouse. Any CaseHub application that
needs STT gets the same interface. DraftHouse provides the capture UI and
the note pipeline; the speech capability is platform infrastructure.
