# Channel Projection SPI Migration — #31, #35, #37

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace DraftHouse's dead file-based debate pipeline with a live Qhorus `ChannelProjection`, restore conversation memory in `ReviewerChannelBackend`, and fix two bundled XS bugs (#35, #37).

**Architecture:** A new `@ApplicationScoped DebateChannelProjection` (runtime module) consolidates fold logic and CDI registration; a new `ReviewConversationRenderer` (api module) produces a plain-text LLM transcript. `ReviewerChannelBackend` injects `ProjectionService` to project channel history before each LLM call, restoring the conversation memory that was lost when DraftHouse migrated from files to Qhorus channels.

**Tech Stack:** Java 21, Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT (`ChannelProjection<S>`, `RenderableProjection<S>`, `ProjectionService`, `ProjectionResult<S>`), Mockito 5, AssertJ 3.27.7.

**Spec:** `docs/superpowers/specs/2026-06-06-channel-projection-spi-migration.md`

---

## File Map

**Create:**
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewConversationRenderer.java`
- `server/api/src/test/java/io/casehub/drafthouse/debate/ReviewConversationRendererTest.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`

**Modify:**
- `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java` — add `DECLINED`
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java` — add `DECLINED`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java` — DECLINED switch cases + strikethrough fix
- `server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java` — rebuild states directly, add DECLINED test
- `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java` — add `reviewHistory` param
- `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java` — add `projectionService`, `projection`, conversation history
- `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java` — inject two new deps
- `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java` — new mocks, 5→6 arg stubs, ordering test
- `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java` — 5→6 arg stubs, add test case 5
- `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java` — prefix UUID slug (`"r-"`) #35
- `server/pom.xml` — remove `<module>claude-agent</module>`
- `~/claude/casehub/parent/docs/PLATFORM.md` — update cross-repo dep table

**Delete (source):**
`server/api/.../debate/`: `SummaryProjector`, `DebateParser`, `RoundParser`, `DebateEvent`, `DebateEntry`, `DebateEntryFormatter`, `DebateRoundContext`, `DebateAgentProvider`
`server/runtime/.../debate/`: `ReviewSession` (6-field), `ReviewSessionService`, `LangChain4jDebateAgentProvider`, `SpecReviewerAiService`, `SpecImplementerAiService`
`server/claude-agent/` (entire module directory)

**Delete (tests):**
`api/test`: `SummaryProjectorTest`, `DebateParserTest`, `RoundParserTest`, `DebateEntryFormatterTest`
`runtime/test`: `DebateAgentProviderContractTest`, `LangChain4jDebateAgentProviderTest`, `DebateRoundTripTest`, `CritiqueResourceTest`
`claude-agent/test`: `ClaudeAgentSdkDebateAgentProviderTest` (gone with module)

---

## Task 1: Prep — update SummaryRendererTest to remove dead-type dependencies

`SummaryRendererTest` currently builds `ReviewState` objects via `SummaryProjector.project(List<DebateEvent>)`. Both types are being deleted. Update the test to build states directly from records before any deletions occur, so the test suite stays green throughout.

**Files:**
- Modify: `server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java`

- [ ] **Step 1: Rewrite SummaryRendererTest**

Replace the entire file content. The test removes the `SummaryProjector projector` field and the `DebateEvent` imports, rebuilding each `ReviewState` from records directly.

```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Test
    void rendersEmptyStateAsHeader() {
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).contains("# Review Summary");
        assertThat(output).doesNotContain("##");
    }

    @Test
    void rendersOpenPoint() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, "§3.2"),
                List.of(new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Both variants appear.")),
                ReviewStatus.OPEN)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("🔴");
        assertThat(output).contains("[R1-REV-001]");
        assertThat(output).contains("P1");
        assertThat(output).contains("Both variants appear.");
    }

    @Test
    void rendersAgreedPointWithStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.IMP, 0, EntryType.AGREE, "Fixed.")),
                ReviewStatus.AGREED)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("✅");
        assertThat(output).contains("~~");
    }

    @Test
    void rendersFlagSectionAtBottom() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.FLAG_HUMAN, "Human needed.")),
                ReviewStatus.PENDING_HUMAN)),
            List.of(new FlagEntry(null, 0, AgentType.REV, "Human needed.")));
        String output = renderer.render(state);
        assertThat(output).contains("⚑");
        assertThat(output).contains("Human needed.");
        assertThat(output.indexOf("⚑")).isGreaterThan(output.indexOf("R1-REV-001"));
    }

    @Test
    void memoDoesNotAppearInSummary() {
        // A memo produces no review points — state stays empty.
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).doesNotContain("Private thought.");
    }

    @Test
    void renderTimestampIsControlledByClock() {
        Instant fixed = Instant.parse("2026-01-15T10:30:00Z");
        renderer.setClockForTest(() -> fixed);
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).contains("2026-01-15T10:30:00Z");
    }
}
```

- [ ] **Step 2: Run api tests to verify they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(debate): rebuild SummaryRendererTest without dead DebateEvent types  Refs #31"
```

