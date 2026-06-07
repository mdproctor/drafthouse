# Debate Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the debate channel write path — MCP tools for REV/IMP agents to post structured debate entries into a Qhorus channel, projection split to fix the review channel regression, and all supporting domain model changes.

**Architecture:** Split the misnamed `DebateChannelProjection` into `ReviewChannelProjection` (review Q&A, `message.type()` dispatch) and a new `DebateChannelProjection` (`artefactRefs.entryType` dispatch). Add `DebateMcpTools` with six tools (start/raise/respond/flag/summary/end), backed by `DebateSessionRegistry` and a no-op `DebateChannelBackend` as a registration fence.

**Tech Stack:** Java 17, Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT, LangChain4j 1.9.1, JUnit 5, Mockito, AssertJ. Maven multi-module: `server/api/` (pure Java domain) and `server/runtime/` (Quarkus app).

**Spec:** `docs/superpowers/specs/2026-06-07-debate-channel-design.md`

**Run all tests:**
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

**Run api-module tests only:**
```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api
```

**Run a single test class:**
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ClassName
```

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java` | Modify | Add `COUNTER` |
| `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java` | Modify | Add `DISPUTED` |
| `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java` | Modify | Add `COUNTER`/`DISPUTED` switch cases |
| `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java` | Create | Session record |
| `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java` | Create | Registry interface |
| `server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java` | Modify | Add 2 tests |
| `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseInstances.java` | Create | Shared constants |
| `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java` | Modify | Remove `HUMAN_INSTANCE_ID` field, use `DraftHouseInstances` |
| `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewChannelProjection.java` | Create (rename) | `ChannelProjection<ReviewState>` only |
| `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` | Replace | New `RenderableProjection`, `artefactRefs` dispatch |
| `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java` | Modify | Add debate channel guard |
| `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java` | Create | `ConcurrentHashMap` impl |
| `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java` | Create | No-op `@ApplicationScoped` backend |
| `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackendFactory.java` | Create | Registers backend on `ChannelInitialisedEvent` |
| `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` | Create | 6 MCP debate tools |
| `server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewChannelProjectionTest.java` | Create (rename) | 14 surviving tests |
| `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java` | Create (new) | ~12 tests |
| `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java` | Modify | Update `HUMAN_INSTANCE_ID` ref |
| `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java` | Modify | Update 5 `HUMAN_INSTANCE_ID` refs |
| `server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java` | Create | 2 routing tests |
| `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java` | Create | ~15 unit tests |
| `server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java` | Create | 2 integration tests |

---

## Task 1: Domain enum additions + SummaryRenderer (api module)

`EntryType.COUNTER` and `ReviewStatus.DISPUTED` are needed by every subsequent task. Adding them first keeps the build green throughout. The `SummaryRenderer` exhaustive switches must be updated in the same commit — the compiler enforces this.

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`
- Test: `server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java`

- [ ] **Step 1.1: Add failing SummaryRenderer tests**

Append to `SummaryRendererTest.java` (after the last existing `@Test`):

```java
    @Test
    void rendersDisputedPoint_withLightningMarker_noStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-IMP-001", new ReviewPoint("R1-IMP-001",
                new PointClassification(Priority.P2, Scope.ISOLATED, null),
                List.of(new ThreadEntry("R1-IMP-001", AgentType.IMP, 1, EntryType.RAISE, "Counter point.")),
                ReviewStatus.DISPUTED)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("⚡");
        assertThat(output).doesNotContain("~~");
    }

    @Test
    void rendersCounterEntryType_withCounterLabel() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 1, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.IMP, 2, EntryType.COUNTER, "My counter.")),
                ReviewStatus.ACTIVE)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("counter");
        assertThat(output).contains("My counter.");
    }
```

- [ ] **Step 1.2: Run tests — expect compile failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compile error — `COUNTER` and `DISPUTED` not defined.

- [ ] **Step 1.3: Add COUNTER to EntryType**

Replace the enum body in `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`:

```java
public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED
}
```

- [ ] **Step 1.4: Add DISPUTED to ReviewStatus**

Replace the enum body in `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java`:

```java
public enum ReviewStatus {
    OPEN, ACTIVE, AGREED, PENDING_HUMAN, DECLINED, DISPUTED
}
```

- [ ] **Step 1.5: Update SummaryRenderer — both exhaustive switches**

In `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`, replace the `render` method body with:

```java
    public String render(ReviewState state) {
        var sb = new StringBuilder();
        sb.append("# Review Summary\n");
        sb.append("**Updated:** ").append(clock.get()).append("\n\n---\n\n");

        for (ReviewPoint point : state.points().values()) {
            String statusMarker = switch (point.currentStatus()) {
                case OPEN          -> "🔴";
                case ACTIVE        -> "🟡";
                case AGREED        -> "✅";
                case PENDING_HUMAN -> "🔵";
                case DECLINED      -> "🚫";
                case DISPUTED      -> "⚡";
            };

            String firstContent = point.thread().isEmpty() ? "" : point.thread().get(0).content();
            String header = "[" + point.id() + "] "
                    + point.classification().priority() + " · "
                    + point.classification().scope()
                    + (point.classification().location() != null
                       ? " · " + point.classification().location() : "")
                    + " — " + firstContent;

            boolean strikethrough = point.currentStatus() == ReviewStatus.AGREED
                    || point.currentStatus() == ReviewStatus.DECLINED;
            if (strikethrough) {
                sb.append("## ").append(statusMarker).append(" ~~").append(header).append("~~\n");
            } else {
                sb.append("## ").append(statusMarker).append(" ").append(header).append("\n");
            }

            for (ThreadEntry entry : point.thread()) {
                String typeLabel = switch (entry.type()) {
                    case RAISE      -> "raise";
                    case AGREE      -> "agree";
                    case COUNTER    -> "counter";
                    case DISPUTE    -> "dispute";
                    case QUALIFY    -> "qualify";
                    case FLAG_HUMAN -> "flag";
                    case DECLINED   -> "declined";
                };
                sb.append("> **").append(entry.agent()).append(" (").append(typeLabel).append("):** ")
                  .append(entry.content()).append("\n");
            }
            sb.append("\n---\n\n");
        }

        if (!state.humanFlags().isEmpty()) {
            sb.append("⚑ **Human review needed:**\n");
            for (FlagEntry flag : state.humanFlags()) {
                sb.append("- ").append(flag.content()).append("\n");
            }
        }
        return sb.toString();
    }
```

- [ ] **Step 1.6: Run api tests — expect all pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api
```

Expected: BUILD SUCCESS, all api tests green.

- [ ] **Step 1.7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add EntryType.COUNTER, ReviewStatus.DISPUTED, SummaryRenderer cases  Refs #27"
```

---

## Task 2: DebateSession + DebateSessionRegistry + DebateSessionRegistryImpl

These are the session model types. All tools and tests depend on them.

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java`

