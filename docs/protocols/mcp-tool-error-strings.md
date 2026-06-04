---
id: PP-20260604-6e8d5d
title: "MCP @Tool methods must return error strings — never propagate exceptions"
type: rule
scope: application
applies_to: "All @Tool methods on DraftHouseMcpTools (and any future MCP tool class)"
severity: important
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java
violation_hint: "A @Tool method that throws RuntimeException or IllegalArgumentException instead of returning 'error: ...' — propagates to the MCP transport as an unhandled server error, not a clean tool response"
created: 2026-06-04
---

Every `@Tool` method must catch all error conditions and return a descriptive `"error: ..."` string rather than letting any exception propagate. The MCP transport presents an uncaught exception as an unhandled server error with no useful message for the LLM caller. All four tool methods (`start_review`, `update_selection`, `query_review`, `end_review`) follow this pattern: invalid sessionId format → error string; session not found → error string; invalid enum value → error string; partial failure with cleanup → error string. The pattern extends to any future `@Tool` method: validate preconditions eagerly, return error strings, never throw.
