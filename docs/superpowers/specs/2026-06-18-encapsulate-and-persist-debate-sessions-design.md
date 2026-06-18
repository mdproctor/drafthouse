# Encapsulate Document Operations + Debate Session Persistence

**Date:** 2026-06-18
**Issues:** #68 (encapsulate document ops), #64 (session persistence)
**Branch:** `issue-68-encapsulate-document-ops`

---

## Problem

### #68 — Compound operations on DocumentSet are not atomic

`DebateMcpTools.removeDocument()` performs a multi-step sequence on `session.documentSet()`: read primary, read comparison (before), remove, read comparison (after), conditionally push SSE events. Each `DocumentSet` method is individually `synchronized`, but another thread could `setComparison()` between steps. The same check-then-act pattern exists in `DebateEventResource.postComparison()`.

The root cause is that `DebateSession` exposes `documentSet()` publicly, forcing callers to coordinate multi-step compound operations that should be atomic.

### #64 — In-memory-only session registry

`DebateSessionRegistryImpl` is a `ConcurrentHashMap` — server restart loses all active debate sessions. Participants, documents, and comparison state are unrecoverable.

---

## Design

### Part 1: Encapsulate document operations in DebateSession

`DebateSession` absorbs all compound document operations. `DocumentSet` becomes package-private. The `documentSet()` accessor is removed from the public API.

#### Extracted top-level types (api/)

`DocumentEntry` and `ComparisonPair` are extracted from `DocumentSet` to top-level records in `io.casehub.drafthouse`. They are domain vocabulary — "a labelled document path" and "a pair of paths being compared" — and must be publicly accessible since `DebateSession` returns them from its public API.

```java
public record DocumentEntry(String path, String label) { ... }
public record ComparisonPair(String pathA, String pathB) {}
```

`DocumentSet` becomes package-private and references the top-level records internally.

#### New methods on DebateSession

| Method | Returns | Behavior |
|--------|---------|----------|
| `addDocument(path, label)` | `boolean` | Delegates to DocumentSet. Returns true if added, false if duplicate. |
| `removeDocument(path)` | `boolean` | Throws `IllegalArgumentException` if path is the primary document or not in the set. Returns whether comparison was cleared as a side effect. |
| `setComparison(pathA, pathB)` | `void` | Throws `IllegalArgumentException` if either path is not in the document set. |
| `clearComparison()` | `void` | Delegates to DocumentSet. |
| `currentComparison()` | `ComparisonPair` | Delegates to DocumentSet. |
| `documents()` | `List<DocumentEntry>` | Returns defensive copy from DocumentSet. |
| `primary()` | `Optional<DocumentEntry>` | Delegates to DocumentSet. |
| `primaryPath()` | `String` | Returns primary document path, or null if empty. Replaces `specPath()`. |

#### Error signaling

`removeDocument()` throws `IllegalArgumentException` for both error conditions — removing the primary (domain invariant violation) and path-not-found (stale caller state). On success, returns `boolean comparisonCleared`. No `RemoveDocumentResult` type needed.

```java
public boolean removeDocument(String path) {
    // throws IllegalArgumentException if path is primary or not found
    // returns true if comparison was cleared as a side effect
}
```

Call site simplifies to:
```java
try {
    boolean comparisonCleared = session.removeDocument(path);
    if (comparisonCleared) pushComparisonChanged(...);
} catch (IllegalArgumentException e) {
    return "error: " + e.getMessage();
}
```

#### Session branching for restartFromRound

Static factory on `DebateSession` for branching:

```java
public static DebateSession branchFrom(DebateSession source,
    UUID channelId, String sessionId, String channelName)
```

Copies the source session's document state (documents + comparison) into a new session. `restartFromRound()` calls this instead of accessing `DocumentSet.copyOf()` directly.

#### Removed from public API

- `documentSet()` — gone. `DocumentSet` is an implementation detail.
- `specPath()` — renamed to `primaryPath()`. Same behavior, domain-accurate naming.

#### Synchronization

All document methods on `DebateSession` synchronize on the `DocumentSet` instance. The compound `removeDocument()` sequence — check primary, read comparison, remove, check comparison — runs atomically under a single lock.

#### Caller impact

