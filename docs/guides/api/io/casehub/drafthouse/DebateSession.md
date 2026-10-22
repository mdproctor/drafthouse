# io.casehub.drafthouse.DebateSession

**Package:** `io.casehub.drafthouse`

**Kind:** `class`

Live state for an active debate session.

A record implies immutability; a live session with dynamic participants is not a value
type — participants join over the session's lifetime via lazy registration.

The participants map starts empty and is populated via registerIfAbsent() as roles post.
REV and IMP are registered eagerly by start_debate; other roles register on first use.

## Fields

### `agentId` (`java.lang.String`)

### `autonomous` (`boolean`)

### `channelId` (`java.util.UUID`)

### `channelName` (`java.lang.String`)

### `contextTracker` (`ContextTracker`)

### `converseStarted` (`java.util.concurrent.atomic.AtomicBoolean`)

### `currentSelection` (`io.casehub.drafthouse.SelectionScope`)

### `debateSessionId` (`java.lang.String`)

### `documentSet` (`io.casehub.drafthouse.DocumentSet`)

### `orchestrator` (`io.casehub.agency.conversation.orchestration.ConversationOrchestrator`)

### `participants` (`java.util.concurrent.ConcurrentHashMap<io.casehub.drafthouse.debate.AgentType,java.lang.String>`)

### `snapshotContent` (`java.util.Map<java.lang.Integer,java.lang.String>`)

### `threads` (`java.util.concurrent.ConcurrentHashMap<java.lang.String,io.casehub.drafthouse.SelectionThread>`)

### `timeline` (`io.casehub.drafthouse.debate.DocumentTimeline`)

### `workspacePath` (`java.lang.String`)

## Constructors

### `public DebateSession(java.util.UUID channelId, java.lang.String debateSessionId, java.lang.String channelName, java.lang.String agentId)`

#### Parameters

- `channelId` (`java.util.UUID`)
- `debateSessionId` (`java.lang.String`)
- `channelName` (`java.lang.String`)
- `agentId` (`java.lang.String`)

### `public DebateSession(java.util.UUID channelId, java.lang.String debateSessionId, java.lang.String channelName, java.lang.String agentId, io.casehub.drafthouse.DocumentSet documentSet)`

#### Parameters

- `channelId` (`java.util.UUID`)
- `debateSessionId` (`java.lang.String`)
- `channelName` (`java.lang.String`)
- `agentId` (`java.lang.String`)
- `documentSet` (`io.casehub.drafthouse.DocumentSet`)

## Methods

### `public boolean addDocument(java.lang.String path, java.lang.String label)`

Adds a document to the set.

#### Parameters

- `path` (`java.lang.String`)
- `label` (`java.lang.String`)

#### Returns

true if added, false if path already exists

### `public java.lang.String agentId()`

### `public static io.casehub.drafthouse.DebateSession branchFrom(io.casehub.drafthouse.DebateSession source, java.util.UUID channelId, java.lang.String sessionId, java.lang.String channelName)`

Creates a new session branched from an existing session.
Copies documents and comparison from the source session.

#### Parameters

- `source` (`io.casehub.drafthouse.DebateSession`)
- `channelId` (`java.util.UUID`)
- `sessionId` (`java.lang.String`)
- `channelName` (`java.lang.String`)

### `public java.util.UUID channelId()`

### `public java.lang.String channelName()`

### `public void clearComparison()`

Clears the current comparison.

### `public ContextTracker contextTracker()`

### `public io.casehub.drafthouse.ComparisonPair currentComparison()`

Returns the current comparison pair, or null if none set.

### `public io.casehub.drafthouse.SelectionScope currentSelection()`

### `public java.lang.String debateSessionId()`

### `public java.util.List<io.casehub.drafthouse.DocumentEntry> documents()`

Returns an unmodifiable list of all documents.

### `public java.util.List<io.casehub.drafthouse.SelectionThread> findThreadsNear(io.casehub.drafthouse.SelectionScope scope)`

#### Parameters

- `scope` (`io.casehub.drafthouse.SelectionScope`)

### `public static io.casehub.drafthouse.DebateSession fromSnapshot(io.casehub.drafthouse.DebateSessionSnapshot snapshot)`

Reconstitutes a live session from a snapshot.

<p>ContextTracker and SelectionScope are ephemeral — initialized to defaults.
Document state and participants are restored from the snapshot.

#### Parameters

