# DraftHouseMcpTools Design

**Issue:** drafthouse#24
**Date:** 2026-06-04 (revised after spec review)
**Status:** Approved v2

## Context

DraftHouse exposes document review via Qhorus channels and a LangChain4j reviewer agent.
`CritiqueResource` (501 stub) was already removed. `ReviewSessionResource` is `@Deprecated`
— the REST scaffolding is superseded by this MCP surface. The underlying debate package
(`ReviewSessionService`, `SummaryProjector`, etc.) is NOT deprecated — it is the structured
multi-round spec debate system tracked by issues #27 and #31, a separate feature from the
Qhorus Q&A diff review implemented here.

## Architecture

```
LLM client (Claude Code or any MCP client)
    │  MCP tool calls
    ▼
DraftHouseMcpTools @ApplicationScoped           ← new (runtime)
    │  creates channel, stores session
    │  puts session in registry
    │  calls initChannel() ──────────────────→  ChannelInitialisedEvent (sync CDI)
    │                                                │
    │                                                ▼
    │                               ReviewerChannelBackendFactory (existing)
    │                               .onChannelInitialised()
    │                                 → new ReviewerChannelBackend(registry, channelId, ...)
    │                                 → gateway.registerBackend(...)
    │
    │  injects
    ▼
ReviewSessionRegistry (api interface)           ← no findBySessionId needed (see §sessionId)
```

`ReviewerChannelBackend.post()` must be fixed to read the live session from the registry on
each invocation — the current `final session` field is a bug: `update_selection` swaps the
map entry but the backend holds the original snapshot, making selection context permanently
stale.

## Design Decisions

### sessionId — server-generated UUID

`start_review` generates `sessionId = UUID.randomUUID().toString()` and returns it to the
caller. The caller supplies this sessionId to all subsequent tool calls. The registry is
keyed by `channelId` (a UUID from `channelService.create()`); `sessionId` IS the channelId
UUID formatted as a string. This eliminates the secondary `findBySessionId` lookup entirely:
`update_selection` and `end_review` call `registry.find(UUID.fromString(sessionId))` directly.

### Document storage — on ReviewSession, not DataService

Documents are session-private and ephemeral. `DataService` is a cross-agent shared data bus
with explicit GC claims — the wrong abstraction. `ReviewSession` gains two fields:
`docAContent: String` and `docBContent: String`. `docAKey`/`docBKey` fields are removed.
The backend reads content from the live session on each `post()` call. No DataService
injection in either `DraftHouseMcpTools` or `ReviewerChannelBackend`.

### Personality — config-only, no client parameter

The `personality` tool parameter is removed. Accepting a personality string from an LLM
client and passing it to the `@SystemMessage` is a direct prompt injection vector. Always
use `config.personality()`.

### File path policy — local-only, explicit risk acceptance

`Files.readString(Path.of(docAPath))` can read any file the JVM process can access. This is
intentional for a local-only tool — consistent with the existing `FileResource` and browser
path behaviour. The risk is accepted and documented here. Before any networked deployment,
a configurable base-directory restriction must be added (tracked as a future hardening item).

## ReviewSession record changes (api module)

Remove `docAKey`, `docBKey`. Add `docAContent`, `docBContent`.

```java
public record ReviewSession(
        UUID channelId,      // = UUID.fromString(sessionId) — registry key
        String sessionId,    // UUID formatted as string, returned to caller
        String instanceId,   // "drafthouse-reviewer-{sessionId}"
        String docAContent,  // full document A text (bounded by maxDocChars)
        String docBContent,  // full document B text (bounded by maxDocChars)
        DocumentSide selectionSide,
        String selectionText,
        String personality
)
```

## ReviewerChannelBackend changes (runtime)

Replace `final ReviewSession session` field with `ReviewSessionRegistry registry` +
`UUID channelId`. On each `post()` call:

```java
ReviewSession session = registry.find(channelId).orElse(null);
if (session == null) return; // session ended, ignore message
```

Documents are read from `session.docAContent()` and `session.docBContent()` directly.
No DataService injection.

Constructor arguments change accordingly; `ReviewerChannelBackendFactory.onChannelInitialised()`
passes `(registry, channelId, ...)` instead of `(session, ...)`.

## MCP Tools

### start_review(docAPath, docBPath) → sessionId

1. Read `docAPath` and `docBPath` with `Files.readString()`.
2. If either doc exceeds `config.maxDocChars()`, return error string immediately (no Qhorus calls).
3. Generate `sessionId = UUID.randomUUID().toString()`. `channelName = "drafthouse/" + sessionId`.
4. `channel = channelService.create(channelName, "DraftHouse review session", APPEND, null)`  
   → `channel.id` is the UUID; confirm `channel.id.toString().equals(sessionId)` is NOT assumed —
   sessionId and channelId are separate values. The channelId is `channel.id`, the sessionId
   is the generated UUID. `update_selection` / `end_review` accept the sessionId string and
   resolve it via `registry.find(channel.id)` — the lookup key is `channel.id`, not the sessionId.
   **Correction:** sessionId and channelId are independent. The returned sessionId is the
   generated UUID string. The registry is keyed by `channel.id`. `update_selection` /
   `end_review` must carry the channel UUID somehow.
   **Resolution:** Return `channel.id` formatted as a string as the `sessionId`. The client
   handle IS the channelId, avoiding any secondary lookup. `registry.find(UUID.fromString(sessionId))`
   resolves O(1). This is the clean design.