- `DebateMcpTools.addDocument()` — calls `session.addDocument(path, label)` directly.
- `DebateMcpTools.removeDocument()` — calls `session.removeDocument(path)` in a try/catch; reads returned `comparisonCleared` boolean for SSE push.
- `DebateMcpTools.setComparison()` — calls `session.setComparison(pathA, pathB)` in a try/catch. Path validation moves into DebateSession.
- `DebateMcpTools.listDocuments()` — calls `session.documents()` and `session.currentComparison()`.
- `DebateMcpTools.startDebate()` — calls `session.addDocument(specPath, "spec")`.
- `DebateMcpTools.restartFromRound()` — calls `DebateSession.branchFrom(original, ...)`.
- `DebateMcpTools.getDebateSummary()` / `exportDebateSummary()` — `session.specPath()` → `session.primaryPath()`.
- `DebateEventResource.postComparison()` — calls `session.setComparison(pathA, pathB)` in a try/catch. Validation moves into DebateSession.
- `DebateEventResource.getDocuments()` — calls `session.documents()` and `session.currentComparison()`.
- `DebateEventResource.activeSessions()` — `s.specPath()` → `s.primaryPath()`.
- `DocumentSetJson` — method signatures change to accept `List<DocumentEntry>` and `ComparisonPair` instead of `DocumentSet`. Or accept `DebateSession` directly.

### Part 2: Debate session persistence

#### Prerequisite: durable Qhorus datasource

Session persistence delivers value only when the `qhorus` datasource is configured for durable storage (PostgreSQL). With H2 in-memory (current dev/test config):
- Qhorus channels and messages are gone on restart
- `ChannelGateway.onStart()` finds no channels to re-initialize
- Persisted DebateSession metadata would point to nonexistent channels

The `@IfBuildProperty` gate (below) means persistence is off by default, so the H2 case is already handled — no persistence, no false expectations.

#### Snapshot type (api/, Tier 1, pure Java)

```java
public record DebateSessionSnapshot(
    UUID channelId, String debateSessionId, String channelName,
    List<DocumentEntry> documents, ComparisonPair comparison,
    Map<AgentType, String> participants) {}
```

`DebateSession.snapshot()` captures a consistent view of the session's durable state — document state atomically under the DocumentSet lock, participant state read separately from the `ConcurrentHashMap`. In practice the snapshot is effectively consistent because `persist()` is called after individual mutations, and document mutations and participant mutations happen in different MCP tool methods. `DebateSession.fromSnapshot(DebateSessionSnapshot)` reconstitutes a live session with ephemeral fields (ContextTracker, SelectionScope) initialized to defaults.

#### Store SPI (api/, Tier 1, pure Java)

```java
public interface DebateSessionStore {
    void save(DebateSessionSnapshot snapshot);
    Optional<DebateSessionSnapshot> load(UUID channelId);
    void remove(UUID channelId);
    Collection<DebateSessionSnapshot> loadAll();
}
```

The store operates on snapshots, never on live `DebateSession` objects. This cleanly separates persistence from live session state and avoids concurrent-read problems during serialization.

#### Persisted state (via DebateSessionSnapshot)

| Field | Type | Notes |
|-------|------|-------|
| `channelId` | `UUID` (PK) | Qhorus channel ID |
| `debateSessionId` | `String` | Currently channelId.toString() |
| `channelName` | `String` | Qhorus channel name |
| `documents` | `List<DocumentEntry>` | Ordered list; first entry is primary |
| `comparison` | `ComparisonPair` | Nullable — pathA and pathB |
| `participants` | `Map<AgentType, String>` | Role to Qhorus instance ID |

#### Not persisted (ephemeral)

- `ContextTracker` — resets to zero on load; agents re-report usage.
- `SelectionScope` — transient browser state; null on load.

#### CDI priority ladder

| Tier | Class | Annotation | Behavior |
|------|-------|-----------|----------|
| No-op | `NoOpDebateSessionStore` | `@DefaultBean @ApplicationScoped` | `save()` is a no-op, `loadAll()` returns empty. Active when persistence is not enabled. |
| Primary | `JpaDebateSessionStore` | `@ApplicationScoped` + `@IfBuildProperty(name = "casehub.drafthouse.persistence.enabled", stringValue = "true")` | JPA-backed. Activates only when persistence is explicitly enabled. |

Both beans are in `runtime/`. The `@IfBuildProperty` gate on the JPA bean means it is absent from the CDI graph when the property is not set, allowing the `@DefaultBean` no-op to activate. Tests get the no-op automatically. Production enables persistence explicitly.

#### JPA entity (runtime/, not exposed in api/)

`DebateSessionEntity` in `runtime/`:
- `channelId` UUID primary key
- `debateSessionId` String
- `channelName` String
- `comparisonPathA` String (nullable)
- `comparisonPathB` String (nullable)
- `@ElementCollection` for documents (path + label, ordered via `@OrderColumn`)
- `@ElementCollection` for participants (AgentType key, instanceId value)