- [ ] **Step 2.1: Create DebateSession record**

```java
// server/api/src/main/java/io/casehub/drafthouse/DebateSession.java
package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active debate session.
 *
 * channelName is stored explicitly — it cannot be reconstructed from debateSessionId alone.
 * revInstanceId and impInstanceId identify the two debate agents in Qhorus.
 */
public record DebateSession(
        UUID channelId,         // registry key; also UUID.fromString(debateSessionId)
        String debateSessionId, // channelId.toString() — the caller's stable handle
        String channelName,     // "drafthouse/debate/d-{uuid}" — needed by end_debate for deletion
        String revInstanceId,   // "drafthouse-rev-{debateSessionId}"
        String impInstanceId    // "drafthouse-imp-{debateSessionId}"
) {}
```

- [ ] **Step 2.2: Create DebateSessionRegistry interface**

```java
// server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java
package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safety: implementations must be safe for concurrent access.
 */
public interface DebateSessionRegistry {

    /** Returns the session for the given channel, or empty if no session is active. */
    Optional<DebateSession> find(UUID channelId);

    /** Registers a new session. Replaces any existing session for the same channelId. */
    void put(DebateSession session);

    /** Removes the session for the given channel. No-op if not found. */
    void remove(UUID channelId);
}
```

- [ ] **Step 2.3: Create DebateSessionRegistryImpl**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java
package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safe via ConcurrentHashMap.
 */
@ApplicationScoped
public class DebateSessionRegistryImpl implements DebateSessionRegistry {

