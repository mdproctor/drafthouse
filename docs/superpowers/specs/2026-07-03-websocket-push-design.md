# Design: Replace SSE Polling with Pages WebSocket Push

**Issue:** casehubio/drafthouse#87
**Date:** 2026-07-03
**Status:** Approved

## Problem

Two independent polling loops create unnecessary latency between MCP actions and browser feedback:

1. **Client-side (5s):** `setInterval` polls `/api/debate/sessions` to discover new debate sessions. The browser doesn't know a session exists until the next tick fires — up to 5 seconds after the MCP client calls `start_debate`.

2. **Server-side (500ms):** `DebateEventResource.events()` uses a Mutiny `ticks().every(500ms)` loop to poll `messageService.pollAfter()` for new Qhorus messages. Even after the SSE connection is established, every debate event has up to 500ms additional latency.

### Architectural Disconnect

`DebateChannelBackend.post()` is called by Qhorus the instant a message is dispatched to the debate channel — a true push notification. But it only handles `SUB_TASK_REQUEST` messages (firing a CDI event for agent dispatch) and drops everything else. Meanwhile, `DebateEventResource` independently polls the same message store at 500ms intervals.

Four `ConcurrentHashMap` queues (`pendingContextSnapshots`, `pendingSelections`, `pendingDocuments`, `pendingComparisons`) act as single-slot buffers — metadata events arrive immediately from REST endpoints and MCP tools but are held until the next 500ms tick drains them.

Additionally, `drafthouse-diff.js` creates per-file `EventSource` connections to `/api/watch?path=` for file-change notifications — separate SSE connections per loaded file.

## Solution

Replace all SSE endpoints and polling with a single persistent WebSocket connection using the pages push protocol. Events flow end-to-end with zero polling:

- Qhorus dispatches message → `DebateChannelBackend.post()` → `WebSocketEventBus` → browser panel
- MCP tool changes metadata → `WebSocketEventBus` → browser panel
- File changes on disk → `WatchService` → `WebSocketEventBus` → browser panel
- Session created/ended → `WebSocketEventBus` → all browsers

Panels require minimal changes — they already subscribe to `pages-event` CustomEvents. The transport swap requires updating the reconnection signal name (`sse-reconnect` → `reconnected`) and removing one redundant type guard in the context gauge.

## Wire Protocol

All server→client messages use the pages wire format. The `event` op is dispatched automatically by pages' `processWireMessage()` as `pages-event` CustomEvents.

### Server → Client

| Topic | Payload | When pushed | Seq |
|-------|---------|-------------|-----|
| `sessions` | `[{debateSessionId, channelName, specPath, agentId}]` | On connect (full list) | — |
| `session-created` | `{debateSessionId, channelName, specPath, agentId}` | Debate starts (broadcast) | — |
| `session-ended` | `{debateSessionId}` | Debate ends (broadcast) | — |
| `reconnected` | `{}` | Every WebSocket open (panels reset state) | — |
| `debate-entries` | `[{...DebateStreamEntry}]` | Message dispatched to channel | — (catch-up only) |
| `context-usage` | `{serverContributionChars, effectivePercent, ...}` | Context tracker update | — |
| `documents-changed` | `{documents: [...]}` | Document add/remove | — |
| `comparison-changed` | `{pathA, pathB}` | Comparison set | — |
| `selection-scope` | `{side, startLine, endLine, selectedText}` | Selection POST | — |
| `file-changed` | `{path}` | File modified on disk | — |

Format: `{ "op": "event", "topic": "<topic>", "payload": <data>, "seq": "<optional>" }`

### Client → Server

