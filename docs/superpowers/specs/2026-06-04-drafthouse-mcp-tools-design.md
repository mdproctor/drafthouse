# DraftHouseMcpTools Design

**Issue:** drafthouse#24  
**Date:** 2026-06-04  
**Status:** Approved

## Context

DraftHouse exposes document review via Qhorus channels and LangChain4j reviewer agents.
The 501-stub `CritiqueResource` was already removed. This spec adds the MCP tool surface
(`DraftHouseMcpTools`) through which any LLM client can initiate, update, and close review
sessions.

## Architecture

```
LLM client (Claude Code or similar)
    │  MCP tool calls
    ▼
DraftHouseMcpTools @ApplicationScoped   ← new (runtime)
    │  creates channel, stores docs
    │  puts session in registry
    │  calls initChannel() ──────────────→ ChannelInitialisedEvent (sync CDI)
    │                                            │
    │                                            ▼
    │                               ReviewerChannelBackendFactory
    │                               .onChannelInitialised()
    │                                 → registers ReviewerChannelBackend
    │
    │  injects
    ▼
ReviewSessionRegistry (api interface) ← extended with findBySessionId()
    │  implemented by
    ▼
ReviewerChannelBackendFactory @ApplicationScoped (runtime) ← minor change only
```

`ReviewerChannelBackend.post()` is already implemented and handles QUERY → RESPONSE/DECLINE
via `DocumentReviewer @AiService`. No changes to the backend or reviewer.

## Changes

### api module — ReviewSessionRegistry

Add `findBySessionId(String sessionId)` — needed by `update_selection` and `end_review`
to resolve a human-readable sessionId to the `ReviewSession` (keyed internally by channelId UUID).

Implementation in `ReviewerChannelBackendFactory`: linear scan of the `sessions` map.
Session counts are always small (O(tens)), so no secondary index is needed.

### runtime module — DraftHouseMcpTools

`@ApplicationScoped`. Injects: `ChannelService`, `ChannelGateway`, `DataService`,
`InstanceService`, `ReviewSessionRegistry`, `DraftHouseConfig`.

No `@Tool` method has a public same-name overload (GE-20260430-b015f5 — Jandex silently
drops the `@Tool` if public overloads exist).

#### start_review(sessionId, docAPath, docBPath, personality?)

1. Read `docAPath` and `docBPath` from local filesystem (`Files.readString`).
2. If either doc exceeds `config.maxDocChars()`, return an error string immediately.
3. `channelName = "drafthouse/" + sessionId`
4. `channel = channelService.create(channelName, "DraftHouse review session", APPEND, null)`
5. `instanceId = "drafthouse-reviewer-" + sessionId`
6. `instance = instanceService.register(instanceId, "DraftHouse reviewer for " + sessionId, List.of("document-review"))` → returns `Instance` with `UUID id`
7. `docAKey = "drafthouse/" + sessionId + "/doc-a"`, `docBKey = "drafthouse/" + sessionId + "/doc-b"`
8. `SharedData docA = dataService.store(docAKey, "Document A for review session " + sessionId, instanceId, docAContent, false, true)`
9. `SharedData docB = dataService.store(docBKey, "Document B for review session " + sessionId, instanceId, docBContent, false, true)`
10. `dataService.claim(docA.id, instance.id)` + `dataService.claim(docB.id, instance.id)` — prevents GC
11. Resolve personality: param if non-null, else `config.personality()`
12. `registry.put(new ReviewSession(channel.id, sessionId, instanceId, docAKey, docBKey, null, null, resolvedPersonality))`  
    **← session MUST be in registry before step 13**
13. `channelGateway.initChannel(channel.id, new ChannelRef(channel.id, channelName))`  
    → fires `ChannelInitialisedEvent` synchronously → `ReviewerChannelBackendFactory.onChannelInitialised()` finds session → `ReviewerChannelBackend` registered
14. Return `"session=" + sessionId + " channel=" + channelName`

Personality is resolved server-side only — never directly from client input (prompt injection risk).

#### update_selection(sessionId, side, selectedText?)

1. `registry.findBySessionId(sessionId)` → if empty, return error
2. `DocumentSide docSide = side != null ? DocumentSide.valueOf(side) : null`
3. `registry.updateSelection(session.channelId(), docSide, selectedText)` — atomic record swap in factory

Pass `side=null` and `selectedText=null` to clear the active selection.

#### end_review(sessionId, deleteChannel?)

1. `registry.findBySessionId(sessionId)` → if empty, return acknowledgment (idempotent)
2. Release SharedData claims: `dataService.getByKey(session.docAKey())` → UUID, then `release(id, instanceUUID)` + docB (look up instance UUID via `instanceService.findByInstanceId(session.instanceId())`)
3. `registry.remove(session.channelId())`
4. If `deleteChannel=true`: `channelService.delete(channelName, force=true)`
5. Return `"ended session=" + sessionId`

**Note:** `InstanceService` has no `deregister` method. The registered instance becomes stale
naturally when Qhorus's periodic `markStaleOlderThan()` runs. This is acceptable — per-session
instances are ephemeral by design.

## Error Handling

- File not found or unreadable → return error string (no exception propagation to MCP client)
- Doc size exceeded → return error string immediately before any Qhorus operations
- Session not found in `update_selection` / `end_review` → return error string (idempotent calls are safe)
- Qhorus failures → propagate as runtime exception (MCP server wraps in `isError:true` response)

## Not In Scope

- `CritiqueResource` deletion — already done
- `InstanceService.deregister` — method doesn't exist; stale-instance GC handles it
- Personality from client input — explicitly rejected (prompt injection risk)
- `update_selection` with mismatched null/non-null side+text — validated by `ReviewSession` compact constructor

## Testing

Unit tests for each `@Tool` method covering:
- Happy path (start → update → end)
- Doc size exceeded (start_review returns error, no Qhorus calls)
- Session not found (update/end idempotent)
- Personality fallback to config

Integration test (#25, separate issue) covers the full QUERY → Commitment → RESPONSE lifecycle
with a real H2 Qhorus datasource.