    private final ConcurrentHashMap<UUID, DebateSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<DebateSession> find(final UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(final DebateSession session) {
        sessions.put(session.channelId(), session);
    }

    @Override
    public void remove(final UUID channelId) {
        sessions.remove(channelId);
    }
}
```

- [ ] **Step 2.4: Build to verify compile**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 2.5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/DebateSession.java server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateSession record, DebateSessionRegistry SPI, DebateSessionRegistryImpl  Refs #27"
```

---

## Task 3: DraftHouseInstances + migrate HUMAN_INSTANCE_ID

Extract the constant and update all six call sites that would otherwise be compile failures.

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseInstances.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java`

- [ ] **Step 3.1: Create DraftHouseInstances**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseInstances.java
package io.casehub.drafthouse;

/** Well-known Qhorus instance IDs registered at startup by DraftHouseMcpTools. */
final class DraftHouseInstances {
    static final String HUMAN_INSTANCE_ID = "drafthouse-human";
    private DraftHouseInstances() {}
}
```

- [ ] **Step 3.2: Update DraftHouseMcpTools — remove field, use DraftHouseInstances**

In `DraftHouseMcpTools.java`:
- Remove line: `static final String HUMAN_INSTANCE_ID = "drafthouse-human";`
- Replace `HUMAN_INSTANCE_ID` with `DraftHouseInstances.HUMAN_INSTANCE_ID` everywhere in the class (two occurrences: `registerHumanInstance()` and `queryReview()`)

The `registerHumanInstance` method becomes:
```java
    @PostConstruct
    void registerHumanInstance() {
        instanceService.register(DraftHouseInstances.HUMAN_INSTANCE_ID, "DraftHouse human reviewer",
                List.of("document-review-human"));
    }
```

And in `queryReview`:
```java
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content(question)
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());
```

- [ ] **Step 3.3: Update DraftHouseMcpToolsTest — line 229**

Replace:
```java
assertThat(d.sender()).isEqualTo(DraftHouseMcpTools.HUMAN_INSTANCE_ID);
```
With:
```java
assertThat(d.sender()).isEqualTo(DraftHouseInstances.HUMAN_INSTANCE_ID);
```

- [ ] **Step 3.4: Update ReviewSessionLifecycleTest — 5 occurrences**

Replace all 5 occurrences of `.sender(DraftHouseMcpTools.HUMAN_INSTANCE_ID)` with `.sender(DraftHouseInstances.HUMAN_INSTANCE_ID)` in `ReviewSessionLifecycleTest.java`.

- [ ] **Step 3.5: Run full test suite — verify no regressions**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="DraftHouseMcpToolsTest,ReviewSessionLifecycleTest"
```

Expected: both test classes green.

- [ ] **Step 3.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseInstances.java server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor: extract DraftHouseInstances.HUMAN_INSTANCE_ID from DraftHouseMcpTools  Refs #27"
```

---

## Task 4: Rename DebateChannelProjection → ReviewChannelProjection

This is a rename refactor. The existing class stays almost unchanged — only the interface narrows from `RenderableProjection` to `ChannelProjection`. The test file is renamed and 3 tests are deleted.

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewChannelProjection.java` (from DebateChannelProjection)
- Delete: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` (will be recreated in Task 5)
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewChannelProjectionTest.java` (from DebateChannelProjectionTest)
- Delete: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`

- [ ] **Step 4.1: Create ReviewChannelProjection (copy + modify)**

Create `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewChannelProjection.java`:

```java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds review Q&A channel messages into ReviewState.
 * Dispatch is on message.type() — review channels never carry artefactRefs.
 * Agent classification uses message.actorType(): HUMAN→REV, AGENT→IMP.
 *
 * Does NOT implement RenderableProjection — ReviewerChannelBackend calls
 * ReviewConversationRenderer.render() directly; projection.render() is never called.
 */
@ApplicationScoped
public class ReviewChannelProjection implements ChannelProjection<ReviewState> {

    private static final System.Logger LOG = System.getLogger(ReviewChannelProjection.class.getName());

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        return switch (message.type()) {
            case QUERY    -> handleRaise(state, message);
            case RESPONSE -> handleResponse(state, message);
            case DECLINE  -> handleDecline(state, message);
            case HANDOFF  -> handleFlagHuman(state, message);
            default       -> state;
        };
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ReviewState handleRaise(ReviewState state, MessageView message) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agentType(message), 0, EntryType.RAISE, message.content()));
        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleResponse(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        boolean isQualify = message.content() != null
                && message.content().startsWith("[QUALIFY] ");
        String content = isQualify
                ? message.content().substring("[QUALIFY] ".length())
                : message.content();
        EntryType entryType = isQualify ? EntryType.QUALIFY : EntryType.AGREE;
        ReviewStatus newStatus = isQualify ? ReviewStatus.ACTIVE : ReviewStatus.AGREED;
        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message), 0, entryType, content));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleDecline(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Decline references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.DECLINED,
                Objects.requireNonNullElse(message.content(), "")));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, ReviewStatus.DECLINED);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        String content = Objects.requireNonNullElse(message.content(), "");
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.FLAG_HUMAN, content));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, agentType(message), content));
        return new ReviewState(points, flags);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AgentType agentType(MessageView message) {
        if (message.actorType() == null) {
            throw new IllegalArgumentException(
                    "MessageView.actorType() must not be null in ReviewChannelProjection");
        }
        return switch (message.actorType()) {
            case HUMAN -> AgentType.REV;
            case AGENT -> AgentType.IMP;
            default    -> throw new IllegalArgumentException(
                    "Unsupported actorType in review projection: " + message.actorType());
        };
    }

    private Map<String, String> parseArtefacts(String artefacts) {
        Map<String, String> map = new HashMap<>();
        for (String part : artefacts.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        return map;
    }

    private Priority parsePriority(String s) {
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.P3; }
    }

    private Scope parseScope(String s) {
        try { return Scope.valueOf(s.toUpperCase()); } catch (Exception e) { return Scope.ISOLATED; }
    }
}
```

- [ ] **Step 4.2: Create ReviewChannelProjectionTest (14 tests, 3 deleted)**

Create `server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewChannelProjectionTest.java`. This is the existing `DebateChannelProjectionTest` content with: class renamed to `ReviewChannelProjectionTest`, field changed to `new ReviewChannelProjection()`, and three tests removed (`projectionName_returnsDebateSummary`, `render_emptyResult_returnsNonBlankSentinel`, `render_nonEmptyResult_returnsNonBlankString`).

```java
package io.casehub.drafthouse.debate;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewChannelProjectionTest {

    private final ReviewChannelProjection proj = new ReviewChannelProjection();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageView query(String correlationId, ActorType actorType,
                                     Priority p, Scope s, String loc, String content) {
        String artefactRefs = "entryId=" + correlationId + "|priority=" + p
                + "|scope=" + s + "|location=" + (loc != null ? loc : "");
        return new MessageView(null, null, "test", MessageType.QUERY,
                content, correlationId, null, null, artefactRefs, actorType, null, null, 0);
    }

    private static MessageView respond(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.RESPONSE,
                content, correlationId, null, null, null, actorType, null, null, 0);
    }

    private static MessageView decline(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.DECLINE,
                content, correlationId, null, null, null, actorType, null, null, 0);
    }

    private static MessageView handoff(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.HANDOFF,
                content, correlationId, null, "human", null, actorType, null, null, 0);
    }

    private static MessageView event(ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.EVENT,
                content, null, null, null, null, actorType, null, null, 0);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void identity_returnsEmptyState_andFreshInstanceEachCall() {
        ReviewState s1 = proj.identity();
        ReviewState s2 = proj.identity();
        assertThat(s1.points()).isEmpty();
        assertThat(s1.humanFlags()).isEmpty();
        assertThat(s1).isNotSameAs(s2);
    }

    @Test
    void apply_query_createsOpenPoint_humanMapsToRev() {
        ReviewState s = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, "§3.2", "Point A."));
        assertThat(s.points()).containsKey("R1");
        ReviewPoint p = s.points().get("R1");
        assertThat(p.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(p.classification().priority()).isEqualTo(Priority.P1);
        assertThat(p.thread()).hasSize(1);
        assertThat(p.thread().get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(p.thread().get(0).agent()).isEqualTo(AgentType.REV);
    }

    @Test
    void apply_response_agree_transitionsToAgreed_agentMapsToImp() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, respond("R1", ActorType.AGENT, "Answer."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("R1").thread().get(1).agent()).isEqualTo(AgentType.IMP);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.AGREE);
    }

    @Test
    void apply_response_qualify_transitionsToActive_stripsPrefix() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, respond("R1", ActorType.AGENT, "[QUALIFY] Partial."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
        assertThat(s1.points().get("R1").thread().get(1).content()).isEqualTo("Partial.");
    }

    @Test
    void apply_decline_transitionsToDeclined() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, decline("R1", ActorType.AGENT, "Out of scope."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.DECLINED);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.DECLINED);
    }

    @Test
    void apply_decline_nullCorrelationId_stateUnchanged_noLog() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0, decline(null, ActorType.AGENT, "Out of scope."));
        assertThat(s1).isSameAs(s0);
    }

    @Test
    void apply_decline_unknownCorrelationId_stateUnchanged() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0, decline("UNKNOWN", ActorType.AGENT, "Out of scope."));
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void apply_decline_nullContent_threadEntryIsEmptyString() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, new MessageView(null, null, "test", MessageType.DECLINE,
                null, "R1", null, null, null, ActorType.AGENT, null, null, 0));
        assertThat(s1.points().get("R1").thread().get(1).content()).isNotNull().isEqualTo("");
    }

    @Test
    void apply_handoff_nullContent_doesNotProduceNullInThread() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, new MessageView(null, null, "test", MessageType.HANDOFF,
                null, "R1", null, "human", null, ActorType.AGENT, null, null, 0));
        assertThat(s1.points().get("R1").thread().get(1).content()).isNotNull();
        assertThat(s1.humanFlags().get(0).content()).isNotNull();
    }

    @Test
    void apply_handoff_transitionsToPendingHuman() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, handoff("R1", ActorType.AGENT, "Needs human."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
    }

    @Test
    void apply_event_isNoOp() {
        ReviewState s0 = proj.identity();
        assertThat(proj.apply(s0, event(ActorType.AGENT, "Internal.")).points()).isEmpty();
    }

    @Test
    void apply_doesNotMutateInputState() {
        ReviewState initial = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        proj.apply(initial, respond("R1", ActorType.AGENT, "A."));
        assertThat(initial.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void agentType_nullActorType_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        assertThatThrownBy(() -> proj.apply(s0, new MessageView(null, null, "test",
                MessageType.RESPONSE, "A.", "R1", null, null, null, null, null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentType_systemActorType_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        assertThatThrownBy(() -> proj.apply(s0, new MessageView(null, null, "test",
                MessageType.RESPONSE, "A.", "R1", null, null, null, ActorType.SYSTEM, null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 4.3: Delete the old files**

```bash
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
```

- [ ] **Step 4.4: Update ReviewerChannelBackendFactory**

In `ReviewerChannelBackendFactory.java`:
1. Change import: `import io.casehub.drafthouse.debate.DebateChannelProjection;` → `import io.casehub.drafthouse.debate.ReviewChannelProjection;`
2. Change field: `@Inject DebateChannelProjection projection;` → `@Inject ReviewChannelProjection projection;`
3. Add guard as the second line of `onChannelInitialised`:

```java
    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        if (event.channelName().startsWith("drafthouse/debate/")) return;
        if (registry.find(event.channelId()).isEmpty()) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                registry, event.channelId(), messageService, llm, config.reviewer().maxDocChars(),
                projectionService, projection);
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, ReviewerChannelBackend.BACKEND_TYPE);
    }