- `snapshot` (`io.casehub.drafthouse.DebateSessionSnapshot`)

### `public static java.lang.String instanceId(io.casehub.drafthouse.debate.AgentType role, java.lang.String debateSessionId)`

Derives the Qhorus instance ID for a role in a session.
Single source of truth for the naming convention — use at every call site.

#### Parameters

- `role` (`io.casehub.drafthouse.debate.AgentType`)
- `debateSessionId` (`java.lang.String`)

### `public java.lang.String instanceIdFor(io.casehub.drafthouse.debate.AgentType role)`

Returns the stored instance ID for a role, or null if not yet registered.

#### Parameters

- `role` (`io.casehub.drafthouse.debate.AgentType`)

### `public boolean isAutonomous()`

### `public boolean markConverseStarted()`

### `public io.casehub.agency.conversation.orchestration.ConversationOrchestrator orchestrator()`

### `public java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String> participants()`

Returns an unmodifiable view of the current participants map.

### `public java.util.Optional<io.casehub.drafthouse.DocumentEntry> primary()`

Returns the primary (first) document, or empty if no documents.

### `public java.lang.String primaryPath()`

### `public java.lang.String registerIfAbsent(io.casehub.drafthouse.debate.AgentType role, java.util.function.Supplier<java.lang.String> registration)`

Atomically registers a role's instance on first use.

<p>Success path: the supplier is called exactly once per role; its return value is stored
atomically. Subsequent calls return the stored value without invoking the supplier.

<p>Exception path: if the supplier throws, `ConcurrentHashMap.computeIfAbsent`
does not store a value — the key remains absent and the next call will retry the supplier.
Retry is safe because `InstanceService.register()` is an upsert (idempotent).

#### Parameters

- `role` (`io.casehub.drafthouse.debate.AgentType`)
- `registration` (`java.util.function.Supplier<java.lang.String>`)

### `public boolean removeDocument(java.lang.String path)`

Removes a document from the set.

#### Parameters

- `path` (`java.lang.String`)

#### Returns

true if comparison was cleared as a side effect, false otherwise

#### Throws

- `IllegalArgumentException` — if path is the primary document or not found

### `public void resolveThread(java.lang.String threadId)`

#### Parameters

- `threadId` (`java.lang.String`)

### `public void setAutonomous(boolean autonomous)`

#### Parameters

- `autonomous` (`boolean`)

### `public void setComparison(java.lang.String pathA, java.lang.String pathB)`

Sets the comparison pair.

#### Parameters

- `pathA` (`java.lang.String`)
- `pathB` (`java.lang.String`)

#### Throws

- `IllegalArgumentException` — if either path is not in the document set

### `public void setOrchestrator(io.casehub.agency.conversation.orchestration.ConversationOrchestrator orchestrator)`

#### Parameters

- `orchestrator` (`io.casehub.agency.conversation.orchestration.ConversationOrchestrator`)

### `public void setSnapshotContent(java.util.Map<java.lang.Integer,java.lang.String> content)`

#### Parameters

- `content` (`java.util.Map<java.lang.Integer,java.lang.String>`)

### `public void setTimeline(io.casehub.drafthouse.debate.DocumentTimeline timeline)`

#### Parameters

- `timeline` (`io.casehub.drafthouse.debate.DocumentTimeline`)

### `public void setWorkspacePath(java.lang.String workspacePath)`

#### Parameters

- `workspacePath` (`java.lang.String`)

### `public io.casehub.drafthouse.DebateSessionSnapshot snapshot()`

Captures durable state for persistence.

<p>Document state is captured atomically under the DocumentSet lock.
Participant state is read separately from the ConcurrentHashMap.
The snapshot is effectively consistent because document and participant
mutations happen in different MCP tool methods.

<p>Ephemeral state (ContextTracker, SelectionScope) is excluded.

### `public java.lang.String snapshotContentAt(int index)`

#### Parameters

- `index` (`int`)

### `public java.lang.String startThread(io.casehub.drafthouse.SelectionScope anchor)`

#### Parameters

- `anchor` (`io.casehub.drafthouse.SelectionScope`)

### `public java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread> threads()`

### `public io.casehub.drafthouse.debate.DocumentTimeline timeline()`

### `public void updateSelection(io.casehub.drafthouse.SelectionScope selection)`

#### Parameters

- `selection` (`io.casehub.drafthouse.SelectionScope`)

### `public java.lang.String workspacePath()`