---

## Task 2: Domain model — add DECLINED + update SummaryRenderer

Adding `DECLINED` to `EntryType` and `ReviewStatus` immediately breaks `SummaryRenderer`'s exhaustive switches (no `default` clause). All three files must change together in one commit.

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java`

- [ ] **Step 1: Add DECLINED to EntryType**

```java
public enum EntryType { RAISE, AGREE, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED }
```

- [ ] **Step 2: Add DECLINED to ReviewStatus**

```java
public enum ReviewStatus { OPEN, ACTIVE, AGREED, PENDING_HUMAN, DECLINED }
```

- [ ] **Step 3: Update SummaryRenderer — add DECLINED to both switches and fix strikethrough**

In `SummaryRenderer.render()`, find the `statusMarker` switch and add the new case:

```java
String statusMarker = switch (point.currentStatus()) {
    case OPEN          -> "🔴";
    case ACTIVE        -> "🟡";
    case AGREED        -> "✅";
    case PENDING_HUMAN -> "🔵";
    case DECLINED      -> "🚫";
};
```

Find the `typeLabel` switch and add the new case:

```java
String typeLabel = switch (entry.type()) {
    case RAISE      -> "raise";
    case AGREE      -> "agree";
    case DISPUTE    -> "dispute";
    case QUALIFY    -> "qualify";
    case FLAG_HUMAN -> "flag";
    case DECLINED   -> "declined";
};
```

Find the header strikethrough conditional and extend it to cover DECLINED:

```java
boolean strikethrough = point.currentStatus() == ReviewStatus.AGREED
        || point.currentStatus() == ReviewStatus.DECLINED;
if (strikethrough) {
    sb.append("## ").append(statusMarker).append(" ~~").append(header).append("~~\n");
} else {
    sb.append("## ").append(statusMarker).append(" ").append(header).append("\n");
}
```

- [ ] **Step 4: Add DECLINED rendering test to SummaryRendererTest**

Add after `renderTimestampIsControlledByClock`:

```java
@Test
void rendersDeclinedPointWithStrikethrough() {
    var state = new ReviewState(
        Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
            new PointClassification(Priority.P3, Scope.ISOLATED, null),
            List.of(
                new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Off topic?"),
                new ThreadEntry(null, AgentType.IMP, 0, EntryType.DECLINED, "Out of scope.")),
            ReviewStatus.DECLINED)),
        List.of());
    String output = renderer.render(state);
    assertThat(output).contains("🚫");
    assertThat(output).contains("~~");
    assertThat(output).contains("declined");
}
```

- [ ] **Step 5: Run api tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add DECLINED to domain model; update SummaryRenderer  Refs #31"
```

---

## Task 3: Delete dead code, modules, and tests

**Files:** delete many (see File Map above)

- [ ] **Step 1: Remove claude-agent module from server/pom.xml**

In `server/pom.xml`, delete the line:
```xml
<module>claude-agent</module>
```

- [ ] **Step 2: Delete dead source files**

```bash
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/SummaryProjector.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateParser.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/RoundParser.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateEvent.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateEntry.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateEntryFormatter.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateRoundContext.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/main/java/io/casehub/drafthouse/debate/DebateAgentProvider.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSession.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionService.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/LangChain4jDebateAgentProvider.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/SpecReviewerAiService.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/main/java/io/casehub/drafthouse/debate/SpecImplementerAiService.java
```

- [ ] **Step 3: Delete dead test files**