```

- [ ] **Step 4.5: Run ReviewChannelProjectionTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewChannelProjectionTest
```

Expected: 14 tests green.

- [ ] **Step 4.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add -A server/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor(debate): rename DebateChannelProjection→ReviewChannelProjection, narrow to ChannelProjection, add debate channel guard  Refs #27"
```

---

## Task 5: New DebateChannelProjection (artefactRefs dispatch)

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`

- [ ] **Step 5.1: Write DebateChannelProjectionTest (failing — class doesn't exist yet)**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
package io.casehub.drafthouse.debate;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DebateChannelProjectionTest {

    private final DebateChannelProjection proj = new DebateChannelProjection();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageView msg(MessageType type, String correlationId, String artefactRefs, String content) {
        return new MessageView(null, null, "test-sender", type,
                content, correlationId, null, null, artefactRefs, ActorType.AGENT, null, null, 0);
    }

    private static String ratefacts(String entryType, String agent, int round) {
        return "entryType=" + entryType + "|agent=" + agent + "|round=" + round;
    }

    private static String ratefacts(String entryType, String agent, int round, String priority, String scope) {
        return "entryType=" + entryType + "|agent=" + agent + "|round=" + round
                + "|priority=" + priority + "|scope=" + scope;
    }

    // ── raise ─────────────────────────────────────────────────────────────────

    @Test
    void raise_createsOpenPoint_revAgent_roundPopulated() {
        ReviewState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("raise", "REV", 1, "P1", "ISOLATED"),
                        "This is the raise content."));
        assertThat(s.points()).containsKey("pt-1");
        ReviewPoint p = s.points().get("pt-1");
        assertThat(p.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(p.thread()).hasSize(1);
        assertThat(p.thread().get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(p.thread().get(0).agent()).isEqualTo(AgentType.REV);
        assertThat(p.thread().get(0).round()).isEqualTo(1);
        assertThat(p.thread().get(0).content()).isEqualTo("This is the raise content.");
    }

    @Test
    void raise_impAgent_mapsToIMP() {
        ReviewState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-2", ratefacts("raise", "IMP", 2, "P2", "SYSTEMIC"), "IMP point."));
        assertThat(s.points().get("pt-2").thread().get(0).agent()).isEqualTo(AgentType.IMP);
    }

    // ── agree ─────────────────────────────────────────────────────────────────

    @Test
    void agree_transitionsToAgreed() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("agree", "IMP", 2), "Agreed."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.AGREE);
        assertThat(s1.points().get("pt-1").thread().get(1).agent()).isEqualTo(AgentType.IMP);
    }

    // ── dispute ───────────────────────────────────────────────────────────────

    @Test
    void dispute_transitionsToDisputed_notDeclined() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("dispute", "IMP", 2), "I disagree."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.DISPUTED);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.DISPUTE);
    }

    // ── qualify ───────────────────────────────────────────────────────────────

    @Test
    void qualify_transitionsToActive_entryTypeQualify() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("qualify", "IMP", 2), "Partially."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
    }

    // ── counter ───────────────────────────────────────────────────────────────

    @Test
    void counter_transitionsToActive_entryTypeCounter_distinctFromQualify() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("counter", "IMP", 2), "My counter."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.COUNTER);
    }

    // ── flag-human ────────────────────────────────────────────────────────────

    @Test
    void flagHuman_transitionsToPendingHuman_flagEntryRoundPopulated() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.HANDOFF, "pt-1", ratefacts("flag-human", "REV", 3), "Human needed."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
        assertThat(s1.humanFlags().get(0).round()).isEqualTo(3);
        assertThat(s1.humanFlags().get(0).content()).isEqualTo("Human needed.");
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void unknownEntryType_stateUnchanged() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.QUERY, "pt-1", "entryType=unknown|agent=REV|round=1", "?"));
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void missingAgent_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        assertThatThrownBy(() -> proj.apply(s0,
                msg(MessageType.DONE, "pt-1", "entryType=agree|round=2", "Agreed.")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullActorType_doesNotThrow() {
        // DebateChannelProjection uses artefactRefs.agent — actorType is irrelevant
        ReviewState s = proj.apply(proj.identity(),
                new MessageView(null, null, "test", MessageType.QUERY,
                        "Content.", "pt-1", null, null,
                        ratefacts("raise", "REV", 1, "P1", "ISOLATED"),
                        null, null, null, 0));
        assertThat(s.points()).containsKey("pt-1");
    }

    @Test
    void apply_doesNotMutateInputState() {
        ReviewState after_raise = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        proj.apply(after_raise,
                msg(MessageType.DONE, "pt-1", ratefacts("agree", "IMP", 2), "Done."));
        assertThat(after_raise.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void projectionName_returnsDebateSummary() {
        assertThat(proj.projectionName()).isEqualTo("debate-summary");
    }

    @Test
    void render_emptyResult_returnsNonBlankSentinel() {
        ProjectionResult<ReviewState> empty = new ProjectionResult<>(proj.identity(), null);
        assertThat(proj.render(empty)).isNotBlank();
    }
}
```

- [ ] **Step 5.2: Run test — expect compile failure (class not yet created)**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest 2>&1 | grep -E "ERROR|cannot find"
```

Expected: compile error — `DebateChannelProjection` not found.

- [ ] **Step 5.3: Implement DebateChannelProjection**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds debate channel messages into ReviewState.
 * Dispatch is on artefactRefs.entryType — debate channels always carry artefactRefs.
 * Agent classification uses artefactRefs.agent (REV/IMP) — NOT actorType.
 *
 * Both REV and IMP agents are ActorType.AGENT; actorType cannot distinguish them.
 * See PP-20260607-508f7b.
 */
@ApplicationScoped
public class DebateChannelProjection implements RenderableProjection<ReviewState> {

    private static final System.Logger LOG = System.getLogger(DebateChannelProjection.class.getName());

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Override public String projectionName() { return "debate-summary"; }

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        Map<String, String> meta = parseArtefacts(message.artefactRefs());
        String entryType = meta.get("entryType");
        if (entryType == null) return state;
        return switch (entryType) {
            case "raise"      -> handleRaise(state, message, meta);
            case "agree"      -> handleAgree(state, message, meta);
            case "dispute"    -> handleDispute(state, message, meta);
            case "qualify"    -> handleQualify(state, message, meta);
            case "counter"    -> handleCounter(state, message, meta);
            case "flag-human" -> handleFlagHuman(state, message, meta);
            default           -> state;
        };
    }

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ReviewState handleRaise(ReviewState state, MessageView message, Map<String, String> meta) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        int round = parseRound(meta);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agentType(meta), round, EntryType.RAISE, message.content()));
        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleAgree(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.AGREE, ReviewStatus.AGREED);
    }

    private ReviewState handleDispute(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.DISPUTE, ReviewStatus.DISPUTED);
    }

    private ReviewState handleQualify(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.QUALIFY, ReviewStatus.ACTIVE);
    }

    private ReviewState handleCounter(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.COUNTER, ReviewStatus.ACTIVE);
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message, Map<String, String> meta) {
        String targetId = message.correlationId();
        String content = Objects.requireNonNullElse(message.content(), "");
        int round = parseRound(meta);
        AgentType agent = agentType(meta);
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agent, round, EntryType.FLAG_HUMAN, content));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, round, agent, content));
        return new ReviewState(points, flags);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReviewState appendToPoint(ReviewState state, MessageView message,
                                       Map<String, String> meta, EntryType type, ReviewStatus newStatus) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Debate entry ({0}) references unknown point: {1} — discarded", type, targetId);
            return state;
        }
        ReviewPoint existing = state.points().get(targetId);
        int round = parseRound(meta);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(meta), round, type, message.content()));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private AgentType agentType(Map<String, String> meta) {
        String agent = meta.get("agent");
        if (agent == null) throw new IllegalArgumentException("artefactRefs.agent missing in debate message");
        return switch (agent) {
            case "REV" -> AgentType.REV;
            case "IMP" -> AgentType.IMP;
            default    -> throw new IllegalArgumentException("Unknown agent in debate artefactRefs: " + agent);
        };
    }

    private int parseRound(Map<String, String> meta) {
        String r = meta.get("round");
        if (r == null) return 0;
        try { return Integer.parseInt(r); } catch (NumberFormatException e) { return 0; }
    }

    private Map<String, String> parseArtefacts(String artefacts) {
        Map<String, String> map = new HashMap<>();
        if (artefacts == null || artefacts.isBlank()) return map;
        for (String part : artefacts.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        return map;
    }

    private Priority parsePriority(String s) {
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.P3; }
    }

    private Scope parseScope(String s) {
        try { return Scope.valueOf(s.toUpperCase()); } catch (Exception e) { return Scope.ISOLATED; }
    }
}
```

- [ ] **Step 5.4: Run DebateChannelProjectionTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all tests green.

- [ ] **Step 5.5: Run full suite to verify no regressions**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: BUILD SUCCESS.

- [ ] **Step 5.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelProjection — artefactRefs dispatch, REV/IMP agent classification  Refs #27"
```

