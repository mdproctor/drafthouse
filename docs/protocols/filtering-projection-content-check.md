---
id: PP-20260610-60a67a
title: "Consumers of filtering ChannelProjection decorators must check state content, not ProjectionResult.isEmpty()"
type: rule
scope: application
applies_to: "DebateMcpTools and any future caller of a ChannelProjection decorator that skips messages"
severity: important
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java
  - server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
violation_hint: "Using result.isEmpty() as the emptiness gate after a RoundBoundedProjection or any filter-skipping projection — isEmpty() returns false even when no messages matched (cursor advances regardless), producing header-only rendered output instead of the 'No activity' sentinel"
garden_ref: "GE-20260609-0e178e"
created: 2026-06-10
---

`ProjectionResult.isEmpty()` returns `lastMessageId == null`, which means "the channel had no messages at scan time" — not "the projection produced no content". When a filtering decorator (e.g. `RoundBoundedProjection`) scans a non-empty channel but skips all messages because they exceed `maxRound`, the resulting `ProjectionResult` has `isEmpty() == false` and a state equivalent to `projection.identity()`. Callers must check domain-specific state content directly:

```java
// Wrong — isEmpty() is cursor-based, not content-based
String out = result.isEmpty() ? "No activity." : renderer.render(result.state());

// Right — check which fields constitute "empty" for this state type
ConversationState s = result.state();
String out = (s.points().isEmpty() && s.memos().isEmpty() && s.subTaskFindings().isEmpty())
    ? "No debate activity up to round " + round + "."
    : renderer.render(s);
```

This check lives in the tool layer (`DebateMcpTools`), not in `SummaryRenderer` — the renderer's contract is to render a non-empty state; detecting emptiness is a caller responsibility.
