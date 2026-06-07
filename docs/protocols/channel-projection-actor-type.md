---
id: PP-20260607-508f7b
title: "ChannelProjection implementations must classify actors via MessageView.actorType(), not sender strings"
type: rule
scope: repo
applies_to: "Any ChannelProjection<S> or RenderableProjection<S> implemented in casehub-drafthouse"
severity: important
refs:
  - docs/superpowers/specs/2026-06-06-channel-projection-spi-migration.md
garden_ref: GE-20260607-f2f604
violation_hint: "private AgentType agentType(MessageView m) { if (m.sender().startsWith(...)) ... }"
created: 2026-06-07
---

Use `message.actorType()` (an `ActorType` enum: HUMAN / AGENT / SYSTEM) to classify who
sent a message inside `apply()` — never parse `message.sender()` for this purpose.
Sender strings in DraftHouse are deployment-specific session IDs
(e.g. `"drafthouse-reviewer-{uuid}"`) that vary per session and will break string matching
silently. `actorType` is set explicitly at every dispatch site and is stable across
deployments. Throw `IllegalArgumentException` for null or unexpected `actorType` values —
do not fall back to defaults, as silent misclassification produces incorrect fold state.
Note: this mapping (HUMAN→REV, AGENT→IMP) is a v1 heuristic; when #27 DebateChannel
introduces two-agent folds, the mapping must be revisited.
