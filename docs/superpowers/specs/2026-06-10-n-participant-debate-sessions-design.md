# N-Participant Debate Sessions — Design Spec

**Date:** 2026-06-10
**Status:** Draft
**Scope:** Server-side only — no UI, no Qhorus, no SummaryRenderer changes
**Supersedes:** The two-party (REV/IMP) constraint in the debate session layer
**Related issues:** #41 (sub-project 1 of 4)

---

## Problem

The debate session layer is hardcoded to two participants (REV and IMP). The underlying Qhorus channel layer is N-party by nature — any number of instances can post to a channel. The constraint lives in four places: the `AgentType` enum, the `DebateSession` record, `DebateMcpTools`, and `DebateChannelProjection.agentType()`.

---

## Design

### 1. `AgentType` enum — extend with three new roles

```java
public enum AgentType { REV, IMP, SUPERVISOR, MODERATOR, SELECTOR }
```

**Role semantics:**
| Role | Intended use |
|------|-------------|
| `REV` | Reviewer — raises points, critiques |
| `IMP` | Implementer — responds, accepts/disputes |
| `SUPERVISOR` | Observes debate quality, raises meta-points |
| `MODERATOR` | Redirects debate, can call points resolved |
| `SELECTOR` | Reads full summary, makes final selection decision |

These are the initial values. Migration to a configuration file is deferred — enum values are added as needed.

`SummaryRenderer` already renders `entry.agent()` as a string — no changes required.

---

### 2. `DebateSession` — class, not record

A record implies immutability; a live session with dynamic participants is not a value type. `DebateSession` becomes a class.

```java
public class DebateSession {
    private final UUID channelId;
    private final String debateSessionId;
    private final String channelName;
    private final ConcurrentHashMap<AgentType, String> participants; // role → instanceId
    private final String specPath;

    public DebateSession(UUID channelId, String debateSessionId, String channelName, String specPath) { ... }

    /** Derives the Qhorus instance ID for a role in a given session. Single source of truth for the naming convention. */
    public static String instanceId(AgentType role, String debateSessionId) {
        return "drafthouse-" + role.name().toLowerCase() + "-" + debateSessionId;
    }

    /**
     * Atomically registers a role's instance on first use.
     * On the success path: the supplier is called exactly once per role; its return value is
     * stored atomically in the participants map. Subsequent calls return the stored value.
     * On the exception path: if the supplier throws, ConcurrentHashMap.computeIfAbsent does
     * not store a value — the key remains absent and the next call will retry the supplier.
     * Retry is safe because InstanceService.register() is an upsert (idempotent).
     */
    public String registerIfAbsent(AgentType role, Supplier<String> registration) {
        return participants.computeIfAbsent(role, r -> registration.get());
    }

    public String instanceIdFor(AgentType role)  { return participants.get(role); }
    public Map<AgentType, String> participants()  { return Collections.unmodifiableMap(participants); }
    // standard getters for channelId, debateSessionId, channelName, specPath
}
```

The participants map starts empty and is populated lazily via `registerIfAbsent` as roles are used (see §4). REV and IMP are registered eagerly in `start_debate`.

---

### 3. Instance ID convention — unified, no special-casing

The convention is enforced by `DebateSession.instanceId(role, debateSessionId)` — a static method in `api/`, callable from both `DebateMcpTools` and tests. There is no prose-only convention that could drift.

```
drafthouse-{role.name().toLowerCase()}-{debateSessionId}
```

Examples:
- `drafthouse-rev-{id}`, `drafthouse-imp-{id}` (unchanged from current)
- `drafthouse-supervisor-{id}`, `drafthouse-moderator-{id}`, `drafthouse-selector-{id}` (new)

Capability tag convention: `document-debate-{role.name().toLowerCase()}`

---

### 4. `DebateMcpTools` — specific changes

#### Role validation (all tools)

Replace the hardcoded `"REV"/"IMP"` string check everywhere:

```java
AgentType role;
try { role = AgentType.valueOf(agentRole); }
catch (IllegalArgumentException e) {
    return "error: invalid agentRole '" + agentRole + "' — must be one of: "
        + Arrays.stream(AgentType.values()).map(Enum::name).collect(Collectors.joining(", "));
}
```

#### `sender()` — lazy participant registration

When a role is used for the first time (e.g., SUPERVISOR joins mid-debate), auto-register its instance. Registration delegates through `DebateSession.registerIfAbsent()`, which holds the `ConcurrentHashMap` and can call `computeIfAbsent` correctly. `DebateMcpTools` must not call `computeIfAbsent` on `session.participants()` — that returns an unmodifiable view and would throw `UnsupportedOperationException`.

