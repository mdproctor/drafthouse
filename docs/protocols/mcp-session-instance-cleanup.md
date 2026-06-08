---
id: PP-20260608-21c69f
title: "MCP session lifecycle methods must deregister all Qhorus instances they registered"
type: rule
scope: application
applies_to: "All MCP tool classes with session start/end methods (DraftHouseMcpTools, DebateMcpTools)"
severity: important
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java
  - server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java
violation_hint: "A catch block that removes the channel and registry entry but does not call instanceService.deregister() — or an end_* method that removes the registry entry but not the instance"
created: 2026-06-08
---

Every `start_*` method that calls `instanceService.register(instanceId, ...)` must ensure `instanceService.deregister(instanceId)` is called in both paths: (1) the catch block for partial failure, and (2) the corresponding `end_*` method for normal termination. To make the instance ID accessible in the catch block, hoist `String instanceId = null` before the try block and assign it after `channelService.create()` inside the try — `if (instanceId != null) { deregister(...) }` in the catch is then safe and idempotent (deregister is a no-op if the instance was never registered). Orphaned instances accumulate until Qhorus `markStaleOlderThan()` sweeps them — they do not cause data loss but pollute the instance registry.
