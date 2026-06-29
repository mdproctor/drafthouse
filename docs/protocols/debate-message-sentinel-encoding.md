---
id: PP-20260608-d94c7d
title: "Debate channel messages must use DebateProtocol.META_SENTINEL as the content prefix"
type: rule
scope: application
applies_to: "DebateMcpTools (encoding), DebateChannelProjection (decoding)"
severity: important
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java
  - server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java
  - server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
violation_hint: "A DebateMcpTools method constructing encoded content with a hardcoded 'META:' or any other string prefix instead of DebateProtocol.META_SENTINEL — the projection will silently discard the message as plain content"
created: 2026-06-08
---

All debate channel messages carry structured metadata (entryType, role, round, priority, scope, location) encoded as `DebateProtocol.META_SENTINEL + "key=value|...\\n\\n<body>"`. `DebateMcpTools` must prepend `DebateProtocol.META_SENTINEL` (never a hardcoded string) to all encoded content in `raisePoint()`, `respondTo()`, and `flagHuman()`. `DebateChannelProjection.parseMeta()` treats any message not starting with the sentinel as plain content and ignores it — a wrong prefix is a silent discard, not an error. Substring offsets in `parseMeta()` and `bodyContent()` must use `DebateProtocol.META_SENTINEL.length()`, never a hardcoded number, so they remain correct if the sentinel ever changes.