5. `instanceId = "drafthouse-reviewer-" + sessionId`
6. `instance = instanceService.register(instanceId, "DraftHouse reviewer " + sessionId, List.of("document-review"))`
7. Resolve personality from `config.personality()` (no client parameter).
8. `session = new ReviewSession(channel.id, sessionId, instanceId, docAContent, docBContent, null, null, personality)`
9. `registry.put(session)` ← **MUST precede initChannel**
10. `channelGateway.initChannel(channel.id, new ChannelRef(channel.id, channelName))`  
    → synchronous CDI `ChannelInitialisedEvent` → `onChannelInitialised()` → backend registered
11. Return JSON: `{"sessionId": "<channelId-as-string>", "channel": "<channelName>"}`

**Rollback on failure:** Wrap steps 4–10 in try-finally. On any exception after step 4,
attempt: `registry.remove(channel.id)`, `channelService.delete(channelName, force=true)`.
Log failures in the cleanup path but do not suppress the original exception.

**Duplicate sessionId:** Since sessionId = channelId UUID (server-generated), collisions are
astronomically unlikely. Guard: if `registry.find(channel.id)` is non-empty after step 4,
return an error string without proceeding.

### update_selection(sessionId, side, selectedText?)

1. `UUID channelId = UUID.fromString(sessionId)` — return error string if unparseable.
2. `Optional<ReviewSession> s = registry.find(channelId)` — return error string if empty.
3. `DocumentSide docSide` — parse `side` only if non-null; return error string on `IllegalArgumentException`.
4. `registry.updateSelection(channelId, docSide, selectedText)`
5. Return `{"sessionId": sessionId, "status": "ok"}`.

Pass `side=null, selectedText=null` to clear the active selection.

### query_review(sessionId, question)

1. `UUID channelId = UUID.fromString(sessionId)` — return error string if unparseable.
2. `registry.find(channelId)` — return error string if no active session.
3. `messageService.dispatch(MessageDispatch.builder().channelId(channelId).sender("<caller-instanceId>").type(QUERY).content(question).correlationId(UUID.randomUUID().toString()).actorType(ActorType.HUMAN).build())`  
   → `ReviewerChannelBackend.post()` handles the QUERY and dispatches RESPONSE/DECLINE.
4. Return `{"sessionId": sessionId, "status": "dispatched"}` — response arrives asynchronously
   via the channel; the caller must listen for RESPONSE messages or poll the channel.
5. Caller instanceId: use a fixed `"drafthouse-human"` instance registered once at startup,
   or accept the caller's instanceId as an optional parameter. **Decision:** fixed
   `"drafthouse-human"` registered at `@ApplicationScoped` startup — no client parameter.

### end_review(sessionId, deleteChannel?)

Default for `deleteChannel` is `false`. Pass `true` to fully tear down the channel.

1. `UUID channelId = UUID.fromString(sessionId)` — return error string if unparseable.
2. `registry.find(channelId)` — if empty, return `{"sessionId": sessionId, "status": "not-found"}` (idempotent).
3. `registry.remove(channelId)`
4. If `deleteChannel == true`:  
   `channelService.delete("drafthouse/" + sessionId, force=true)`  
   → `ChannelGateway.cleanupForDeletion()` is called internally, which calls `backend.close()` on every registered backend. Confirmed in ChannelGateway source — no backend leak.
5. If `deleteChannel == false`: backend remains in gateway registry until next server restart
   (in-memory only; not a persistence leak). The channel persists but no session handles it.
   On restart, `ChannelInitialisedEvent` fires but `registry.find()` returns empty, so no
   backend is re-registered.
6. Return `{"sessionId": sessionId, "status": "ended", "channelDeleted": deleteChannel}`.

**Note:** `InstanceService` has no `deregister` method. The registered instance becomes stale
when Qhorus's periodic `markStaleOlderThan()` runs. Acceptable for a local tool.

## ReviewSessionRegistry — no changes needed

`find(UUID channelId)` already exists. `findBySessionId` is not needed because sessionId IS
the channelId string representation. No new interface methods required.

## Security Summary

| Concern | Policy |
|---|---|
| File path traversal | Accepted risk for local-only tool. Document read surface is intentional and consistent with FileResource. Harden before networked deployment. |
| Personality injection | Removed client parameter. Always use `config.personality()`. |
| Caller identity in query_review | Fixed `"drafthouse-human"` instance; no client-controlled identity. |

## Testing

Unit tests (no Quarkus context required):

- `start_review` happy path: channel created, session in registry, initChannel called after put
- `start_review` doc-too-large: returns error, no Qhorus calls
- `start_review` file-not-found: returns error, no Qhorus calls
- `start_review` partial failure: DataService/ChannelService throws → cleanup attempted
- `update_selection` happy path: registry entry updated with new selection
- `update_selection` invalid side string: returns error string (not exception)
- `update_selection` session not found: returns error string
- `update_selection` null side+text: clears selection (passes invariant check)
- `query_review` happy path: QUERY dispatched to channel with correct correlationId
- `query_review` session not found: returns error string
- `end_review` happy path: session removed from registry
- `end_review` with deleteChannel=true: channelService.delete called
- `end_review` session not found: idempotent, returns not-found status
- `ReviewerChannelBackend.post()` sees updated selection: update selection, post message, verify context contains new text
- `ReviewerChannelBackend.post()` with ended session (null from registry): no dispatch

Integration test (#25, separate issue) covers the full QUERY → Commitment → RESPONSE
lifecycle with a real H2 Qhorus datasource.
