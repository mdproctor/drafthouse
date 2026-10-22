---
layout: post
title: "The Half-Extracted Fold"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [DraftHouse]
---

P5 moved the conversation protocol to casehub-agency — the state model, the renderer, the abstract projection. Any CaseHub app could now fold channel messages into `ConversationState` and render the result. Except they couldn't.

The problem was hiding in plain sight. `ConversationProjection` does two things in sequence: it parses sentinel-encoded metadata from message content, then it folds the parsed values into state. Both steps were private methods inside one class. You could only reach the fold through the sentinel parser.

DraftHouse has two conversation projections. `DebateChannelProjection` extends `ConversationProjection` — three hook methods, done. But `ReviewChannelProjection` dispatches on typed Qhorus messages (`message.type()` rather than sentinel strings), and it needs the same fold. So it reimplements it. Create point, append to thread, update status, escalate flag — fifty lines of identical mechanics with a different front door.

The fold operations — `createPoint`, `respondToPoint`, `flagHuman`, `addMemo`, plus the sub-task lifecycle — are pure state transitions on `ConversationState`. They don't care whether the entry type came from parsing `DHMETA:entryType=RAISE` or from mapping `MessageType.QUERY → "RAISE"`. The abstraction boundary was in the wrong place: between "parse" and "fold," not between "sentinel" and "typed."

We extracted a `ConversationFold` utility to blocks — seven static methods, each a pure function from state to state. `ConversationProjection`'s private methods became thin wrappers calling `ConversationFold`. `ReviewChannelProjection` dropped from 155 lines to 97, the reimplemented fold replaced by one-liner calls. Both still pass their full test suites.

The interesting part isn't the extraction itself — that's mechanical. It's that P5 was supposed to make conversation projections reusable, and it almost did. The model and renderer were public. The fold was locked behind an implementation detail. Any app using typed Qhorus messages — which is the intended usage pattern for the platform — would have hit the same duplication ReviewChannelProjection already had. Claudony would have been next.
