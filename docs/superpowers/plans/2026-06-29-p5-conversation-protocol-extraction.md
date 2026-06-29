# P5: Structured Conversation Protocol Extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the structured conversation protocol (data model, projection, renderer) from DraftHouse to casehub-blocks as the `io.casehub.blocks.conversation` package, then migrate DraftHouse to consume it.

**Architecture:** Concrete protocol with string-typed entry types and a three-method subclass hook on an abstract `ConversationProjection`. Data model is records with defensive immutability. Renderer is a concrete class configurable via vocabulary maps. DraftHouse becomes a thin vocabulary layer over the blocks protocol.

**Tech Stack:** Java 21 records, JUnit 5 + AssertJ + Mockito, Qhorus ChannelProjection SPI, Maven multi-module

## Global Constraints

- casehub-blocks version: `0.2-SNAPSHOT` (already a dependency of DraftHouse server/api)
- Qhorus SPI: `ChannelProjection<S>` in `io.casehub.qhorus.api.spi`, `MessageView` in `io.casehub.qhorus.api.message`
- `apply()` must never throw (protocol PP-20260610-a47ef5) — parse defensively, log, return state unchanged
- `identity()` must return a fresh instance on every call (Qhorus contract)
- Sentinel encoding via `ChannelMessageMeta` (already in blocks `io.casehub.blocks.channel`)
- Tests use JUnit 5 + AssertJ + Mockito (no Quarkus harness for blocks unit tests)
- All blocks test classes use a `TestConversationProjection` — no DraftHouse terminology leaks into blocks
- Breaking changes cost nothing — fix the design, not the callers
- Build order: `mvn install` in blocks first, then DraftHouse resolves updated blocks

## Pre-implementation

Before starting any task, read:
- The design spec: `docs/superpowers/specs/2026-06-29-p5-conversation-protocol-extraction-design.md`
- Platform doc: `../parent/docs/PLATFORM.md` (module tier structure, SPI patterns)
- Protocols in `docs/protocols/`: `channel-projection-apply-must-not-throw.md`, `debate-message-sentinel-encoding.md`, `debate-restart-context-not-entry-type.md`
- Universal protocols in `../garden/docs/protocols/universal/`: `module-tier-structure.md`, `maven-coordinate-standard.md`

---

### Task 1: Blocks data model — records, enums, protocol constants

All new code in casehub-blocks (`~/claude/casehub/blocks`).

**Files:**
- Create: `src/main/java/io/casehub/blocks/conversation/Priority.java`
- Create: `src/main/java/io/casehub/blocks/conversation/SubTaskStatus.java`
- Create: `src/main/java/io/casehub/blocks/conversation/PointClassification.java`
- Create: `src/main/java/io/casehub/blocks/conversation/ThreadEntry.java`
- Create: `src/main/java/io/casehub/blocks/conversation/FlagEntry.java`
- Create: `src/main/java/io/casehub/blocks/conversation/RoundMemo.java`
- Create: `src/main/java/io/casehub/blocks/conversation/SubTaskFinding.java`
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationPoint.java`
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationState.java`
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationProtocol.java`
- Create: `src/test/java/io/casehub/blocks/conversation/ConversationStateTest.java`

**Interfaces:**
- Produces: All data types used by Task 2 (ConversationProjection) and Task 3 (ConversationRenderer)

**Records and enums** — port from DraftHouse `server/api/.../debate/` with these transformations:

| DraftHouse field | Blocks field | Change |
|---|---|---|
| `ThreadEntry.agent` (AgentType) | `ThreadEntry.role` (String) | Enum → String |
| `ThreadEntry.type` (EntryType) | `ThreadEntry.entryType` (String) | Enum → String, rename |
| `ReviewPoint.currentStatus` (ReviewStatus) | `ConversationPoint.status` (String) | Enum → String |
| `PointClassification.scope` (Scope) | `PointClassification.scope` (String) | Enum → String |
| `Priority.P1/P2/P3` | `Priority.HIGH/MEDIUM/LOW` | Values renamed |
| `SubTaskFinding.taskType` (SubTaskType) | `SubTaskFinding.taskType` (String) | Enum → String |
| `SubTaskFinding.requestingAgent` | `SubTaskFinding.requestedBy` | Renamed |
| `FlagEntry.agent` (AgentType) | `FlagEntry.role` (String) | Enum → String |
| `RoundMemo.agentRole` | `RoundMemo.role` | Renamed |

**ConversationProtocol** — final utility class with string constants only:

```java
package io.casehub.blocks.conversation;