```bash
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/test/java/io/casehub/drafthouse/debate/SummaryProjectorTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/test/java/io/casehub/drafthouse/debate/DebateParserTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/test/java/io/casehub/drafthouse/debate/RoundParserTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/api/src/test/java/io/casehub/drafthouse/debate/DebateEntryFormatterTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateAgentProviderContractTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/test/java/io/casehub/drafthouse/debate/LangChain4jDebateAgentProviderTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateRoundTripTest.java
rm /Users/mdproctor/claude/casehub/drafthouse/server/runtime/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java
```

- [ ] **Step 4: Delete claude-agent module directory**

```bash
rm -rf /Users/mdproctor/claude/casehub/drafthouse/server/claude-agent
```

- [ ] **Step 5: Verify build compiles cleanly**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
```

Expected: `BUILD SUCCESS`. If any compile errors appear, a live file still imports a deleted type — fix before continuing.

- [ ] **Step 6: Commit all deletions**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add -A
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "chore(debate): delete dead file-based pipeline and claude-agent module  Refs #31"
```

---

## Task 4: TDD — ReviewConversationRenderer

`ReviewConversationRenderer` is a pure-Java utility in the `api` module. It renders `ReviewState` as a plain Q/A transcript for LLM context. Renders only `AGREED` and `DECLINED` points; excludes `OPEN`, `ACTIVE`, `PENDING_HUMAN`.

**Files:**
- Create: `server/api/src/test/java/io/casehub/drafthouse/debate/ReviewConversationRendererTest.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewConversationRenderer.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReviewConversationRendererTest {

    private final ReviewConversationRenderer renderer = new ReviewConversationRenderer();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewState emptyState() {
        return new ReviewState(Map.of(), List.of());
    }

    /** Builds a ReviewPoint with one RAISE thread entry and optionally one response entry. */
    private static ReviewPoint point(String id, ReviewStatus status, String question, String answer) {
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(id, AgentType.REV, 0, EntryType.RAISE, question));
        if (answer != null) {
            EntryType respType = status == ReviewStatus.DECLINED ? EntryType.DECLINED : EntryType.AGREE;
            thread.add(new ThreadEntry(null, AgentType.IMP, 0, respType, answer));
        }
        return new ReviewPoint(id, new PointClassification(Priority.P3, Scope.ISOLATED, null), thread, status);
    }

    private static ReviewState stateWith(ReviewPoint... points) {
        var map = new LinkedHashMap<String, ReviewPoint>();
        for (ReviewPoint p : points) map.put(p.id(), p);
        return new ReviewState(map, List.of());
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void emptyState_returnsSentinel() {
        assertThat(renderer.render(emptyState())).contains("No prior review activity");
    }

    @Test
    void onlyOpenPoints_returnsSentinel() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.OPEN, "Q?", null))))
                .contains("No prior review activity");
    }

    @Test
    void activePoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.ACTIVE, "Q?", "Partial."))))
                .contains("No prior review activity");
    }

    @Test
    void pendingHumanPoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.PENDING_HUMAN, "Q?", "Needs human."))))
                .contains("No prior review activity");
    }

    @Test
    void agreedPoint_renderedAsQA() {
        ReviewState s = stateWith(point("R1", ReviewStatus.AGREED, "What changed?", "It changed X."));
        String output = renderer.render(s);
        assertThat(output).contains("Q: What changed?");
        assertThat(output).contains("A: It changed X.");
    }

    @Test
    void declinedPoint_renderedWithParenthetical_noDoublePeriod() {
        ReviewState s = stateWith(point("R1", ReviewStatus.DECLINED, "Off topic?", "Out of scope."));
        String output = renderer.render(s);
        assertThat(output).contains("Q: Off topic?");
        assertThat(output).contains("(Declined");
        assertThat(output).contains("Out of scope");
        // trailing period from content must be stripped before appending closing paren
        assertThat(output).doesNotContain("scope..)");
        assertThat(output).doesNotContain("scope..)");
    }

    @Test
    void openPoint_excludedWhenMixedWithCompleted() {
        ReviewState s = stateWith(
                point("R1", ReviewStatus.AGREED, "Q1?", "A1."),
                point("R2", ReviewStatus.OPEN, "Q2?", null));
        String output = renderer.render(s);
        assertThat(output).contains("Q1?");
        assertThat(output).doesNotContain("Q2?");
    }

    @Test
    void multipleCompletedExchanges_renderedInInsertionOrder() {
        ReviewState s = stateWith(
                point("R1", ReviewStatus.AGREED, "First Q?", "First A."),
                point("R2", ReviewStatus.DECLINED, "Second Q?", "Out of scope."));
        String output = renderer.render(s);
        assertThat(output).contains("First Q?");
        assertThat(output).contains("Second Q?");
        assertThat(output.indexOf("First Q?")).isLessThan(output.indexOf("Second Q?"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ReviewConversationRendererTest
```