---

## Task 6: DebateChannelBackend + DebateChannelBackendFactory

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackendFactory.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java`

- [ ] **Step 6.1: Write DebateChannelBackendFactoryTest (failing)**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java
package io.casehub.drafthouse;

import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebateChannelBackendFactoryTest {

    private ChannelGateway gateway;
    private DebateChannelBackend debateBackend;
    private DebateChannelBackendFactory debateFactory;
    private ReviewerChannelBackendFactory reviewerFactory;
    private ReviewSessionRegistry reviewRegistry;

    @BeforeEach
    void setUp() {
        gateway = mock(ChannelGateway.class);
        debateBackend = new DebateChannelBackend();

        debateFactory = new DebateChannelBackendFactory();
        debateFactory.gateway = gateway;
        debateFactory.debateBackend = debateBackend;

        reviewRegistry = mock(ReviewSessionRegistry.class);
        reviewerFactory = new ReviewerChannelBackendFactory();
        reviewerFactory.gateway = gateway;
        reviewerFactory.registry = reviewRegistry;
        // other fields left null — the factory returns before using them when debate channel guard fires
    }

    @Test
    void debateChannel_registersDebateBackend_notReviewerBackend() {
        UUID channelId = UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, "drafthouse/debate/d-abc123");

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        verify(gateway).deregisterBackend(channelId, DebateChannelBackend.BACKEND_ID);
        verify(gateway).registerBackend(channelId, debateBackend, DebateChannelBackend.BACKEND_TYPE);
        // ReviewerChannelBackendFactory returns early — registry.find() must not have been called
        verifyNoInteractions(reviewRegistry);
    }

    @Test
    void reviewChannel_registersReviewerBackend_notDebateBackend() {
        UUID channelId = UUID.randomUUID();
        String channelName = "drafthouse/r-" + UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, channelName);

        // ReviewerChannelBackendFactory will call registry.find() — no session → returns early
        when(reviewRegistry.find(channelId)).thenReturn(Optional.empty());

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        // DebateChannelBackendFactory should not register for non-debate channels
        verify(gateway, never()).registerBackend(eq(channelId), eq(debateBackend), anyString());
    }
}
```

- [ ] **Step 6.2: Run test — expect compile failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelBackendFactoryTest 2>&1 | grep -E "ERROR|cannot find"
```

Expected: compile error.

- [ ] **Step 6.3: Create DebateChannelBackend**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java
package io.casehub.drafthouse;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * No-op ChannelBackend for debate channels.
 *
 * Debate is peer-to-peer: REV and IMP agents post directly via DebateMcpTools.
 * No backend processing is triggered on message arrival.
 * The backend's only role is as a registration fence — its presence prevents
 * ReviewerChannelBackendFactory from attaching an LLM backend to debate channels.
 *
 * Stateless and @ApplicationScoped: the same instance handles all debate channels.
 */
@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {

    static final String BACKEND_ID   = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}
    @Override public void post(ChannelRef channel, OutboundMessage message) {}
}
```

- [ ] **Step 6.4: Create DebateChannelBackendFactory**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackendFactory.java
package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

/**
 * Registers DebateChannelBackend for debate channels on init and startup recovery.
 * Deregisters before registering to be idempotent on restart.
 */
@ApplicationScoped
public class DebateChannelBackendFactory {

    @Inject ChannelGateway gateway;
    @Inject DebateChannelBackend debateBackend;

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/debate/")) return;
        gateway.deregisterBackend(event.channelId(), DebateChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), debateBackend, DebateChannelBackend.BACKEND_TYPE);
    }
}
```

- [ ] **Step 6.5: Run DebateChannelBackendFactoryTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelBackendFactoryTest
```

Expected: both tests green.