Repurposes pages `subscribe`/`unsubscribe` ops as application-level signaling commands (pending pages#98 listen/unlisten, which would be the cleaner fit). This is a deliberate semantic mismatch: the `subscribe` op in the pages protocol implies data delivery via `snapshot`/`append`/`replace`/`remove` ops keyed by dataset — but the server never sends these data ops. All server→client data flows as `event` ops dispatched via `eventTarget`, bypassing the subscription handler entirely. The `subscribe` message is used only to register interest on the server side.

| Dataset pattern | Meaning |
|----------------|---------|
| `debate:<sessionId>` | Watch debate events for this session |
| `file:<path>` | Watch file for changes |

Format: `{ "op": "subscribe", "dataset": "debate:<sessionId>" }`

Session lifecycle events (`sessions`, `session-created`, `session-ended`) are pushed to ALL connections unconditionally — no subscribe needed.

### Future: Pages #98 Migration

When pages delivers listen/unlisten (casehubio/casehub-pages#98), the client→server protocol becomes:
```json
{ "op": "listen", "topics": ["debate:abc123", "file:/path/to/doc.md"] }
{ "op": "unlisten", "topics": ["debate:abc123"] }
```
Server-side parsing changes from `subscribe`→`listen`; no architectural change. Client swaps `createWebSocketSource` for `createEventConnection` (pages#99).

## Server Architecture

### New: `DebateWebSocket`

Quarkus `@WebSocket(path = "/api/ws")` endpoint using `quarkus-websockets-next`.

```
@OnOpen    → register connection in WebSocketEventBus
           → push { op: "event", topic: "reconnected" } (panels reset state)
           → push current sessions as event op
@OnTextMessage → parse JSON (reject malformed with logged warning, no response):
  - validate: must be JSON object with "op" field; malformed JSON → log warning, ignore
  - subscribe debate:<sessionId> → validate UUID → look up session in registry
    → session exists: register as watcher, send catch-up
    → session not found: silently ignore (no watcher registration, no catch-up — the `sessions` list from `@OnOpen` already tells the client which sessions are active; stale subscriptions from pages reconnect are harmless)
  - unsubscribe debate:<sessionId> → validate UUID, unregister
  - subscribe file:<path> → validate path against union of all watched sessions' document sets for this connection (prevents directory traversal), start WatchService, register
  - unsubscribe file:<path> → validate path, stop watching if no other watchers, unregister
  - subscribe/unsubscribe with unrecognized dataset pattern → silently ignore (no-op, no warning — e.g., the `_events` dummy subscription from pages connection setup)
  - unknown op → log warning, ignore
@OnClose   → unregister all watches, clean up file watchers with no remaining subscribers
@OnError   → log, clean up (same as @OnClose)
```

Uses `@RunOnVirtualThread` for message handlers (file watch setup involves blocking I/O).

### New: `WebSocketEventBus`

`@ApplicationScoped` CDI singleton. The routing hub between event producers and WebSocket connections.

```java
// Session lifecycle — broadcast to ALL connections
void broadcastSessionCreated(SessionInfo info)
void broadcastSessionEnded(String sessionId)

// Debate events — push to connections watching this session
void pushDebateEntries(UUID channelId, List<DebateStreamEntry> entries)
void pushMetadata(UUID channelId, String topic, String payloadJson)

// File changes — push to connections watching this path
void pushFileChanged(String path)

// Connection management
void register(WebSocketConnection conn)
void unregister(WebSocketConnection conn)
void watchSession(WebSocketConnection conn, UUID channelId)
void unwatchSession(WebSocketConnection conn, UUID channelId)
void watchFile(WebSocketConnection conn, String path)
void unwatchFile(WebSocketConnection conn, String path)
```

Internal state:
- `ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketConnection>>` — session watchers (channelId → connections). `CopyOnWriteArraySet` is correct here: writes (subscribe/unsubscribe) are infrequent compared to reads (iterating to push events).
- `ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketConnection>>` — file watchers (path → connections)
- `CopyOnWriteArraySet<WebSocketConnection>` — all registered connections (for broadcasts)

Push methods use `connection.sendText(json)` which returns `Uni<Void>` — non-blocking and safe from any thread. Quarkus WebSocket Next / Vert.x serializes `sendText()` calls internally per connection, so concurrent calls from different producer threads (Qhorus fan-out thread, REST request thread, WatchService thread) will not interleave within a WebSocket message. The order between different event types is non-deterministic, but this is semantically correct because panels handle each topic independently.

All `sendText()` calls subscribe to the returned `Uni` with an error handler that calls `unregister(connection)` — self-healing cleanup for dead connections. Without this, `@OnClose` may not fire promptly for all failure modes (TCP keepalive defaults to ~2 hours), leaving stale connections in watcher sets. The pattern:
```java
connection.sendText(json).subscribe().with(v -> {}, err -> unregister(connection));
```

### Modified: `DebateChannelBackend`

`post()` changes:
1. Convert ALL message types to `DebateStreamEntry` via a new `DebateStreamEntry.from(OutboundMessage)` factory method and push via `WebSocketEventBus.pushDebateEntries()` — this is the zero-latency path replacing the 500ms poll. Note: `OutboundMessage` does not carry the Qhorus ledger message ID (its `messageId()` is a delivery-scoped UUID), so live-pushed entries have no `seq`. This is intentional — the reconnection model uses full catch-up with panel reset, not incremental recovery via `since`.
2. Still fire CDI `ChannelAgentRequest` for `SUB_TASK_REQUEST` (orthogonal — agent dispatch)

### Modified: `DebateEventResource`

- **Remove:** SSE endpoint (`events()` method), four `ConcurrentHashMap` pending queues, 500ms `ticks()` loop, `serializeMessages()`, `serializeContextSnapshot()` helpers
- **Keep:** REST endpoints (`postSelection`, `deleteSelection`, `getDocuments`, `postComparison`, `activeSessions`)
- **Change:** `pushContextSnapshot()`, `pushDocumentsChanged()`, `pushComparisonChanged()`, `pushSelectionEvent()` call `WebSocketEventBus.pushMetadata()` directly instead of writing to ConcurrentHashMaps

### Session lifecycle hook

`DebateMcpTools.startDebate()` calls `WebSocketEventBus.broadcastSessionCreated()` after creating the session and registering it in the registry, and `DebateMcpTools.endDebate()` calls `WebSocketEventBus.broadcastSessionEnded()` after removing it. The broadcast originates from `DebateMcpTools` (in the `runtime` module), not from `DebateSessionRegistryImpl` — the registry interface lives in the `api` module and must not depend on transport concerns. This is consistent with the existing pattern where `DebateMcpTools` already calls `debateEventResource.pushContextSnapshot()` and `pushDocumentsChanged()` directly.

### Removed: `WatchResource`

File watching moves into `WebSocketEventBus`. Internally reuses `java.nio.file.WatchService` with the same polling mechanism, but pushes `file-changed` events through WebSocket instead of SSE. Reference-counted: watch stops when the last subscriber for a path disconnects.

### Dependency

Add `quarkus-websockets-next` to `server/runtime/pom.xml`:
```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-websockets-next</artifactId>
</dependency>
```

## Client Architecture

### index.ts — WebSocket replaces SSE bridge and session polling

On page load (after `loadSite()`):
1. Create WebSocket source via `createWebSocketSource("ws://" + location.host + "/api/ws", { eventTarget: document.documentElement })` — the `eventTarget` config is **required** for `event` ops to be dispatched as `pages-event` CustomEvents; without it, `processWireMessage()` silently drops them. Note: `PushSourceConfig.eventTarget` is typed as `HTMLElement`, so `document` (type `Document`) does not compile. `document.documentElement` (the `<html>` element) IS an `HTMLElement`, and events dispatched with `bubbles: true` bubble up to `document` where the panels listen. After pages#100 widens the type to `EventTarget`, this can be simplified to `{ eventTarget: document }`.
2. Subscribe to a dummy dataset `"_events"` via `source.subscribe()` to establish the WebSocket connection. The listener is a no-op (all real data arrives via `eventTarget` events, not subscription callbacks). The server silently ignores this subscription because `_events` doesn't match any recognized dataset pattern — this is intentional and not an error.
3. Listen for `pages-event` topic `sessions` → auto-connect if single session, show picker if multiple
4. Listen for `pages-event` topics `session-created` / `session-ended` → live updates

All client→server signaling goes through `source.subscribe()` / `source.unsubscribe()` — the underlying WebSocket is not directly accessible. This is critical for reconnection correctness: the `ws.onopen` handler in the pages WebSocket source (websocket-source.ts:54-66) iterates all tracked subscriptions and re-sends them automatically. Subscriptions registered through `source.subscribe()` are tracked; messages sent by other means would not be re-sent on reconnect.

`connectDebateSession(sessionId)`:
1. Call `source.subscribe("debate:<sessionId>" as DataSetId, { uuid: "debate:<sessionId>" as DataSetId }, noOpListener, noOpError)` — the `ExternalDataSetDef` only needs `uuid`; `extractWireName()` falls back to the `dataSetId` when `def.url` is undefined, so `"debate:<sessionId>"` becomes the wire name. The no-op listener is intentional: data subscription ops (snapshot/append/replace/remove) are never sent; all data arrives via `event` ops on `eventTarget`.
2. Configure panels (same as today)
3. Call `source.subscribe("file:<pathA>" as DataSetId, ...)` and `source.subscribe("file:<pathB>" as DataSetId, ...)` for file watching
4. Store subscription DataSetIds for later cleanup via `source.unsubscribe()`

When comparison changes:
1. Call `source.unsubscribe()` on old file DataSetIds, then `source.subscribe()` for new paths
2. Load new files into diff panel

### Deleted: `sse-bridge.ts`

No replacement. Pages WebSocket source dispatches `event` ops as `pages-event` CustomEvents automatically.

### Modified: `drafthouse-diff.js`

- Remove all `EventSource` creation and `onmessage` handlers
- Add `pages-event` listener for topic `file-changed` — when `payload.path` matches a loaded file, reload via `fetch(/api/file?path=...)`
- `loadFile()` no longer sets up a watcher — file watching is managed by index.ts

### Modified: `drafthouse-debate.js`

- Replace `topic === 'sse-reconnect'` with `topic === 'reconnected'` — the SSE-specific reconnect signal is replaced by a transport-neutral `reconnected` event pushed by the server on every WebSocket open

### Modified: `drafthouse-review-tracker.js`

- Replace `topic === 'sse-reconnect'` with `topic === 'reconnected'` — same reconnection signal change

### Modified: `drafthouse-context-gauge.js`

- Replace `topic === 'sse-reconnect'` with `topic === 'reconnected'`
- Remove redundant `if (data.type !== 'context-usage') return` guard in `#handleMeta()` — under SSE, `payload` was the full JSON object including `type: "context-usage"` (because `sse-bridge.ts` dispatches `topic: data.type, payload: data`); under WebSocket, the topic is in the wire envelope and the payload does NOT include a `type` field, so this guard would always return early. The topic-based filter in the listener (`topic === 'context-usage'`) already provides the necessary discrimination.

## Catch-up and Reconnection

### Initial connection

1. WebSocket opens → server pushes `reconnected` event (idempotent — panels with no state simply ignore it)
2. Server pushes `sessions` event immediately
3. Client auto-selects session → sends subscribe for `debate:<sessionId>`
4. Server sends catch-up: last N messages via `messageService.pollAfter(channelId, 0, catchUpLimit)` (configurable via `DraftHouseConfig.debate().catchUpLimit()`, default 500) as a single `debate-entries` event, plus current metadata state as individual events:
   - `context-usage` from `session.contextTracker().snapshot()` — **best-effort after server restart**: ContextTracker is ephemeral (excluded from DebateSessionSnapshot), so it resets to defaults until the next `report_context` MCP call from the agent. This is acceptable — context usage is a live indicator, not critical state.
   - `documents-changed` from `session.documents()` — persisted in DebateSessionSnapshot, always available.
   - `comparison-changed` from `session.currentComparison()` — persisted in DebateSessionSnapshot, always available.
5. Live events flow immediately as they arrive via ChannelBackend

### Reconnection

Pages handles reconnection automatically with exponential backoff (max 30s):

1. WebSocket drops → pages reconnects
2. Server pushes `reconnected` event → panels reset their internal state (entries cleared, gauge reset)
3. Pages re-sends all active subscribe messages via `ws.onopen` handler
4. Server sends full catch-up for each subscription (same as initial connection)
5. Metadata state is sent fresh (current snapshot, not a stream)
6. File watch subscribes re-sent (file-changed is idempotent — triggers a reload)

### Why full catch-up, not incremental

The pages `event` op handler in `processWireMessage()` returns early without calling `updateSeq()` — the `lastSeq` tracking in the WebSocket source only applies to data subscription ops (`snapshot`, `append`, `replace`, `remove`), which this design never uses. Therefore `lastSeq` is never set, and `since` is never sent on reconnect. Rather than working around this (which would require pages changes), the design uses a simpler and equally correct model: panels reset on reconnect, server sends full history. For typical debate sessions (20-50 messages), this is negligible bandwidth.

## What Gets Removed

| File/Code | Action |
|-----------|--------|
| `sse-bridge.ts` | Delete |
| `WatchResource.java` | Delete |
| `DebateEventResource.events()` SSE method | Delete |
| Four `ConcurrentHashMap` pending queues in `DebateEventResource` | Delete |
| 500ms Mutiny `ticks()` polling loop | Delete |
| `setInterval` session discovery polling in `index.ts` | Delete |
| Per-file `EventSource` connections in `drafthouse-diff.js` | Delete |
| `showSessionPicker` static HTML builder | Keep (still needed for multi-session) |
| REST endpoints (selection, comparison, documents, sessions) | Keep |

## Pages Infrastructure Improvements (Deferred)

Filed as casehubio/casehub-pages#97 (epic) with child issues:

| # | Title | Impact on this design |
|---|-------|-----------------------|
| pages#98 | Event topic subscriptions (listen/unlisten) | Replaces subscribe-as-signal hack |
| pages#99 | Lightweight event connection API | Replaces createWebSocketSource + dummy subscribe |
| pages#100 | Server-side Java push protocol types | Replaces hand-formatted JSON in WebSocketEventBus; also widens `PushSourceConfig.eventTarget` from `HTMLElement` to `EventTarget` (non-breaking — `HTMLElement` extends `EventTarget`) |

These are mechanical migrations when delivered — no architectural change to the design above.

## Platform Coherence Notes

- **quarkus-websockets-next**: Established pattern — Claudony uses it for terminal streaming
- **ChannelBackend SPI**: Correct integration point per `qhorus-consumer-integration-pattern` protocol — reactive event-driven consumption of channel messages
- **@ApplicationScoped on WebSocket endpoint**: Per `harness-rest-resource-blocking-applicationscoped` protocol
- **Thread model**: WebSocket message handlers use `@RunOnVirtualThread`; push methods use non-blocking `sendText()` returning `Uni<Void>`
- **Minimal panel changes**: Panels subscribe to `pages-event` — transport swap requires only reconnection signal rename (`sse-reconnect` → `reconnected`) and removal of one redundant type guard in context gauge

## Test Strategy

### Core lifecycle
- `DebateWebSocket` + `WebSocketEventBus`: integration tests using Quarkus `@WebSocket` test client — verify subscribe/catch-up/push/unsubscribe lifecycle
- `DebateChannelBackend`: unit test verifying `post()` pushes all message types to `WebSocketEventBus`

### Reconnection (critical path)
- **Full reconnection cycle**: connect → subscribe → receive events → close connection → reconnect → verify `reconnected` event arrives first → verify re-subscribe triggers full catch-up → verify catch-up content matches pre-disconnect state
- **Subscribe to ended session**: subscribe to session → end session via `endDebate()` → reconnect → verify stale subscription is silently ignored (no error, no catch-up)

### Edge cases
- **Concurrent push from multiple producers**: push debate entry via `ChannelBackend.post()` and metadata via `pushMetadata()` concurrently on the same connection → verify both events arrive intact (no garbled output)
- **File watch reference counting**: subscribe two connections to the same path → unsubscribe one → verify file still watched → unsubscribe the other → verify `WatchService` stopped
- **Dead connection self-healing**: subscribe → force-close the underlying TCP connection without a close frame → push an event → verify `sendText()` failure triggers `unregister()` and the connection is removed from all watcher sets

### Migration
- Existing E2E tests (`DebatePanelE2ETest` etc.): migrate from SSE assertions to WebSocket — panels modified minimally, transport different
- `DebateEventResourceTest`: remove SSE-specific tests, keep REST endpoint tests