The JPA store maps `DebateSessionSnapshot` ↔ `DebateSessionEntity` internally. The domain model never sees JPA types.

#### Datasource strategy: share the qhorus datasource

DraftHouse tables live alongside Qhorus tables in the `qhorus` named datasource. This avoids a second connection pool and second Flyway history for three small tables.

Flyway location addition:
```properties
quarkus.flyway.qhorus.locations=classpath:db/ledger/migration,classpath:db/qhorus/migration,classpath:db/drafthouse/migration
```

Migrations at `classpath:db/drafthouse/migration`. V100 creates `debate_session`, `debate_session_document`, and `debate_session_participant` tables. V100 provides safe buffer above Qhorus's current domain range (V1–V14) and below ledger's base range (V1000+).

#### Qhorus channel re-wiring on restart

No additional startup logic needed. Qhorus `ChannelGateway.onStart(@Observes StartupEvent)` iterates all persisted channels via `crossTenantChannelStore.listAll()` and calls `initChannel(channelId, ref, recovered=true)`. This fires `ChannelInitialisedEvent(recovered=true)`, which `DebateChannelBackendFactory.onChannelInitialised()` picks up — re-registering the debate backend for any `drafthouse/debate/` prefixed channel.

`DebateSessionRegistryImpl` is lazily initialized (`@ApplicationScoped` without `@Startup`). On first access (typically the first MCP tool call), `@PostConstruct` calls `store.loadAll()` and populates the map. Channel recovery via `ChannelGateway.onStart()` does not require sessions to be in the registry — `DebateChannelBackendFactory` only registers backends, it does not look up sessions.

#### DebateSessionRegistryImpl changes

Write-through cache pattern:

- `@Inject DebateSessionStore store`
- `@PostConstruct`: calls `store.loadAll()`, reconstitutes each snapshot via `DebateSession.fromSnapshot()`, populates the `ConcurrentHashMap`.
- `put(session)`: stores in map AND calls `store.save(session.snapshot())`.
- `remove(channelId)`: removes from map AND calls `store.remove(channelId)`.
- `find()` and `activeSessions()`: read from the map only (hot path, no DB hit).

#### Incremental persist

New method on `DebateSessionRegistryImpl`:

```java
public void persist(DebateSession session) {
    store.save(session.snapshot());
}
```

Called by MCP tools after mutations (document add/remove, comparison change, participant registration). The session object is already in the map by reference — only the store needs updating.

#### Persist call sites in DebateMcpTools

After any mutation that changes persisted state:
- `addDocument()` — after `session.addDocument()`, call `registry.persist(session)`
- `removeDocument()` — after `session.removeDocument()`, call `registry.persist(session)`
- `setComparison()` — after `session.setComparison()`, call `registry.persist(session)`
- `sender()` (participant registration) — after `registerIfAbsent()` when a new participant is registered, call `registry.persist(session)`

`startDebate()` and `endDebate()` already go through `registry.put()` and `registry.remove()`, so no additional persist calls needed there.

#### DebateEventResource persist call sites

- `postComparison()` — after `session.setComparison()`, call `registry.persist(session)`

---

## Testing

- **Unit tests (api/):** `DebateSession` encapsulation — `removeDocument()` throws for primary and not-found, returns correct `comparisonCleared` boolean, `setComparison()` validates paths, `branchFrom()` copies documents correctly, `snapshot()` captures consistent state, `fromSnapshot()` reconstitutes correctly, atomicity under concurrent access.
- **Unit tests (api/):** `DocumentEntry` and `ComparisonPair` as top-level records — validation, equality.
- **Unit tests (api/):** `DebateSessionStore` SPI contract — test against a trivial in-memory implementation in test sources to verify the contract.
- **Integration tests (runtime/):** `JpaDebateSessionStore` — save, load, remove, loadAll with H2 `MODE=PostgreSQL`. Documents ordered correctly, participants round-trip, comparison nullable.
- **Integration tests (runtime/):** `DebateSessionRegistryImpl` write-through behavior — put persists, remove deletes, startup loads from store.
- **Integration tests (runtime/):** CDI activation — verify `NoOpDebateSessionStore` activates without `casehub.drafthouse.persistence.enabled`, and `JpaDebateSessionStore` activates with it set to `true`.
- **Existing tests:** `DebateSessionTest` and `DocumentSetTest` updated to reflect API changes (`specPath()` → `primaryPath()`, new domain methods, top-level record types). `DebateMcpToolsTest` updated for new method signatures.