- [ ] **Step 6.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackendFactory.java server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelBackend (no-op), DebateChannelBackendFactory  Refs #27"
```

---

## Task 7: DebateMcpTools + unit tests

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 7.1: Write DebateMcpToolsTest (failing — class not yet created)**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.ReviewState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DebateMcpToolsTest {

    private ChannelService channelService;
    private ChannelGateway channelGateway;
    private InstanceService instanceService;
    private MessageService messageService;
    private ProjectionService projectionService;
    private DebateSessionRegistry registry;
    private DebateChannelProjection debateProjection;
    private DebateMcpTools tools;

    private Channel stubChannel;

    @BeforeEach
    void setUp() {
        channelService    = mock(ChannelService.class);
        channelGateway    = mock(ChannelGateway.class);
        instanceService   = mock(InstanceService.class);
        messageService    = mock(MessageService.class);
        projectionService = mock(ProjectionService.class);
        registry          = mock(DebateSessionRegistry.class);
        debateProjection  = mock(DebateChannelProjection.class);

        tools = new DebateMcpTools();
        tools.channelService    = channelService;
        tools.channelGateway    = channelGateway;
        tools.instanceService   = instanceService;
        tools.messageService    = messageService;
        tools.projectionService = projectionService;
        tools.registry          = registry;
        tools.debateProjection  = debateProjection;

        stubChannel      = new Channel();
        stubChannel.id   = UUID.randomUUID();
        stubChannel.name = "drafthouse/debate/d-" + UUID.randomUUID();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(stubChannel);

        Instance stubInstance = new Instance();
        stubInstance.id = UUID.randomUUID();
        when(instanceService.register(anyString(), anyString(), any())).thenReturn(stubInstance);
    }

    // ── start_debate ──────────────────────────────────────────────────────────

    @Test
    void startDebate_registryPutBeforeInitChannel() {
        var order = inOrder(registry, channelGateway);
        tools.startDebate("irrelevant-spec.md");
        order.verify(registry).put(any());
        order.verify(channelGateway).initChannel(eq(stubChannel.id), any(ChannelRef.class));
    }

    @Test
    void startDebate_happyPath_sessionFieldsCorrect() {
        String result = tools.startDebate("spec.md");

        assertThat(result).contains(stubChannel.id.toString());
        assertThat(result).contains("spec.md");

        ArgumentCaptor<DebateSession> cap = ArgumentCaptor.forClass(DebateSession.class);
        verify(registry).put(cap.capture());
        DebateSession s = cap.getValue();
        assertThat(s.channelId()).isEqualTo(stubChannel.id);
        assertThat(s.debateSessionId()).isEqualTo(stubChannel.id.toString());
        assertThat(s.channelName()).isEqualTo(stubChannel.name);
        assertThat(s.revInstanceId()).isEqualTo("drafthouse-rev-" + s.debateSessionId());
        assertThat(s.impInstanceId()).isEqualTo("drafthouse-imp-" + s.debateSessionId());
    }

    @Test
    void startDebate_channelName_hasDebatePrefix() {
        tools.startDebate("spec.md");
        verify(channelService).create(
                argThat(name -> name.startsWith("drafthouse/debate/d-")),
                anyString(), eq(ChannelSemantic.APPEND), isNull());
    }

    // ── session precondition ──────────────────────────────────────────────────

    @Test
    void raisePoint_invalidSessionIdFormat_returnsFormatError() {
        String result = tools.raisePoint("not-a-uuid", "REV", 1, "content", "P1", "ISOLATED", null);
        assertThat(result).startsWith("error: invalid session id format:");
    }

    @Test
    void raisePoint_unknownSession_returnsNotFoundError() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());
        String result = tools.raisePoint(UUID.randomUUID().toString(), "REV", 1, "content", "P1", "ISOLATED", null);
        assertThat(result).startsWith("error: no active debate session for:");
    }

    // ── raise_point ───────────────────────────────────────────────────────────

    @Test
    void raisePoint_dispatchesQueryWithCorrectFields() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.raisePoint(channelId.toString(), "REV", 1,
                "The issue content.", "P1", "ISOLATED", "§3.2");

        assertThat(result).contains("pointId");
        assertThat(result).contains("dispatched");

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.QUERY);
        assertThat(d.sender()).isEqualTo(session.revInstanceId());
        assertThat(d.content()).isEqualTo("The issue content.");
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.artefactRefs()).contains("entryType=raise");
        assertThat(d.artefactRefs()).contains("agent=REV");
        assertThat(d.artefactRefs()).contains("round=1");
        assertThat(d.artefactRefs()).contains("priority=P1");
        assertThat(d.artefactRefs()).contains("scope=ISOLATED");
        assertThat(d.artefactRefs()).contains("location=§3.2");
        assertThat(d.correlationId()).isNotBlank(); // the minted pointId
    }

    @Test
    void raisePoint_impSender_usesImpInstanceId() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.raisePoint(channelId.toString(), "IMP", 2, "content", "P2", "SYSTEMIC", null);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().sender()).isEqualTo(session.impInstanceId());
    }

    // ── respond_to ────────────────────────────────────────────────────────────

    @Test
    void respondTo_agree_dispatchesDone() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message();
        stubMsg.id = 99L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        String result = tools.respondTo(channelId.toString(), "IMP", 2, pointId, "agree", "I agree.");

        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DONE);
        assertThat(d.content()).isEqualTo("I agree.");
        assertThat(d.inReplyTo()).isEqualTo(99L);
        assertThat(d.correlationId()).isEqualTo(pointId);
        assertThat(d.artefactRefs()).contains("entryType=agree");
    }

    @Test
    void respondTo_dispute_dispatchesDecline() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message();
        stubMsg.id = 42L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "REV", 3, pointId, "dispute", "No.");

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void respondTo_qualify_dispatchesResponse() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "IMP", 2, pointId, "qualify", "Partly.");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void respondTo_counter_dispatchesResponse() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "REV", 3, pointId, "counter", "Counter arg.");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.RESPONSE);
        assertThat(cap.getValue().artefactRefs()).contains("entryType=counter");
    }

    @Test
    void respondTo_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.respondTo(channelId.toString(), "IMP", 2,
                "no-such-point", "agree", "Agreed.");
        assertThat(result).startsWith("error: point not found:");
    }

    // ── flag_human ────────────────────────────────────────────────────────────

    @Test
    void flagHuman_dispatchesHandoffWithCorrectFields() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 7L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        String result = tools.flagHuman(channelId.toString(), "REV", 3, pointId, "Human clarification needed.");

        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.HANDOFF);
        assertThat(d.target()).isEqualTo(DraftHouseInstances.HUMAN_INSTANCE_ID);
        assertThat(d.content()).isEqualTo("Human clarification needed.");
        assertThat(d.inReplyTo()).isEqualTo(7L);
        assertThat(d.artefactRefs()).contains("entryType=flag-human");
    }

    @Test
    void flagHuman_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.flagHuman(channelId.toString(), "REV", 3, "no-point", "reason");
        assertThat(result).startsWith("error: point not found:");
    }

    // ── get_debate_summary ────────────────────────────────────────────────────

    @Test
    void getDebateSummary_delegatesToProjectionAndRenders() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(java.util.Map.of(), java.util.List.of());
        ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
        when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
        when(debateProjection.render(result)).thenReturn("# Summary\n...");

        String summary = tools.getDebateSummary(channelId.toString());
        assertThat(summary).isEqualTo("# Summary\n...");
    }

    // ── end_debate ────────────────────────────────────────────────────────────

    @Test
    void endDebate_removesSessionAndReturnsEnded() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.endDebate(channelId.toString(), false);

        verify(registry).remove(channelId);
        assertThat(result).contains("ended");
        assertThat(result).contains("false"); // channelDeleted: false
    }

    @Test
    void endDebate_unknownSession_returnsNotFoundJson() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());

        String result = tools.endDebate(UUID.randomUUID().toString(), false);

        assertThat(result).contains("not-found");
        assertThat(result).doesNotStartWith("error:");
        verifyNoInteractions(channelService);
    }
}
```