```java
private String sender(DebateSession session, AgentType role) {
    return session.registerIfAbsent(role, () -> {
        String instanceId = DebateSession.instanceId(role, session.debateSessionId());
        instanceService.register(instanceId,
                "DraftHouse " + role.name().toLowerCase() + " " + session.debateSessionId(),
                List.of("document-debate-" + role.name().toLowerCase()));
        return instanceId;
    });
}
```

`start_debate` still registers REV and IMP eagerly (they are expected to always participate). All other roles lazy-register on first use.

#### `start_debate` — participants initialisation and cleanup

```java
DebateSession session = new DebateSession(channel.id, debateSessionId, resolvedName, specPath);
registry.put(session);
// Register REV and IMP eagerly via sender() to populate the participants map
sender(session, AgentType.REV);
sender(session, AgentType.IMP);
channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedName));
```

The cleanup block in the `catch` path replaces the old local-variable approach. After `registry.put(session)`, the participants map is the authoritative list of what has been registered:

```java
} catch (Exception e) {
    LOG.warning("start_debate failed: " + e.getMessage() + " — attempting cleanup");
    if (channel != null) {
        if (session != null) {
            session.participants().values().forEach(id -> {
                try { instanceService.deregister(id); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
            });
            try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
        }
        try { channelService.delete(channel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
    }
    return "error: " + e.getMessage();
}
```

`session` must be declared outside the `try` block (initialised to `null`) so the cleanup can reference it.

#### `end_debate` — deregister all participants

```java
session.participants().values().forEach(instanceId -> {
    try { instanceService.deregister(instanceId); }
    catch (Exception e) { LOG.warning("end_debate: deregister failed: " + e.getMessage()); }
});
```

#### `restart_from_round` — new session construction and cleanup

The new session starts empty; instances are registered lazily as each role first posts. The RESTART_CONTEXT marker is posted via `sender(newSession, AgentType.REV)`, which triggers eager REV registration. All other roles register on first use in the new session.

```java
DebateSession newSession = new DebateSession(
        newChannel.id, newSessionId, newChannel.name, original.specPath());
registry.put(newSession);
channelGateway.initChannel(newChannel.id, new ChannelRef(newChannel.id, newChannel.name));

String markerSender = sender(newSession, AgentType.REV); // registers REV for the new session
String markerContent = ...;
messageService.dispatch(MessageDispatch.builder()
        ...
        .sender(markerSender)
        ...
        .build());
```

The cleanup block replaces the old `newRevId`/`newImpId` local variables. `newSession.participants().values()` contains only what was successfully registered before the failure:

```java
} catch (Exception e) {
    LOG.warning("restart_from_round failed: " + e.getMessage() + " — attempting cleanup");
    if (newChannel != null) {
        if (newSession != null) {
            newSession.participants().values().forEach(id -> {
                try { instanceService.deregister(id); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
            });
            try { registry.remove(newChannel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
        }
        try { channelService.delete(newChannel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
    }
    return "error: " + e.getMessage();
}
```

`newSession` must be declared outside the `try` block (initialised to `null`) so the cleanup can reference it.

#### Tool descriptions — update `agentRole` parameter

All six tools (`raise_point`, `respond_to`, `flag_human`, `post_memo`, `request_subagent`, and `start_debate` error messages) reference "REV or IMP". Update to list all five values: `REV | IMP | SUPERVISOR | MODERATOR | SELECTOR`.

---

### 5. `DebateChannelProjection` — `agentType()` update

The projection's private `agentType()` helper is a hardcoded switch over `"REV"` and `"IMP"`. Any message with `agent=SUPERVISOR` (or any new role) hits the `default` arm and throws `IllegalArgumentException`, which propagates through `apply()` and corrupts the fold. This must be fixed.

**The fix is discard-with-warning, not re-throw.** `apply()` is a left-fold — its contract is `(state, message) → state` for any `MessageView`. Throwing makes it a partial function that crashes on unseen input. Every other unknown-value case in this class is defensive: unknown `entryType` returns state with a warning; unknown `priority` and `scope` return defaults. An unknown `agent` string follows the same pattern.

```java
private AgentType agentType(Map<String, String> meta) {
    String agent = meta.get("agent");
    if (agent == null) {
        LOG.log(System.Logger.Level.ERROR,
                "Debate message missing META.agent field — protocol violation, message discarded");
        return null;
    }
    try {
        return AgentType.valueOf(agent);
    } catch (IllegalArgumentException e) {
        LOG.log(System.Logger.Level.WARNING,
                "Unknown agent ''{0}'' in debate META header — message discarded", agent);
        return null;
    }
}
```

`ProjectionService.fold()` has no exception handling — any throw from `apply()` terminates the fold immediately, discards the partial state, and propagates up through every MCP tool that calls `projectionService.project()`, breaking the session permanently. The distinction between "protocol violation by our code" and "unknown future role" is meaningful for log severity (ERROR vs WARNING), not for whether to throw.