Expected: `COMPILATION ERROR` — `ReviewConversationRenderer` does not exist yet.

- [ ] **Step 3: Implement ReviewConversationRenderer**

```java
package io.casehub.drafthouse.debate;

import java.util.List;

public class ReviewConversationRenderer {

    private static final String SENTINEL = "No prior review activity in this session.";

    public String render(ReviewState state) {
        var sb = new StringBuilder();
        for (ReviewPoint point : state.points().values()) {
            if (point.currentStatus() != ReviewStatus.AGREED
                    && point.currentStatus() != ReviewStatus.DECLINED) {
                continue;
            }
            String question = point.thread().isEmpty() ? "" : point.thread().get(0).content();
            sb.append("Q: ").append(question).append("\n");

            String rawAnswer = lastResponseContent(point.thread());
            if (point.currentStatus() == ReviewStatus.DECLINED) {
                String reason = rawAnswer.endsWith(".")
                        ? rawAnswer.substring(0, rawAnswer.length() - 1)
                        : rawAnswer;
                sb.append("A: (Declined — ").append(reason).append(")\n");
            } else {
                sb.append("A: ").append(rawAnswer).append("\n");
            }
            sb.append("\n");
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? SENTINEL : result;
    }

    private static String lastResponseContent(List<ThreadEntry> thread) {
        for (int i = thread.size() - 1; i >= 0; i--) {
            if (thread.get(i).type() != EntryType.RAISE) {
                String c = thread.get(i).content();
                return c != null ? c : "";
            }
        }
        return "";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ReviewConversationRendererTest
```

Expected: `BUILD SUCCESS`, all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/ReviewConversationRenderer.java server/api/src/test/java/io/casehub/drafthouse/debate/ReviewConversationRendererTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add ReviewConversationRenderer — LLM context transcript  Refs #31"
```

---

## Task 5: TDD — DebateChannelProjection

`DebateChannelProjection` is an `@ApplicationScoped RenderableProjection<ReviewState>` in the `runtime` module. It consolidates all fold logic (from the deleted `SummaryProjector`) and registers with Qhorus's `ProjectionRegistry` for `project_channel` MCP access. The `@ApplicationScoped` annotation is CDI metadata — in the unit test we instantiate directly with `new`.

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`

- [ ] **Step 1: Write the failing test**

```java
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

    @Test
    void projectionName_returnsDebateSummary() {
        assertThat(proj.projectionName()).isEqualTo("debate-summary");
    }

    @Test
    void render_emptyResult_returnsNonBlankSentinel() {
        ProjectionResult<ReviewState> empty = new ProjectionResult<>(proj.identity(), null);
        assertThat(proj.render(empty)).isNotBlank();
    }

    @Test
    void render_nonEmptyResult_returnsNonBlankString() {
        ReviewState s = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        assertThat(proj.render(new ProjectionResult<>(s, 1L))).isNotBlank();
    }
}
```

- [ ] **Step 2: Run test to verify it fails with compilation error**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: `COMPILATION ERROR` — `DebateChannelProjection` does not exist.

- [ ] **Step 3: Implement DebateChannelProjection**