- [ ] **Step 7.2: Run test — expect compile failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest 2>&1 | grep -E "ERROR|cannot find"
```

Expected: compile error — `DebateMcpTools` not found.

- [ ] **Step 7.3: Implement DebateMcpTools**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java
package io.casehub.drafthouse;

import java.util.UUID;
import java.util.List;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * MCP tool surface for debate sessions.
 * Agents (REV and IMP) post structured debate entries via these tools.
 *
 * Error handling: all errors returned as "error: ..." strings per mcp-tool-error-strings.md.
 * Session cleanup order: registry.remove() first, then channel.delete()
 * — matches start_review cleanup (prevents a live session handle pointing at a deleted channel).
 */
@ApplicationScoped
public class DebateMcpTools {

    private static final Logger LOG = Logger.getLogger(DebateMcpTools.class.getName());

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ProjectionService projectionService;
    @Inject DebateSessionRegistry registry;
    @Inject DebateChannelProjection debateProjection;

    @Tool(name = "start_debate",
          description = "Start a peer-to-peer debate session between a reviewer (REV) and implementer (IMP) agent. Returns JSON with debateSessionId (use for all subsequent calls), channel name, and specPath.")
    public String startDebate(
            @ToolArg(description = "Absolute path to the spec file being debated") String specPath) {

        String debateSlug = "d-" + UUID.randomUUID();
        String channelName = "drafthouse/debate/" + debateSlug;

        Channel channel = null;
        try {
            channel = channelService.create(channelName, "DraftHouse debate session",
                    ChannelSemantic.APPEND, null);

            String debateSessionId = channel.id.toString();
            String resolvedName = channel.name;
            String revInstanceId = "drafthouse-rev-" + debateSessionId;
            String impInstanceId = "drafthouse-imp-" + debateSessionId;

            instanceService.register(revInstanceId, "DraftHouse debate reviewer " + debateSessionId,
                    List.of("document-debate-rev"));
            instanceService.register(impInstanceId, "DraftHouse debate implementer " + debateSessionId,
                    List.of("document-debate-imp"));

            DebateSession session = new DebateSession(
                    channel.id, debateSessionId, resolvedName, revInstanceId, impInstanceId);

            registry.put(session);
            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedName));

            return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
                    + "\",\"specPath\":\"" + specPath + "\"}";

        } catch (Exception e) {
            LOG.warning("start_debate failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channelName, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "raise_point",
          description = "Raise a new debate point. Returns JSON with pointId — use this in subsequent respond_to calls to cite this point.")
    public String raisePoint(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV (reviewer) or IMP (implementer)") String agentRole,
            @ToolArg(description = "Current debate round number (integer, starting at 1)") int round,
            @ToolArg(description = "The point being raised") String content,
            @ToolArg(description = "Priority: P1 (blocking), P2 (important), P3 (minor)") String priority,
            @ToolArg(description = "Scope: ISOLATED (single instance) or SYSTEMIC (pattern)") String scope,
            @ToolArg(description = "Optional location: spec section, heading, or free-form. Null to omit.") String location) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

        String pointId = UUID.randomUUID().toString();
        StringBuilder artefacts = new StringBuilder("entryType=raise|agent=").append(agentRole)
                .append("|round=").append(round)
                .append("|priority=").append(priority)
                .append("|scope=").append(scope);
        if (location != null && !location.isBlank()) {
            artefacts.append("|location=").append(location);
        }

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(MessageType.QUERY)
                .content(content)
                .correlationId(pointId)
                .artefactRefs(artefacts.toString())
                .actorType(ActorType.AGENT)
                .build());

        return "{\"pointId\":\"" + pointId + "\",\"status\":\"dispatched\"}";
    }

    @Tool(name = "respond_to",
          description = "Respond to a debate point. entryType must be: agree, dispute, qualify, or counter.")
    public String respondTo(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId returned by raise_point") String pointId,
            @ToolArg(description = "Response type: agree, dispute, qualify, counter") String entryType,
            @ToolArg(description = "Your response content") String content) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

        MessageType qhorusType = switch (entryType) {
            case "agree"   -> MessageType.DONE;
            case "dispute" -> MessageType.DECLINE;
            case "qualify", "counter" -> MessageType.RESPONSE;
            default -> null;
        };
        if (qhorusType == null) {
            return "error: invalid entryType '" + entryType + "' — must be agree, dispute, qualify, or counter";
        }

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(qhorusType)
                .content(content)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .artefactRefs("entryType=" + entryType + "|agent=" + agentRole + "|round=" + round)
                .actorType(ActorType.AGENT)
                .build());

        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "flag_human",
          description = "Flag a debate point for human review. Signals that the agents cannot resolve the point without human input.")
    public String flagHuman(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId being flagged") String pointId,
            @ToolArg(description = "Reason for escalating to human") String reason) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(MessageType.HANDOFF)
                .content(reason)
                .target(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .artefactRefs("entryType=flag-human|agent=" + agentRole + "|round=" + round)
                .actorType(ActorType.AGENT)
                .build());

        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "get_debate_summary",
          description = "Get the current debate summary as markdown. Shows all points with their status, thread, and classifications.")
    public String getDebateSummary(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        var result = projectionService.project(session.channelId(), debateProjection);
        return debateProjection.render(result);
    }

    @Tool(name = "end_debate",
          description = "End a debate session. Pass deleteChannel=true to remove the Qhorus channel.")
    public String endDebate(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Whether to delete the Qhorus channel (default: false)") boolean deleteChannel) {

        UUID channelId;
        try {
            channelId = UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid session id format: " + debateSessionId;
        }

        DebateSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            return "{\"debateSessionId\":\"" + debateSessionId + "\",\"status\":\"not-found\"}";
        }

        registry.remove(channelId);

        if (deleteChannel) {
            try {
                channelService.delete(session.channelName(), true);
            } catch (Exception e) {
                LOG.warning("end_debate: channel delete failed for " + session.channelName()
                        + ": " + e.getMessage());
            }
        }

        return "{\"debateSessionId\":\"" + debateSessionId + "\",\"status\":\"ended\",\"channelDeleted\":"
                + deleteChannel + "}";
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DebateSession resolveSession(String debateSessionId) {
        try {
            UUID channelId = UUID.fromString(debateSessionId);
            return registry.find(channelId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sessionError(String debateSessionId) {
        try {
            UUID.fromString(debateSessionId);
            return "error: no active debate session for: " + debateSessionId;
        } catch (IllegalArgumentException e) {
            return "error: invalid session id format: " + debateSessionId;
        }
    }

    private String sender(DebateSession session, String agentRole) {
        return switch (agentRole) {
            case "REV" -> session.revInstanceId();
            case "IMP" -> session.impInstanceId();
            default    -> throw new IllegalArgumentException("Unknown agentRole: " + agentRole);
        };
    }
}
```