public final class ConversationProtocol {
    private ConversationProtocol() {}

    // Meta key constants
    public static final String ENTRY_TYPE  = "entryType";
    public static final String ROLE        = "role";
    public static final String ROUND       = "round";
    public static final String PRIORITY    = "priority";
    public static final String SCOPE       = "scope";
    public static final String LOCATION    = "location";
    public static final String POINT_ID    = "pointId";
    public static final String SUB_TASK_ID = "subTaskId";
    public static final String TASK_TYPE   = "taskType";

    // Infrastructure entry types
    public static final String SUB_TASK_REQUEST  = "SUB_TASK_REQUEST";
    public static final String SUB_TASK_FINDING  = "SUB_TASK_FINDING";
    public static final String SUB_TASK_ERROR    = "SUB_TASK_ERROR";
    public static final String MEMO              = "MEMO";
    public static final String FLAG_HUMAN        = "FLAG_HUMAN";
    public static final String RESTART_CONTEXT   = "RESTART_CONTEXT";

    // Protocol-level statuses
    public static final String STATUS_OPEN      = "OPEN";
    public static final String STATUS_ESCALATED = "ESCALATED";
}
```

**ConversationState** — defensive immutability pattern (same as existing `ReviewState`):

```java
package io.casehub.blocks.conversation;

import java.util.List;
import java.util.Map;