```java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class DebateChannelProjection implements RenderableProjection<ReviewState> {

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Override public String projectionName() { return "debate-summary"; }

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

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No review activity yet." : renderer.render(result.state());
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ReviewState handleRaise(ReviewState state, MessageView message) {
        String entryId = message.correlationId();
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        var thread = new ArrayList<ThreadEntry>();
        // round=0: MessageView carries no round field in v1; populated by #27 DebateChannel
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
            System.Logger logger = System.getLogger(DebateChannelProjection.class.getName());
            logger.log(System.Logger.Level.WARNING,
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
            System.Logger logger = System.getLogger(DebateChannelProjection.class.getName());
            logger.log(System.Logger.Level.WARNING,
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
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.FLAG_HUMAN, message.content()));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, agentType(message), message.content()));
        return new ReviewState(points, flags);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AgentType agentType(MessageView message) {
        if (message.actorType() == null) {
            throw new IllegalArgumentException(
                    "MessageView.actorType() must not be null in DebateChannelProjection");
        }
        return switch (message.actorType()) {
            case HUMAN -> AgentType.REV;
            case AGENT -> AgentType.IMP;
            default    -> throw new IllegalArgumentException(
                    "Unsupported actorType in debate projection: " + message.actorType());
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

- [ ] **Step 4: Run tests to verify they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: `BUILD SUCCESS`, all 15 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add DebateChannelProjection — RenderableProjection for review channel  Refs #31"
```

---

## Task 6: Wire projection into ReviewerChannelBackend