- [ ] **Step 7.4: Run DebateMcpToolsTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest
```

Expected: all tests green.

- [ ] **Step 7.5: Run full suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: BUILD SUCCESS.

- [ ] **Step 7.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateMcpTools — start/raise/respond/flag/summary/end MCP tools  Refs #27"
```

---

## Task 8: DebateSessionLifecycleTest (integration)

Full-stack round-trip: real Qhorus H2 in-memory, no mocks for channel/message/projection.

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java`

- [ ] **Step 8.1: Write DebateSessionLifecycleTest**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration test for the full debate lifecycle with real Qhorus on H2.
 *
 * No Awaitility: DebateChannelBackend.post() is a no-op, so ChannelGateway.fanOut()
 * triggers no virtual thread work. All debate operations are synchronous end-to-end.
 * Contrast: ReviewSessionLifecycleTest requires Awaitility because ReviewerChannelBackend
 * executes on virtual threads via fanOut().
 */
@QuarkusTest
class DebateSessionLifecycleTest {

    private static final Pattern DEBATE_ID_PATTERN = Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN  = Pattern.compile("\"pointId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;

    private String activeDebateSessionId;

    @BeforeEach
    void setUp() {
        activeDebateSessionId = null;
    }

    @AfterEach
    void tearDown() {
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
        }
    }

    @Test
    void raiseAndAgree_summaryShowsAgreedPoint() {
        // start
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        assertThat(sessionId).isNotBlank();
        activeDebateSessionId = sessionId;

        // REV raises a point
        String raiseResult = tools.raisePoint(sessionId, "REV", 1,
                "The API contract is ambiguous.", "P1", "ISOLATED", "§3.2");
        String pointId = extractGroup(POINT_ID_PATTERN, raiseResult);
        assertThat(pointId).isNotBlank();

        // IMP agrees
        String respondResult = tools.respondTo(sessionId, "IMP", 2, pointId, "agree",
                "Correct — will clarify the contract.");
        assertThat(respondResult).contains("dispatched");

        // summary shows the agreed point
        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("✅");           // AGREED status marker
        assertThat(summary).doesNotContain("~~");     // AGREED has strikethrough — check actual rendering
        assertThat(summary).contains("ambiguous");   // content of raise
    }

    @Test
    void raiseAndDispute_summaryShowsDisputedPoint_noStrikethrough() {
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String raiseResult = tools.raisePoint(sessionId, "REV", 1,
                "Error handling is missing.", "P2", "SYSTEMIC", null);
        String pointId = extractGroup(POINT_ID_PATTERN, raiseResult);

        tools.respondTo(sessionId, "IMP", 2, pointId, "dispute",
                "Retry is caller responsibility per contract.");

        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("⚡");           // DISPUTED status marker
        assertThat(summary).doesNotContain("~~");     // non-terminal — no strikethrough
        assertThat(summary).contains("dispute");      // typeLabel in thread
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
```

- [ ] **Step 8.2: Run DebateSessionLifecycleTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateSessionLifecycleTest
```

Expected: both tests green.

- [ ] **Step 8.3: Run full suite — final verification**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 8.4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(debate): DebateSessionLifecycleTest — raise/agree and raise/dispute lifecycle  Refs #27"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `EntryType.COUNTER` | Task 1 |
| `ReviewStatus.DISPUTED` | Task 1 |
| `SummaryRenderer` COUNTER/DISPUTED cases | Task 1 |
| `DebateSession` record | Task 2 |
| `DebateSessionRegistry` + `DebateSessionRegistryImpl` | Task 2 |
| `DraftHouseInstances` constants extraction | Task 3 |
| All 6 compile-failure reference updates | Task 3 |
| `ReviewChannelProjection` rename + narrow to `ChannelProjection` | Task 4 |
| 3 tests deleted from `ReviewChannelProjectionTest` | Task 4 |
| `ReviewerChannelBackendFactory` debate channel guard | Task 4 |
| `DebateChannelProjection` with artefactRefs dispatch | Task 5 |
| `FlagEntry.round` populated from `artefactRefs.round` | Task 5 |
| `DebateChannelBackend` no-op | Task 6 |
| `DebateChannelBackendFactory` deregister-then-register | Task 6 |
| `DebateMcpTools` — all 6 tools | Task 7 |
| Session precondition (UUID parse + registry find) | Task 7 |
| `end_debate` idempotent not-found JSON | Task 7 |
| `sender()` switch expression with throw | Task 7 |
| Registry-first cleanup order in `start_debate` failure | Task 7 |
| `d-` slug prefix for Qhorus validator | Task 7 |
| `DebateSessionLifecycleTest` — no Awaitility, @AfterEach cleanup | Task 8 |
| Factory routing test | Task 6 |
| `startDebate_registryPutBeforeInitChannel` ordering test | Task 7 |
| `ArgumentCaptor<DebateSession>` field verification | Task 7 |

**Placeholder scan:** None found.

**Type consistency:** `DebateSession` fields match exactly between Task 2 (definition) and Tasks 6-7 (usage). `inReplyTo` is `Long` in all dispatch calls. `artefactRefs` always `String`.

**Agreed-point strikethrough note:** `ReviewStatus.AGREED` renders with strikethrough in `SummaryRenderer` (line 36-37). The lifecycle test for agree says `doesNotContain("~~")` — this is wrong! AGREED does have strikethrough. Fix: change to `assertThat(summary).contains("✅")` only and separately verify content. Fixing inline:

In `DebateSessionLifecycleTest.raiseAndAgree_summaryShowsAgreedPoint`, the assertion `assertThat(summary).doesNotContain("~~")` is incorrect — AGREED renders with strikethrough. Replace with `assertThat(summary).contains("~~")` to verify the agreed point is correctly struck through.

The fix applies to Task 8 Step 8.1 — in the written test above, that assertion is already wrong. The corrected test body for `raiseAndAgree_summaryShowsAgreedPoint`:

```java
        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("✅");           // AGREED status marker
        assertThat(summary).contains("~~");           // AGREED is terminal — strikethrough
        assertThat(summary).contains("ambiguous");    // content of raise
```
