---
id: PP-20260604-b88833
title: "MCP @Tool parameters must not flow raw into LLM prompts — use config or server-side allowlist"
type: rule
scope: application
applies_to: "All @Tool methods that interact with DocumentReviewer or any future LLM @AiService"
severity: critical
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java
  - server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java
  - server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java
violation_hint: "A @Tool parameter whose value is passed directly as a @SystemMessage, personality string, or instruction prefix to an @AiService — the LLM client controls the prompt"
created: 2026-06-04
---

Any string value received as a `@ToolArg` and used verbatim as LLM prompt content (system message, personality, instruction prefix) is a prompt injection vector. An adversarially-crafted prompt can instruct the LLM client to pass a malicious personality string that overrides the reviewer's intended behaviour. The fix: resolve personality and any other LLM-influencing strings exclusively from `DraftHouseConfig` (server-side Quarkus config). If a caller must be able to choose a personality, the valid options must be defined server-side as named keys that map to server-held strings — never accept the raw string and pass it through. The `personality` parameter was removed from `start_review` for exactly this reason.