Every handler that calls `agentType()` must check the return value and early-return `state` if null. There are **three direct call sites in three methods**: `handleRaise`, `handleFlagHuman`, and `appendToPoint` (which handles AGREE, DISPUTE, QUALIFY, and COUNTER):

```java
AgentType agent = agentType(meta);
if (agent == null) return state;
```

No other change to `DebateChannelProjection` is required. The `handleMemo`, `handleSubTaskRequest`, `handleSubTaskFinding`, and `handleSubTaskError` handlers already store `agent` as a raw `String` via `meta.getOrDefault("agent", "UNKNOWN")` — they work correctly for new roles without modification.

---

### 6. What does NOT change

- `Qhorus` — no changes; N-party channels are already supported
- `SummaryRenderer` — already renders `entry.agent()` as a string; no changes
- `DebateProtocol` — no changes; `agent=` field is already a free-form string parsed by the projection
- `ThreadEntry` — already uses `AgentType`; adding enum values is source-compatible
- `ReviewState`, `ReviewPoint`, `SubTaskFinding` — no changes
- **`ReviewChannelProjection.agentType()`** — do not modify. This projection maps `message.actorType()` (a Qhorus `ActorType` enum: HUMAN→REV, AGENT→IMP) to `AgentType`. It is completely unaffected by adding new `AgentType` enum values — those new roles only appear in the debate channel, which uses `DebateChannelProjection`, not this one. An implementor who "fixes" `ReviewChannelProjection.agentType()` is making an unnecessary change that adds no value and could introduce bugs. Note: `ReviewChannelProjection.agentType()` also throws for unknown `actorType` values (its switch has a default throw arm). This is a pre-existing fragility — the review channel fold is vulnerable to the same fold-crash risk as the debate channel was before this fix. That is a separate concern, out of scope for this sub-project; do not address it here.

Note: `DebateChannelProjection` does require a change — the `agentType()` helper (see §5).

---

## Migration

**Record constructor → class constructor.** All call sites that do `new DebateSession(channelId, sessionId, name, revId, impId, specPath)` change to `new DebateSession(channelId, sessionId, name, specPath)`. The instance IDs are no longer constructor arguments.

**Accessors.** `session.revInstanceId()` / `session.impInstanceId()` become `session.instanceIdFor(AgentType.REV)` / `session.instanceIdFor(AgentType.IMP)`.

**Test helper sessions.** The shorthand helpers in `DebateMcpToolsTest` (`sessionFor()` and inline `new DebateSession(…, "r", "i", null)`) will produce sessions with an empty participants map. Tests that exercise tool paths relying on `sender()` (which calls `registerIfAbsent`) will have `instanceService` receive registration calls that the mock needs to handle. The `sessionFor()` helper and related setup stubs must be updated accordingly — this is non-trivial migration, not purely mechanical.

**`startDebate_happyPath_sessionFieldsCorrect`.** This test currently asserts `s.revInstanceId()` and `s.impInstanceId()` on the captured session. With the new class, it must assert that the participants map contains REV and IMP after the tool returns, using `s.instanceIdFor(AgentType.REV)` etc.

No Flyway migrations. No API surface changes visible to Qhorus or callers.

---

## Testing

### Unit tests to add/update

| Test | Nature |
|------|--------|
| `start_debate` registers REV + IMP eagerly | existing, update constructor call and session field assertions |
| `raisePoint` with SUPERVISOR role dispatches correctly | new |
| `raisePoint` with unknown role returns error listing all valid values | new |
| `registerIfAbsent` lazy-registers a new role on first use, returns same id on second call | new (DebateSession unit test) |
| `DebateSession.instanceId()` returns correct format for each role | new (DebateSession unit test) |
| `agentType()` via projection fold: SUPERVISOR raise_point folds correctly into ReviewState | new (DebateChannelProjectionTest) |
| `agentType()` via projection fold: unknown agent string returns null, handler discards with warning | new (DebateChannelProjectionTest) |
| `end_debate` deregisters all participants (not just rev/imp) | existing, update |
| `restart_from_round` new session lazy-registers REV when marker is posted | new |
| `start_debate` failure cleanup iterates participants map | existing, update |

### E2E coverage

`DebateSessionLifecycleTest` exercises REV + IMP end-to-end with a real Qhorus channel. Add a SUPERVISOR `raisePoint` call to one existing test — this exercises the projection fold path for a new role and verifies the summary renders correctly. No separate E2E class required.

---

## Out of scope for this sub-project

- UI changes (sub-project 3)
- SSE push delivery (sub-project 2)
- Context meter and auto-reset (sub-project 4)
- Per-role permissions or channel ACL (future)
- Configuration-file-backed roles (future, explicitly deferred)