public record ConversationState(
        Map<String, ConversationPoint> points,
        List<FlagEntry> humanFlags,
        List<RoundMemo> memos,
        Map<String, SubTaskFinding> subTaskFindings) {

    public ConversationState {
        points = Map.copyOf(points);
        humanFlags = List.copyOf(humanFlags);
        memos = List.copyOf(memos);
        subTaskFindings = Map.copyOf(subTaskFindings);
    }
}
```

All other records follow the same pattern — see current DraftHouse sources for field lists.

- [ ] **Step 1: Write ConversationState immutability tests**

```java
package io.casehub.blocks.conversation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConversationStateTest {

    @Test
    void defensiveCopy_pointsMapIsImmutable() {
        var points = new HashMap<String, ConversationPoint>();
        var state = new ConversationState(points, List.of(), List.of(), Map.of());
        points.put("after", null);
        assertThat(state.points()).isEmpty();
        assertThatThrownBy(() -> state.points().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensiveCopy_humanFlagsIsImmutable() {
        var flags = new ArrayList<FlagEntry>();
        var state = new ConversationState(Map.of(), flags, List.of(), Map.of());
        flags.add(new FlagEntry("e1", 1, "REV", "content"));
        assertThat(state.humanFlags()).isEmpty();
        assertThatThrownBy(() -> state.humanFlags().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensiveCopy_memosIsImmutable() {
        var memos = new ArrayList<RoundMemo>();
        var state = new ConversationState(Map.of(), List.of(), memos, Map.of());
        memos.add(new RoundMemo("REV", 1, "memo"));
        assertThat(state.memos()).isEmpty();
    }

    @Test
    void defensiveCopy_subTaskFindingsIsImmutable() {
        var findings = new HashMap<String, SubTaskFinding>();
        var state = new ConversationState(Map.of(), List.of(), List.of(), findings);
        findings.put("after", null);
        assertThat(state.subTaskFindings()).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (classes don't exist yet)**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml test -Dtest=ConversationStateTest -pl .
```

- [ ] **Step 3: Implement all data model types**

Create all 10 source files listed above. Each is a small record or enum — port from DraftHouse with the field transformations in the table above. `ConversationPoint` uses `List.copyOf(thread)` in compact constructor. All nullable String fields (scope, location, pointId, finding, errorReason) stay nullable — no Optional wrapping.

- [ ] **Step 4: Run tests — expect PASS**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml test -Dtest=ConversationStateTest -pl .
```

- [ ] **Step 5: Commit**

```bash
git -C ~/claude/casehub/blocks add src/main/java/io/casehub/blocks/conversation/ src/test/java/io/casehub/blocks/conversation/
git -C ~/claude/casehub/blocks commit -m "feat: add conversation protocol data model and constants

Records: ConversationState, ConversationPoint, ThreadEntry,
PointClassification, FlagEntry, RoundMemo, SubTaskFinding
Enums: Priority (HIGH/MEDIUM/LOW), SubTaskStatus
Constants: ConversationProtocol (meta keys, infrastructure entry types)

Refs casehubio/drafthouse#81"
```

---

### Task 2: ConversationProjection — abstract fold with hook methods

**Files:**
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationProjection.java`
- Create: `src/test/java/io/casehub/blocks/conversation/ConversationProjectionTest.java`

**Interfaces:**
- Consumes: All data model types from Task 1, `ChannelProjection<ConversationState>` from `io.casehub.qhorus.api.spi`, `MessageView` from `io.casehub.qhorus.api.message`, `ChannelMessageMeta` from `io.casehub.blocks.channel`
- Produces: `ConversationProjection` — abstract base class for app-specific projections (consumed by DraftHouse Task 5)

**Abstract class structure:**

```java
package io.casehub.blocks.conversation;

import io.casehub.blocks.channel.ChannelMessageMeta;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.*;

public abstract class ConversationProjection implements ChannelProjection<ConversationState> {

    private static final Logger LOG = System.getLogger(ConversationProjection.class.getName());

    // --- subclass hooks ---
    protected abstract String sentinel();
    protected abstract boolean isPointInitiator(String entryType);
    protected abstract String statusAfter(String entryType);

    @Override
    public ConversationState identity() {
        return new ConversationState(Map.of(), List.of(), List.of(), Map.of());
    }

    @Override
    public ConversationState apply(ConversationState state, MessageView message) {
        // Full dispatch logic per spec §Projection → apply() dispatch logic
        // 1. Parse meta, 2. No meta → unchanged, 3. Extract entryType,
        // 4. RESTART_CONTEXT → unchanged, 5. Extract role,
        // 6. Extract pointId from correlationId,
        // 7. Infrastructure dispatch, 8. Domain dispatch
    }
}
```

The `apply()` method implements the full dispatch logic from the design spec. Key behaviours:
- Uses `ChannelMessageMeta.parseMeta(sentinel(), message.content())` for metadata
- `RESTART_CONTEXT` intercepted before any other dispatch (protocol PP-20260610-073663)
- Infrastructure types (`MEMO`, `SUB_TASK_*`, `FLAG_HUMAN`) handled by base class
- Domain types dispatched via `isPointInitiator()` and `statusAfter()` hooks
- `SUB_TASK_FINDING`/`ERROR` handlers preserve `requestedBy` from original REQUEST (bug fix from design review R2-03)
- `FLAG_HUMAN` without target point → FlagEntry only (design review R2-08)
- Null `correlationId` on point initiation → state unchanged (design review R3-03)
- Priority parsed defensively: unknown/null → `Priority.LOW` (design review R2-07)
- Never throws from `apply()` (protocol PP-20260610-a47ef5)

**Test subclass:**

```java
class TestConversationProjection extends ConversationProjection {
    @Override protected String sentinel() { return "TEST:"; }
    @Override protected boolean isPointInitiator(String entryType) {
        return "OPEN_TOPIC".equals(entryType);
    }
    @Override protected String statusAfter(String entryType) {
        return switch (entryType) {
            case "ACCEPT" -> "ACCEPTED";
            case "REJECT" -> "REJECTED";
            case "CHALLENGE" -> "CHALLENGED";
            default -> null;
        };
    }
}
```

- [ ] **Step 1: Write projection tests (~20 tests)**

Test categories (see spec §Testing for full list):
- Point initiation: `OPEN_TOPIC` → creates point with OPEN status
- Point response: `ACCEPT` → appends to thread, status ACCEPTED
- Unknown domain type: `statusAfter()` null → appends, status unchanged
- Infrastructure: MEMO, SUB_TASK_REQUEST/FINDING/ERROR, FLAG_HUMAN
- Bug fix: SUB_TASK_FINDING preserves requestedBy
- Bug fix: SUB_TASK_ERROR preserves requestedBy
- FLAG_HUMAN with/without target point
- RESTART_CONTEXT → transparent
- Missing metadata → unchanged
- Missing role → unchanged with log
- Null correlationId on initiation → unchanged
- Response targeting non-existent point → unchanged
- Multi-point accumulation

Helper method to create MessageView mocks:

```java
private MessageView message(String content, String correlationId) {
    var msg = mock(MessageView.class);
    when(msg.content()).thenReturn(content);
    when(msg.correlationId()).thenReturn(correlationId);
    return msg;
}

private String encode(Map<String, String> meta, String body) {
    return ChannelMessageMeta.encode("TEST:", meta, body);
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml test -Dtest=ConversationProjectionTest -pl .
```

- [ ] **Step 3: Implement ConversationProjection**

Full implementation of `apply()` with all dispatch logic. Build immutable state transitions using new record instances (same pattern as current `DebateChannelProjection` — `new ConversationState(updatedPoints, ...)`).

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Run all blocks tests to check no regressions**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml test
```

- [ ] **Step 6: Commit**

```bash
git -C ~/claude/casehub/blocks commit -am "feat: add ConversationProjection abstract fold

Abstract base class implementing ChannelProjection<ConversationState>.
Three hook methods: sentinel(), isPointInitiator(), statusAfter().
Handles all infrastructure dispatch (MEMO, SUB_TASK_*, FLAG_HUMAN,
RESTART_CONTEXT). Fixes requestedBy preservation bug (R2-03).

Refs casehubio/drafthouse#81"
```

---

### Task 3: ConversationRenderer + ConversationRendererConfig

**Files:**
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationRendererConfig.java`
- Create: `src/main/java/io/casehub/blocks/conversation/ConversationRenderer.java`
- Create: `src/test/java/io/casehub/blocks/conversation/ConversationRendererTest.java`

**Interfaces:**
- Consumes: All data model types from Task 1
- Produces: `ConversationRenderer` + `ConversationRendererConfig` — consumed by DraftHouse Task 5

**ConversationRendererConfig** — builder pattern:

```java
public record ConversationRendererConfig(
        Map<String, String> statusEmoji,
        Map<Priority, String> priorityLabel,
        Map<String, String> entryTypeLabel,
        Map<String, String> roleLabel,
        Set<String> resolvedStatuses,
        Set<String> escalatedStatuses) {

    public ConversationRendererConfig {
        statusEmoji = Map.copyOf(statusEmoji);
        priorityLabel = Map.copyOf(priorityLabel);
        entryTypeLabel = Map.copyOf(entryTypeLabel);
        roleLabel = Map.copyOf(roleLabel);
        resolvedStatuses = Set.copyOf(resolvedStatuses);
        escalatedStatuses = Set.copyOf(escalatedStatuses);
    }

    public static Builder builder() { return new Builder(); }
    // Builder with defaults (empty maps/sets)
}
```

**ConversationRenderer** — pure function `render(ConversationState) → String`:

```java
public class ConversationRenderer {
    private static final String DEFAULT_EMOJI = "⬜"; // ⬜
    private final ConversationRendererConfig config;

    public ConversationRenderer(ConversationRendererConfig config) {
        this.config = config;
    }

    public String render(ConversationState state) {
        // 1. Group points: unresolved, escalated, resolved
        // 2. Render each group
        // 3. Render standalone sub-task findings
        // 4. Render human flags
        // 5. Render memos grouped by round
    }
}
```

Port rendering logic from `SummaryRenderer` (108 lines in `server/api/.../debate/SummaryRenderer.java`) with these changes:
- All enum lookups → config map lookups with defaults
- No clock/timestamp (pure — design review R1-11)
- Grouping by resolved/escalated/unresolved (config-driven)
- Within-group order: insertion order (chronological)
- Strikethrough (`~~`) for resolved points

- [ ] **Step 1: Write renderer tests (~10 tests)**

Test categories:
- Empty state → minimal output
- Single point with thread → correct formatting
- Points grouped: unresolved before resolved
- Strikethrough on resolved points
- Emoji from config (known → configured, unknown → ⬜)
- Priority label from config (HIGH→"P1" when configured)
- Entry type label from config
- Role label from config
- Default config → raw strings
- Sub-task findings: point-specific inline, standalone separate
- Human flags section
- Memos grouped by round

- [ ] **Step 2: Run tests — expect FAIL**
- [ ] **Step 3: Implement ConversationRendererConfig + ConversationRenderer**
- [ ] **Step 4: Run tests — expect PASS**
- [ ] **Step 5: Run all blocks tests**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml test
```

- [ ] **Step 6: Install blocks to local Maven repo**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/blocks/pom.xml install -DskipTests
```

DraftHouse depends on `casehub-blocks:0.2-SNAPSHOT`. This `install` makes the new conversation package available.

- [ ] **Step 7: Commit**

```bash
git -C ~/claude/casehub/blocks commit -am "feat: add ConversationRenderer with configurable vocabulary

Pure renderer: ConversationState → String (markdown).
Config-driven: statusEmoji, priorityLabel, entryTypeLabel, roleLabel,
resolvedStatuses, escalatedStatuses. No clock (pure function).
Points grouped by resolution status, insertion order within groups.

Refs casehubio/drafthouse#81"
```

---

### Task 4: DraftHouse server/api migration — delete moved types

All work in casehub-drafthouse (`~/claude/casehub/drafthouse`).

**Files:**
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewPoint.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/ThreadEntry.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/PointClassification.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/Priority.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/Scope.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/FlagEntry.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskFinding.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskStatus.java`
- Delete: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewConversationRenderer.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionSnapshot.java`

**Interfaces:**
- Consumes: blocks `io.casehub.blocks.conversation.*`
- Produces: Clean server/api with blocks imports

**ReviewConversationRenderer migration:**
- `ReviewState` → `ConversationState`
- `ReviewPoint` → `ConversationPoint`
- `ReviewStatus.AGREED` → `"AGREED".equals(p.status())`
- `ReviewStatus.DECLINED` → `"DECLINED".equals(p.status())`
- `EntryType.RAISE` → `"RAISE".equals(e.entryType())`
- `e.agent().name()` → `e.role()`
- `e.type().name().toLowerCase()` → config-driven or raw string

**DebateSession/DebateSessionSnapshot:**
- `import io.casehub.drafthouse.debate.ReviewState` → removed (ReviewState no longer referenced directly in session — check actual usage)

**EntryType stays** — kept as app-level constants for MCP tool compile-time convenience. No changes needed.

**AgentType stays** — no changes.

- [ ] **Step 1: Delete the 13 moved source files**
- [ ] **Step 2: Update ReviewConversationRenderer imports and type references**
- [ ] **Step 3: Update DebateSession/DebateSessionSnapshot if they reference deleted types**
- [ ] **Step 4: Verify server/api compiles (server/runtime will NOT compile yet — expected)**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml compile -pl api
```

- [ ] **Step 5: Commit**

```bash
git -C ~/claude/casehub/drafthouse commit -am "refactor: delete types moved to casehub-blocks conversation package

Removed: ReviewState, ReviewPoint, ThreadEntry, PointClassification,
Priority, Scope, ReviewStatus, FlagEntry, RoundMemo, SubTaskFinding,
SubTaskType, SubTaskStatus, SummaryRenderer.
Updated: ReviewConversationRenderer to use blocks types.
Kept: EntryType (app constants), AgentType (DraftHouse roles).

Refs #81"
```

---

### Task 5: DraftHouse runtime — projection core

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateStreamEntry.java`
- Modify: `server/runtime/src/test/java/.../debate/DebateChannelProjectionTest.java`
- Modify: `server/runtime/src/test/java/.../DebateStreamEntryTest.java`

**Interfaces:**
- Consumes: `ConversationProjection`, `ConversationState`, `ConversationRenderer`, `ConversationRendererConfig` from blocks
- Produces: Thin `DebateChannelProjection` subclass used by Tasks 6-7

**DebateChannelProjection** — replaces 253-line file with ~40 lines:

```java
@ApplicationScoped
public class DebateChannelProjection extends ConversationProjection
        implements RenderableProjection<ConversationState> {

    private static final ConversationRendererConfig DEBATE_CONFIG =
        ConversationRendererConfig.builder()
            .statusEmoji(Map.of("OPEN", "🔴", "ACTIVE", "🟡",
                "AGREED", "✅", "ESCALATED", "🔵",
                "DECLINED", "🚫", "DISPUTED", "⚡"))
            .resolvedStatuses(Set.of("AGREED", "DECLINED"))
            .escalatedStatuses(Set.of("ESCALATED"))
            .priorityLabel(Map.of(Priority.HIGH, "P1", Priority.MEDIUM, "P2", Priority.LOW, "P3"))
            .entryTypeLabel(Map.of("RAISE", "raised", "AGREE", "agreed",
                "COUNTER", "countered", "DISPUTE", "disputed", "QUALIFY", "qualified",
                "FLAG_HUMAN", "flag", "DECLINED", "declined"))
            .roleLabel(Map.of("REV", "REV", "IMP", "IMP"))
            .build();

    private final ConversationRenderer renderer = new ConversationRenderer(DEBATE_CONFIG);

    @Override public String projectionName() { return "debate-summary"; }

    @Override public String render(ProjectionResult<ConversationState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    public String renderState(ConversationState state) {
        return renderer.render(state);
    }

    @Override protected String sentinel() { return DebateProtocol.META_SENTINEL; }

    @Override protected boolean isPointInitiator(String entryType) {
        return "RAISE".equals(entryType);
    }

    @Override protected String statusAfter(String entryType) {
        return switch (entryType) {
            case "AGREE" -> "AGREED";
            case "COUNTER", "QUALIFY" -> "ACTIVE";
            case "DISPUTE" -> "DISPUTED";
            case "DECLINED" -> "DECLINED";
            default -> null;
        };
    }

    // RoundBoundedProjection inner class stays — uses BoundedProjectionDecorator<ConversationState>
}
```

**DebateProtocol** — thins to sentinel + convenience:

```java
public final class DebateProtocol {
    private DebateProtocol() {}
    public static final String META_SENTINEL = "DHMETA:";

    public static Map<String, String> parseMeta(String content) {
        return ChannelMessageMeta.parseMeta(META_SENTINEL, content);
    }
    public static int parseRound(Map<String, String> meta) {
        return ChannelMessageMeta.parseInt(meta, ConversationProtocol.ROUND);
    }
    public static String bodyContent(String content) {
        return ChannelMessageMeta.bodyContent(META_SENTINEL, content);
    }
}
```

**DebateStreamEntry** — update field types:
- `Scope scope` → `String scope`
- `Priority` import → `io.casehub.blocks.conversation.Priority`
- `parsePriority()` → map `"P1"/"P2"/"P3"` as legacy AND `"HIGH"/"MEDIUM"/"LOW"` as current
- `parseScope()` → direct string extraction
- `meta.get("agent")` → `meta.get(ConversationProtocol.ROLE)` — but note: wire key changes from `"agent"` to `"role"`, so during transition this needs to handle both keys

- [ ] **Step 1: Update DebateChannelProjection to extend ConversationProjection**
- [ ] **Step 2: Update DebateProtocol**
- [ ] **Step 3: Update DebateStreamEntry**
- [ ] **Step 4: Adapt DebateChannelProjectionTest — slim to hook-mapping tests only; bulk tests are now in blocks**
- [ ] **Step 5: Adapt DebateStreamEntryTest**
- [ ] **Step 6: Compile check**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml compile -pl api,runtime
```

- [ ] **Step 7: Run adapted tests**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime -Dtest="DebateChannelProjectionTest,DebateStreamEntryTest"
```

- [ ] **Step 8: Commit**

```bash
git -C ~/claude/casehub/drafthouse commit -am "refactor: DebateChannelProjection extends ConversationProjection

253-line projection → ~40-line subclass with 3 hook methods.
DebateProtocol thinned to sentinel + convenience wrappers.
DebateStreamEntry updated for blocks types.

Refs #81"
```

---

### Task 6: DraftHouse runtime — handler package

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/ArbitrateHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/ConsistencyCheckHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/CustomHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/DeepAnalysisHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/NeutralSummaryHandler.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/VerifyHandler.java`
- Modify: corresponding test files (6 handler tests)

**Interfaces:**
- Consumes: blocks types from Task 1, `DebateChannelProjection` from Task 5

**Transformation pattern** (applied to all 7 handlers):

| Before | After |
|---|---|
| `abstract SubTaskType taskType()` | `abstract String taskType()` |
| `SubTaskType.VERIFY` | `"VERIFY"` |
| `handles()`: `SubTaskType.valueOf(meta.get("taskType")) == taskType()` | `taskType().equals(meta.get(ConversationProtocol.TASK_TYPE))` |
| `buildResponse()`: `meta.get("agent")` | `meta.get(ConversationProtocol.ROLE)` |
| `buildResponse()`: `taskType().name()` | `taskType()` |
| `buildResponse()`: manual string concat | `ChannelMessageMeta.encode(...)` |
| `currentState()` returns `ReviewState` | returns `ConversationState` |
| `requirePoint()` returns `ReviewPoint` | returns `ConversationPoint` |
| `e.type() == EntryType.DISPUTE` | `"DISPUTE".equals(e.entryType())` |
| `e.agent().name()` | `e.role()` |
| `e.type().name()` | `e.entryType()` |
| `p.currentStatus() == ReviewStatus.AGREED` | `"AGREED".equals(p.status())` |

- [ ] **Step 1: Update AbstractDebateSubAgentHandler**
- [ ] **Step 2: Update all 6 concrete handlers**
- [ ] **Step 3: Adapt handler tests — same transformation pattern (construct blocks types)**
- [ ] **Step 4: Run handler tests**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime -Dtest="ArbitrateHandlerTest,ConsistencyCheckHandlerTest,CustomHandlerTest,DeepAnalysisHandlerTest,NeutralSummaryHandlerTest,VerifyHandlerTest"
```

- [ ] **Step 5: Commit**

```bash
git -C ~/claude/casehub/drafthouse commit -am "refactor: migrate handler package to blocks conversation types

All 7 handlers: enum dispatch → string dispatch, blocks record types,
ChannelMessageMeta.encode() for response encoding.

Refs #81"
```

---

### Task 7: DraftHouse runtime — review channel + MCP tools

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewChannelProjection.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: corresponding test files

**ReviewChannelProjection** — does NOT extend ConversationProjection (dispatches on `message.type()`, not META headers). Changes:
- `ReviewState` → `ConversationState`
- `ReviewPoint` → `ConversationPoint`
- `ReviewStatus.*` → string constants
- `EntryType.*` → string constants
- `new ThreadEntry(null, agentType, 0, EntryType.RAISE, content)` → `new ThreadEntry(null, role, 0, "RAISE", content)` where `role` is derived from `message.actorType()` (HUMAN→"REV", AGENT→"IMP")
- `Priority.P1` → `Priority.HIGH`, etc.
- `Scope.SYSTEMIC` → `"SYSTEMIC"`

**ReviewerChannelBackend** — type updates:
- `ChannelProjection<ReviewState>` → `ChannelProjection<ConversationState>`
- `ReviewState` → `ConversationState` in projection result usage

**DebateMcpTools** (767 lines) — the largest migration. Key changes:
- All `ReviewState` → `ConversationState`
- All `SubTaskStatus.*` → `io.casehub.blocks.conversation.SubTaskStatus.*`
- All `SubTaskFinding` → blocks `SubTaskFinding` (field `requestingAgent` → `requestedBy`)
- Encoding: manual string concat → `ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, body)`
- Wire key: `"agent"` → `ConversationProtocol.ROLE` (i.e., `"role"`)
- `renderBounded()`: `new SummaryRenderer().render(state)` → `debateProjection.renderState(state)`
- `Priority.P1` → `Priority.HIGH`, etc.
- `SubTaskType.VERIFY` → `"VERIFY"`, etc.
- Emptiness checks: `s.points().isEmpty() && s.memos().isEmpty() && s.subTaskFindings().isEmpty()` (protocol PP-20260610-60a67a)

- [ ] **Step 1: Update ReviewChannelProjection**
- [ ] **Step 2: Update ReviewerChannelBackend**
- [ ] **Step 3: Update DebateMcpTools**
- [ ] **Step 4: Compile check**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml compile -pl api,runtime
```

- [ ] **Step 5: Adapt ReviewChannelProjectionTest**
- [ ] **Step 6: Adapt ReviewerChannelBackendTest**
- [ ] **Step 7: Adapt DebateMcpToolsTest**
- [ ] **Step 8: Run all adapted tests**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime -Dtest="ReviewChannelProjectionTest,ReviewerChannelBackendTest,DebateMcpToolsTest"
```

- [ ] **Step 9: Commit**

```bash
git -C ~/claude/casehub/drafthouse commit -am "refactor: migrate review channel and MCP tools to blocks types

ReviewChannelProjection: blocks types (does not extend ConversationProjection).
ReviewerChannelBackend: ConversationState projection result.
DebateMcpTools: blocks types, ChannelMessageMeta.encode(), wire key
'agent'→'role', renderBounded via debateProjection.renderState().

Refs #81"
```

---

### Task 8: Full build verification + E2E

- [ ] **Step 1: Full DraftHouse build (compile + all unit tests)**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime
```

- [ ] **Step 2: Fix any remaining compilation errors or test failures**

Common issues to watch for:
- Import paths not updated (search for `io.casehub.drafthouse.debate.ReviewState` etc.)
- `SubTaskFinding` field name change (`requestingAgent` → `requestedBy`)
- `ThreadEntry` accessor changes (`.agent()` → `.role()`, `.type()` → `.entryType()`)
- `Priority.P1` references anywhere
- `ReviewStatus` references anywhere
- `Scope.SYSTEMIC`/`Scope.ISOLATED` references anywhere

- [ ] **Step 3: Run E2E tests**

```bash
/opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime -Dtest="*E2ETest"
```

- [ ] **Step 4: Commit any fixes**

- [ ] **Step 5: Update protocols that reference deleted types**

Review `docs/protocols/` for references to `ReviewState`, `SummaryRenderer`, `EntryType.valueOf()`, `ReviewStatus`, `Scope`. Update affected protocols to reference blocks types. Key protocols to check:
- `channel-projection-actor-type.md` — references `AgentType` (stays, no change)
- `channel-projection-apply-must-not-throw.md` — code examples use `AgentType.valueOf()` → update example to show string-based parsing
- `debate-restart-context-not-entry-type.md` — references `EntryType.valueOf()` → update to show string dispatch
- `debate-message-sentinel-encoding.md` — references `DebateProtocol.META_SENTINEL` (stays, no change)
- `filtering-projection-content-check.md` — references `ReviewState` → update to `ConversationState`

- [ ] **Step 6: Commit protocol updates**

---

## Verification

After all tasks complete:

1. **Blocks repo:** `mvn -f ~/claude/casehub/blocks/pom.xml test` — all tests pass including new conversation package tests
2. **DraftHouse repo:** `mvn -f ~/claude/casehub/drafthouse/server/pom.xml install -DskipTests && mvn -f ~/claude/casehub/drafthouse/server/pom.xml test -pl runtime` — all unit + E2E tests pass
3. **No DraftHouse types leak into blocks:** `grep -r "drafthouse" ~/claude/casehub/blocks/src/` returns nothing
4. **No deleted type references remain:** search for `ReviewState`, `ReviewPoint`, `ReviewStatus`, `SubTaskType`, `Scope` in DraftHouse Java sources — none found (except in `EntryType.java` and `AgentType.java` which are intentionally kept)
5. **Protocol coherence:** all 5 DraftHouse protocols still accurately describe the current code