Update `DocumentReviewer`, `ReviewerChannelBackend`, and `ReviewerChannelBackendFactory` to inject and use `ProjectionService`. Update `ReviewerChannelBackendTest` in lock-step.

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java`

- [ ] **Step 1: Update DocumentReviewer — add reviewHistory param**

Replace the full file content:

```java
package io.casehub.drafthouse;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("{{personality}}")
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            Review history (prior turns in this session):
            {{reviewHistory}}

            Current query: {{query}}

            If this query is outside the scope of document review (e.g. general knowledge, \
            unrelated topics), respond with declined=true and explain why in content.
            Otherwise respond with declined=false and your review in content.
            """)
    ReviewResult review(String personality, String documentA, String documentB,
                        String selectionContext, String reviewHistory, String query);
}
```

- [ ] **Step 2: Update ReviewerChannelBackend**

Add two new fields and update the constructor. Replace the full file:

```java
package io.casehub.drafthouse;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.ReviewConversationRenderer;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;

public class ReviewerChannelBackend implements ChannelBackend {

    static final String BACKEND_ID = "drafthouse-reviewer";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(ReviewerChannelBackend.class.getName());

    private final ReviewSessionRegistry registry;
    private final UUID channelId;
    private final MessageService messageService;
    private final DocumentReviewer llm;
    private final int maxDocChars;
    private final ProjectionService projectionService;
    private final ChannelProjection<ReviewState> projection;
    private final ReviewConversationRenderer conversationRenderer = new ReviewConversationRenderer();

    ReviewerChannelBackend(ReviewSessionRegistry registry, UUID channelId,
                           MessageService messageService, DocumentReviewer llm,
                           int maxDocChars, ProjectionService projectionService,
                           ChannelProjection<ReviewState> projection) {
        this.registry = registry;
        this.channelId = channelId;
        this.messageService = messageService;
        this.llm = llm;
        this.maxDocChars = maxDocChars;
        this.projectionService = projectionService;
        this.projection = projection;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.type() != MessageType.QUERY) return;

        ReviewSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            LOG.warning("ReviewerChannelBackend.post() called but session not found for channel "
                    + channelId + " — session may have ended");
            return;
        }

        Long inReplyTo = messageService
                .findByCorrelationId(message.correlationId().toString())
                .map(m -> m.id)
                .orElse(null);
        if (inReplyTo == null) {
            LOG.warning("Could not resolve inReplyTo for correlationId " + message.correlationId()
                    + " on channel " + channel.name() + " — skipping dispatch");
            return;
        }

        if (session.docAContent().length() > maxDocChars
                || session.docBContent().length() > maxDocChars) {
            dispatch(channel, message, inReplyTo, session,
                    ReviewResult.decline("Documents exceed the maximum size for review."));
            return;
        }

        String selectionContext = buildSelectionContext(session);

        ReviewResult result;
        try {
            var historyResult = projectionService.project(channelId, projection);
            String reviewHistory = conversationRenderer.render(historyResult.state());
            result = llm.review(session.personality(), session.docAContent(),
                    session.docBContent(), selectionContext, reviewHistory, message.content());
        } catch (Exception e) {
            LOG.warning("Backend error on channel " + channel.name() + ": " + e.getMessage());
            result = ReviewResult.decline("Reviewer encountered an error.");
        }
        dispatch(channel, message, inReplyTo, session, result);
    }

    private void dispatch(ChannelRef channel, OutboundMessage message,
                          Long inReplyTo, ReviewSession session, ReviewResult result) {
        MessageType type = result.declined() ? MessageType.DECLINE : MessageType.RESPONSE;
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender(session.instanceId())
                .type(type)
                .content(result.content())
                .inReplyTo(inReplyTo)
                .correlationId(message.correlationId().toString())
                .actorType(ActorType.AGENT)
                .build());
    }

    private static String buildSelectionContext(ReviewSession session) {
        if (session.selectionSide() == null || session.selectionText() == null) return "";
        return "Selected text (Document " + session.selectionSide().name() + "): "
                + session.selectionText();
    }
}
```

- [ ] **Step 3: Update ReviewerChannelBackendFactory**

Add two injected fields:

```java
@ApplicationScoped
public class ReviewerChannelBackendFactory {

    @Inject ReviewSessionRegistry registry;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;
    @Inject DraftHouseConfig config;
    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection projection;

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        if (registry.find(event.channelId()).isEmpty()) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                registry, event.channelId(), messageService, llm, config.reviewer().maxDocChars(),
                projectionService, projection);
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, ReviewerChannelBackend.BACKEND_TYPE);
    }
}
```

Add the missing imports:
```java
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.qhorus.runtime.message.ProjectionService;
```

- [ ] **Step 4: Update ReviewerChannelBackendTest — add mocks and update all stubs to 6 args**

Full file replacement (preserve existing tests, add new mock setup and ordering test):

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;

class ReviewerChannelBackendTest {

    private static final UUID CHANNEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ReviewSessionRegistry registry;
    private MessageService messageService;
    private DocumentReviewer llm;
    private ProjectionService projectionService;
    private ChannelProjection<ReviewState> projection;
    private ReviewerChannelBackend backend;
    private ChannelRef channelRef;
    private ReviewSession session;

    @BeforeEach
    void setUp() {
        registry = mock(ReviewSessionRegistry.class);
        messageService = mock(MessageService.class);
        llm = mock(DocumentReviewer.class);
        projectionService = mock(ProjectionService.class);
        projection = mock(ChannelProjection.class);

        when(projectionService.project(CHANNEL_ID, projection))
                .thenReturn(new ProjectionResult<>(new ReviewState(Map.of(), List.of()), null));

        session = new ReviewSession(
                CHANNEL_ID, CHANNEL_ID.toString(), "drafthouse/sess-1",
                "drafthouse-reviewer-" + CHANNEL_ID,
                "Original text", "Revised text",
                null, null, "You are a reviewer.");

        backend = new ReviewerChannelBackend(
                registry, CHANNEL_ID, messageService, llm, 100_000,
                projectionService, projection);
        channelRef = new ChannelRef(CHANNEL_ID, "drafthouse/sess-1");

        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(session));

        Message queryMsg = new Message();
        queryMsg.id = 42L;
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.of(queryMsg));
    }

    @Test
    void queryDispatches_response_onSuccess() {
        when(llm.review(any(), eq("Original text"), eq("Revised text"), any(), any(), eq("Is this clear?")))
                .thenReturn(new ReviewResult(false, "The revision is clear."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(d.inReplyTo()).isEqualTo(42L);
        assertThat(d.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(d.sender()).isEqualTo(session.instanceId());
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(d.content()).isEqualTo("The revision is clear.");
    }

    @Test
    void queryDispatches_decline_whenReviewerDeclines() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.decline("Out of scope."));

        backend.post(channelRef, query("What is the weather?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Out of scope.");
    }

    @Test
    void queryDispatches_sanitizedDecline_onReviewerException() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("sk-ant-api03-SECRET-KEY"));

        backend.post(channelRef, query("Anything"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Reviewer encountered an error.");
        assertThat(captor.getValue().content()).doesNotContain("sk-ant-api03-SECRET-KEY");
    }

    @Test
    void nonQueryMessage_doesNotCallProjectionOrLlm() {
        OutboundMessage event = outboundMessage(MessageType.EVENT, "Some event", CORRELATION_ID);
        backend.post(channelRef, event);
        verifyNoInteractions(projectionService);
        verifyNoInteractions(llm);
    }

    @Test
    void documentsExceedingMaxSize_dispatchDecline_withoutCallingLlmOrProjection() {
        // Override backend with 5-char max; session has "Original text" (13 chars)
        backend = new ReviewerChannelBackend(
                registry, CHANNEL_ID, messageService, llm, 5,
                projectionService, projection);

        backend.post(channelRef, query("Question?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        verifyNoInteractions(llm);
        verifyNoInteractions(projectionService);
    }

    @Test
    void queryDispatch_callsProjectionBeforeLlm() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Response."));

        backend.post(channelRef, query("Question?"));

        InOrder order = inOrder(projectionService, llm);
        order.verify(projectionService).project(CHANNEL_ID, projection);
        order.verify(llm).review(any(), any(), any(), any(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static OutboundMessage query(String content) {
        return outboundMessage(MessageType.QUERY, content, CORRELATION_ID);
    }

    private static OutboundMessage outboundMessage(MessageType type, String content, UUID correlationId) {
        // OutboundMessage is a Qhorus API type — construct however the existing tests do
        // (preserve whatever factory/builder pattern the class already uses)
        return new OutboundMessage(correlationId, type, content, ActorType.HUMAN);
    }
}
```

> **Note on `OutboundMessage` constructor:** check the existing test or the Qhorus API to confirm the exact constructor signature. The `outboundMessage` helper above shows the logical shape — adjust to match the real API if it differs.

- [ ] **Step 5: Build the project to verify it compiles**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Run ReviewerChannelBackendTest**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerChannelBackendTest
```

Expected: `BUILD SUCCESS`, all 6 tests pass.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): wire ProjectionService into ReviewerChannelBackend — restore conversation memory  Refs #31"
```

---

## Task 7: #35 — Fix UUID channel slug

`UUID.randomUUID()` starts with a digit ~62.5% of the time, failing Qhorus segment validation (`[a-z][a-z0-9]*`). Prefix with `"r-"`.

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`

- [ ] **Step 1: Apply the one-line fix**

In `startReview()`, find:
```java
String channelSlug = UUID.randomUUID().toString();
```
Replace with:
```java
String channelSlug = "r-" + UUID.randomUUID();
```

- [ ] **Step 2: Build to verify**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "fix: prefix UUID channel slug with 'r-' to satisfy Qhorus segment validation  Closes #35"
```

---

## Task 8: Integration tests — update stubs and add multi-turn test

Update `ReviewSessionLifecycleTest` to match the new 6-arg `DocumentReviewer.review()` signature and add a multi-turn test verifying conversation history is passed on the second query.

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java`

- [ ] **Step 1: Update all 5-arg stubs to 6 args**

Find every occurrence of:
```java
when(documentReviewer.review(any(), any(), any(), any(), any()))
```
and every:
```java
.thenReturn(new ReviewResult(...))
// or
.thenThrow(new RuntimeException(...))
```

Update **all** of them (including `@BeforeEach setUp()`) to 6 `any()` args:
```java
when(documentReviewer.review(any(), any(), any(), any(), any(), any()))
        .thenReturn(new ReviewResult(false, "Good revision."));
```

The five stubs requiring update:
1. `setUp()` `@BeforeEach`
2. `query_dispatchesResponse_andFulfillsCommitment` (if it has its own stub)
3. `query_dispatchesDecline_andDeclinesCommitment_whenReviewerDeclines`
4. `query_dispatchesSanitizedDecline_andDeclinesCommitment_onReviewerException`

- [ ] **Step 2: Add test case 5 — multi-turn history**

Add before the helper method `extractSessionId`:

```java
@Test
void secondQuery_receivesPriorExchangeInHistory() {
    String result = tools.startReview(docA.toString(), docB.toString());
    String sessionId = extractSessionId(result);
    activeSessionId = sessionId;
    UUID channelId = UUID.fromString(sessionId);

    // First query — @BeforeEach stub returns "Good revision." for any 6-arg call
    String corrId1 = UUID.randomUUID().toString();
    messageService.dispatch(MessageDispatch.builder().channelId(channelId)
            .sender(DraftHouseMcpTools.HUMAN_INSTANCE_ID)
            .type(MessageType.QUERY).content("First question.")
            .correlationId(corrId1).actorType(ActorType.HUMAN).build());
    await().atMost(TIMEOUT).until(() ->
            messageService.findResponseByCorrelationId(channelId, corrId1).isPresent());

    // Second query
    String corrId2 = UUID.randomUUID().toString();
    messageService.dispatch(MessageDispatch.builder().channelId(channelId)
            .sender(DraftHouseMcpTools.HUMAN_INSTANCE_ID)
            .type(MessageType.QUERY).content("Second question.")
            .correlationId(corrId2).actorType(ActorType.HUMAN).build());
    await().atMost(TIMEOUT).until(() ->
            messageService.findResponseByCorrelationId(channelId, corrId2).isPresent());

    // Capture reviewHistory from both LLM calls — assert on the second invocation
    ArgumentCaptor<String> historyCaptor = ArgumentCaptor.forClass(String.class);
    verify(documentReviewer, times(2))
            .review(any(), any(), any(), any(), historyCaptor.capture(), any());
    String secondHistory = historyCaptor.getAllValues().get(1);

    assertThat(secondHistory).contains("First question.");
    assertThat(secondHistory).contains("Good revision.");         // first answer
    assertThat(secondHistory).doesNotContain("Second question."); // OPEN → excluded
}
```

Add required imports at the top of the file (if not already present):
```java
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
```

- [ ] **Step 3: Run the full integration test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewSessionLifecycleTest
```

Expected: `BUILD SUCCESS`, 5 tests pass (4 existing + 1 new).

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test: update ReviewSessionLifecycleTest — 6-arg stubs and multi-turn history test  Refs #31"
```

---

## Task 9: Run full test suite and update PLATFORM.md

- [ ] **Step 1: Run all tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: `BUILD SUCCESS`, all tests pass including E2E.

- [ ] **Step 2: Update PLATFORM.md cross-repo dependency table**

In `~/claude/casehub/parent/docs/PLATFORM.md`, find the cross-repo dep table section. The existing row for `casehub-drafthouse` api lists `ChannelProjection<S>`. Replace it with the expanded version, and add a new row for the runtime ProjectionService dep:

```
| `casehub-qhorus-api` | `casehub-drafthouse` | `api` | `ChannelProjection<S>`, `RenderableProjection<S>`, `ProjectionResult<S>`, `MessageView`, `MessageType` — debate projection SPI |
| `casehub-qhorus` (runtime) | `casehub-drafthouse` | `runtime` | `ProjectionService` — channel history fold for LLM context |
```

- [ ] **Step 3: Commit PLATFORM.md to casehub-parent repo**

```bash
git -C /Users/mdproctor/claude/casehub/parent add docs/PLATFORM.md
git -C /Users/mdproctor/claude/casehub/parent commit -m "docs: update casehub-drafthouse dep table — add RenderableProjection, ProjectionService  no-issue: dep table maintenance"
```

- [ ] **Step 4: Commit PLATFORM.md update to drafthouse workspace**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse commit --allow-empty -m "docs: PLATFORM.md updated in parent repo for qhorus dep additions  Refs #31"
```

Actually only commit if there are staged changes. If PLATFORM.md is in a separate repo, the drafthouse repo needs no commit for it — just note it in the handover.

---

## Self-Review

**Spec coverage check:**

| Spec section | Task |
|---|---|
| Delete dead code + claude-agent module | Task 3 |
| Delete CritiqueResourceTest (#37) | Task 3 |
| EntryType.DECLINED, ReviewStatus.DECLINED | Task 2 |
| SummaryRenderer switch exhaustiveness + strikethrough | Task 2 |
| SummaryRendererTest rebuild + DECLINED test | Tasks 1 + 2 |
| ReviewConversationRenderer (TDD) | Task 4 |
| DebateChannelProjection (TDD, all 15 tests) | Task 5 |
| DocumentReviewer reviewHistory param | Task 6 |
| ReviewerChannelBackend — projection + history | Task 6 |
| ReviewerChannelBackendFactory — new injections | Task 6 |
| ReviewerChannelBackendTest — mocks, ordering, maxDocChars | Task 6 |
| #35 UUID slug fix | Task 7 |
| ReviewSessionLifecycleTest — 5→6 args + test 5 | Task 8 |
| PLATFORM.md dep table | Task 9 |

All spec requirements have a corresponding task. ✅
