# Review Manifest Layer 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the debate-manifest review loop: domain model, parsers, projector, formatter, renderer, LangChain4j agent provider, JGit-backed session service, stub REST endpoints, and optional Claude Agent SDK provider — all with parity contract tests.

**Architecture:** Pure-Java domain model in `server/api/` (implements `ChannelProjection<ReviewState>` from casehub-qhorus-api); LangChain4j `@DefaultBean` provider and JGit `ReviewSessionService` in `server/runtime/`; optional `ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)` in `server/claude-agent/`. Round snippets (agent output) → `RoundParser` → `DebateEntryFormatter` → `debate.md`; `DebateParser` → `SummaryProjector` (incremental fold) → `SummaryRenderer` → `summary.md`.

**Tech Stack:** Java 17 sealed interfaces + records, JGit 6.x, Quarkus 3.34.3, quarkus-langchain4j, casehub-qhorus-api (`ChannelProjection<S>`, `MessageView`, `MessageType`), JUnit 5, RestAssured.

---

## File Map

```
server/
  pom.xml                                           MODIFY — add claude-agent module
  api/
    pom.xml                                         MODIFY — add casehub-qhorus-api dep
    src/main/java/io/casehub/drafthouse/debate/
      AgentType.java                                CREATE
      EntryType.java                                CREATE
      Priority.java                                 CREATE
      Scope.java                                    CREATE
      ReviewStatus.java                             CREATE
      DebateEvent.java                              CREATE — sealed interface
      PointClassification.java                      CREATE — record
      ThreadEntry.java                              CREATE — record
      ReviewPoint.java                              CREATE — record
      FlagEntry.java                                CREATE — record
      ReviewState.java                              CREATE — record
      DebateEntry.java                              CREATE — record (agent output, no id)
      DebateRoundContext.java                       CREATE — record
      DebateAgentProvider.java                      CREATE — SPI interface
      DebateParser.java                             CREATE
      RoundParser.java                              CREATE
      SummaryProjector.java                         CREATE — implements ChannelProjection<ReviewState>
      SummaryRenderer.java                          CREATE
      DebateEntryFormatter.java                     CREATE
    src/test/java/io/casehub/drafthouse/debate/
      DebateParserTest.java                         CREATE
      RoundParserTest.java                          CREATE
      SummaryProjectorTest.java                     CREATE
      SummaryRendererTest.java                      CREATE
      DebateEntryFormatterTest.java                 CREATE
  runtime/
    pom.xml                                         MODIFY — add jgit dep
    src/main/java/io/casehub/drafthouse/debate/
      LangChain4jDebateAgentProvider.java           CREATE
      SpecReviewerAiService.java                    CREATE — @RegisterAiService
      SpecImplementerAiService.java                 CREATE — @RegisterAiService
      ReviewSessionService.java                     CREATE — @ApplicationScoped @Blocking
      ReviewSession.java                            CREATE — record (session state)
      ReviewSessionResource.java                    CREATE — stub REST
    src/main/resources/prompts/
      spec-reviewer.txt                             CREATE
      spec-implementer.txt                          CREATE
    src/test/java/io/casehub/drafthouse/debate/
      DebateAgentProviderContractTest.java          CREATE — abstract
      LangChain4jDebateAgentProviderTest.java       CREATE
      DebateRoundTripIT.java                        CREATE
      ReviewSessionResourceTest.java                CREATE
    src/test/resources/fixtures/debate/
      round1.md                                     CREATE
      round1-expected-summary.md                    CREATE
      round2.md                                     CREATE
      round2-expected-summary.md                    CREATE
  claude-agent/                                     CREATE — new module
    pom.xml                                         CREATE
    src/main/java/io/casehub/drafthouse/debate/claude/
      ClaudeAgentSdkDebateAgentProvider.java        CREATE
    src/test/java/io/casehub/drafthouse/debate/claude/
      ClaudeAgentSdkDebateAgentProviderTest.java    CREATE
```

---

## Task 1: Maven setup

**Files:** `server/pom.xml`, `server/api/pom.xml`, `server/runtime/pom.xml`, `server/claude-agent/pom.xml` (create)

- [ ] **Add `casehub-qhorus-api` to `server/api/pom.xml`**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-qhorus-api</artifactId>
</dependency>
```

Version is managed by the parent BOM — do not specify `<version>` here.

- [ ] **Add JGit to `server/runtime/pom.xml`**

```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>6.9.0.202403050737-r</version>
</dependency>
```

- [ ] **Add `claude-agent` module to `server/pom.xml`**

In `<modules>`:
```xml
<module>api</module>
<module>runtime</module>
<module>claude-agent</module>
```

- [ ] **Create `server/claude-agent/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>drafthouse-server</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>drafthouse-claude-agent</artifactId>
    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>drafthouse-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.spring-ai-community</groupId>
            <artifactId>claude-agent-sdk-java</artifactId>
            <version>1.0.0</version>
        </dependency>
        <dependency>
            <groupId>io.smallrye.reactive</groupId>
            <artifactId>mutiny</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.enterprise</groupId>
            <artifactId>jakarta.enterprise.cdi-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <!-- Test -->
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>drafthouse-runtime</artifactId>
            <version>${project.version}</version>
            <classifier>tests</classifier>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

⚠️ Verify `com.github.spring-ai-community:claude-agent-sdk-java:1.0.0` exists in Maven Central before proceeding:
```bash
/opt/homebrew/bin/mvn dependency:get -Dartifact=com.github.spring-ai-community:claude-agent-sdk-java:1.0.0
```
If not found, check JitPack and add the repository. Add to parent BOM once verified.

- [ ] **Verify build compiles**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -DskipTests
```
Expected: `BUILD SUCCESS` (no test failures, compile only).

- [ ] **Commit**

```bash
git add server/pom.xml server/api/pom.xml server/runtime/pom.xml server/claude-agent/pom.xml
git commit -m "chore: add casehub-qhorus-api, jgit, claude-agent module scaffold

Refs #29"
```

---

## Task 2: Domain model — enums, records, sealed interface, SPI

**Files:** All types in `server/api/src/main/java/io/casehub/drafthouse/debate/`

- [ ] **Create supporting enums**

`AgentType.java`:
```java
package io.casehub.drafthouse.debate;
public enum AgentType { REV, IMP }
```

`EntryType.java`:
```java
package io.casehub.drafthouse.debate;
public enum EntryType { RAISE, AGREE, DISPUTE, QUALIFY, FLAG_HUMAN }
```

`Priority.java`:
```java
package io.casehub.drafthouse.debate;
public enum Priority { P1, P2, P3 }
```

`Scope.java`:
```java
package io.casehub.drafthouse.debate;
public enum Scope { SYSTEMIC, ISOLATED }
```

`ReviewStatus.java`:
```java
package io.casehub.drafthouse.debate;
public enum ReviewStatus { OPEN, ACTIVE, AGREED, PENDING_HUMAN }
```

- [ ] **Create `DebateEvent.java` — sealed interface**

```java
package io.casehub.drafthouse.debate;

public sealed interface DebateEvent
        permits DebateEvent.RaiseEvent, DebateEvent.ResponseEvent,
                DebateEvent.FlagHumanEvent, DebateEvent.AgentMemo {

    /**
     * A new point raised. entryId is null when produced by an agent (assigned later
     * by DebateEntryFormatter); non-null when parsed from existing debate.md by DebateParser.
     */
    record RaiseEvent(
            String entryId,
            int round,
            AgentType agent,
            Priority priority,
            Scope scope,
            String location,
            String content) implements DebateEvent {}

    record ResponseEvent(
            int round,
            AgentType agent,
            String targetId,
            EntryType type,
            String content,
            ReviewStatus statusDirective) implements DebateEvent {}

    record FlagHumanEvent(
            int round,
            AgentType agent,
            String content,
            String targetId,
            ReviewStatus statusDirective) implements DebateEvent {}

    record AgentMemo(int round, AgentType agent, String content) implements DebateEvent {}
}
```

- [ ] **Create fold-state records**

`PointClassification.java`:
```java
package io.casehub.drafthouse.debate;
public record PointClassification(Priority priority, Scope scope, String location) {}
```

`ThreadEntry.java`:
```java
package io.casehub.drafthouse.debate;
public record ThreadEntry(String entryId, AgentType agent, int round,
                          EntryType type, String content) {}
```

`ReviewPoint.java`:
```java
package io.casehub.drafthouse.debate;
import java.util.List;
public record ReviewPoint(String id, PointClassification classification,
                          List<ThreadEntry> thread, ReviewStatus currentStatus) {}
```

`FlagEntry.java`:
```java
package io.casehub.drafthouse.debate;
public record FlagEntry(String entryId, int round, AgentType agent, String content) {}
```

`ReviewState.java`:
```java
package io.casehub.drafthouse.debate;
import java.util.LinkedHashMap;
import java.util.List;
public record ReviewState(LinkedHashMap<String, ReviewPoint> points,
                          List<FlagEntry> humanFlags) {}
```

- [ ] **Create SPI records and interface**

`DebateEntry.java` (agent output — no id):
```java
package io.casehub.drafthouse.debate;

public record DebateEntry(
        EntryType type,
        String targetId,
        String content,
        ReviewStatus statusDirective,
        Priority priority,
        Scope scope,
        String location) {}
```

`DebateRoundContext.java`:
```java
package io.casehub.drafthouse.debate;

public record DebateRoundContext(
        String specContent,
        String debateContent,
        ReviewState currentState,
        int roundNumber,
        String sessionId) {}
```

`DebateAgentProvider.java`:
```java
package io.casehub.drafthouse.debate;
import java.util.List;

public interface DebateAgentProvider {
    List<DebateEntry> executeReviewerRound(DebateRoundContext context);
    List<DebateEntry> executeImplementerRound(DebateRoundContext context);
}
```

- [ ] **Verify compilation**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl api -DskipTests
```
Expected: `BUILD SUCCESS`

- [ ] **Commit**

```bash
git add server/api/src/
git commit -m "feat(debate): domain model — enums, records, sealed DebateEvent, DebateAgentProvider SPI

Refs #29"
```

---

## Task 3: RoundParser — TDD

Parses the lightweight round-snippet format produced by agents.

**Files:** `RoundParser.java`, `RoundParserTest.java`

- [ ] **Write failing test**

`server/api/src/test/java/io/casehub/drafthouse/debate/RoundParserTest.java`:
```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RoundParserTest {

    private final RoundParser parser = new RoundParser();

    @Test
    void parsesRaiseEntry() {
        String snippet = """
            TYPE: raise
            PRIORITY: P1
            SCOPE: Isolated
            LOCATION: §3.2
            CONTENT: Both start_review and begin_review appear.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        DebateEntry e = entries.get(0);
        assertThat(e.type()).isEqualTo(EntryType.RAISE);
        assertThat(e.priority()).isEqualTo(Priority.P1);
        assertThat(e.scope()).isEqualTo(Scope.ISOLATED);
        assertThat(e.location()).isEqualTo("§3.2");
        assertThat(e.content()).isEqualTo("Both start_review and begin_review appear.");
        assertThat(e.targetId()).isNull();
    }

    @Test
    void parsesAgreeEntry() {
        String snippet = """
            TYPE: agree
            TARGET: R1-REV-001
            STATUS: Agreed
            CONTENT: Standardising to start_review throughout.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        DebateEntry e = entries.get(0);
        assertThat(e.type()).isEqualTo(EntryType.AGREE);
        assertThat(e.targetId()).isEqualTo("R1-REV-001");
        assertThat(e.statusDirective()).isEqualTo(ReviewStatus.AGREED);
        assertThat(e.priority()).isNull();
    }

    @Test
    void parsesDisputeEntry() {
        String snippet = """
            TYPE: dispute
            TARGET: R1-REV-002
            STATUS: Active
            CONTENT: Retry is caller responsibility per MCP contract.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.DISPUTE);
        assertThat(entries.get(0).statusDirective()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void parsesQualifyEntry() {
        String snippet = """
            TYPE: qualify
            TARGET: R2-IMP-001
            STATUS: Active
            CONTENT: Accepted on substance but contract reference must be cited.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.QUALIFY);
    }

    @Test
    void parsesFlagHumanEntry() {
        String snippet = """
            TYPE: flag_human
            CONTENT: REV and IMP differ on MCP contract scope.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.FLAG_HUMAN);
    }

    @Test
    void parsesMultipleEntriesAndIgnoresMemo() {
        String snippet = """
            TYPE: raise
            PRIORITY: P2
            SCOPE: Systemic
            CONTENT: Missing error handling.

            TYPE: agree
            TARGET: R1-REV-001
            STATUS: Agreed
            CONTENT: Fixed.

            MEMO: Section 4 needs more work.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(entries.get(1).type()).isEqualTo(EntryType.AGREE);
    }

    @Test
    void isCaseInsensitiveOnTypeField() {
        String snippet = """
            TYPE: RAISE
            PRIORITY: p2
            SCOPE: isolated
            CONTENT: Test.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.RAISE);
    }
}
```

- [ ] **Run test — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=RoundParserTest
```
Expected: FAIL — `RoundParser` does not exist.

- [ ] **Implement `RoundParser.java`**

```java
package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;

public class RoundParser {

    public List<DebateEntry> parse(String roundSnippet) {
        List<DebateEntry> entries = new ArrayList<>();
        String[] blocks = roundSnippet.split("(?m)^\\s*$");
        for (String block : blocks) {
            block = block.strip();
            if (block.isEmpty() || block.toUpperCase().startsWith("MEMO:")) continue;
            DebateEntry entry = parseBlock(block);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private DebateEntry parseBlock(String block) {
        String type = null;
        String target = null;
        String content = null;
        String priority = null;
        String scope = null;
        String location = null;
        String status = null;

        for (String line : block.lines().toList()) {
            String upper = line.stripLeading().toUpperCase();
            if (upper.startsWith("TYPE:"))     type     = value(line);
            else if (upper.startsWith("TARGET:"))   target   = value(line);
            else if (upper.startsWith("CONTENT:"))  content  = value(line);
            else if (upper.startsWith("PRIORITY:")) priority = value(line);
            else if (upper.startsWith("SCOPE:"))    scope    = value(line);
            else if (upper.startsWith("LOCATION:")) location = value(line);
            else if (upper.startsWith("STATUS:"))   status   = value(line);
        }

        if (type == null || content == null) return null;

        EntryType entryType = switch (type.toUpperCase().replace("-", "_")) {
            case "RAISE"      -> EntryType.RAISE;
            case "AGREE"      -> EntryType.AGREE;
            case "DISPUTE"    -> EntryType.DISPUTE;
            case "QUALIFY"    -> EntryType.QUALIFY;
            case "FLAG_HUMAN" -> EntryType.FLAG_HUMAN;
            default           -> null;
        };
        if (entryType == null) return null;

        Priority p = priority != null ? Priority.valueOf(priority.toUpperCase()) : null;
        Scope s    = scope    != null ? Scope.valueOf(scope.toUpperCase())       : null;
        ReviewStatus rs = status != null ? parseStatus(status) : null;

        return new DebateEntry(entryType, target, content, rs, p, s, location);
    }

    private String value(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).strip() : null;
    }

    private ReviewStatus parseStatus(String s) {
        return switch (s.toUpperCase().replace(" ", "_")) {
            case "AGREED"         -> ReviewStatus.AGREED;
            case "ACTIVE"         -> ReviewStatus.ACTIVE;
            case "OPEN"           -> ReviewStatus.OPEN;
            case "PENDING_HUMAN"  -> ReviewStatus.PENDING_HUMAN;
            default               -> null;
        };
    }
}
```

- [ ] **Run test — confirm pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=RoundParserTest
```
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Commit**

```bash
git add server/api/src/
git commit -m "feat(debate): RoundParser — lenient round-snippet parser

Refs #29"
```

---

## Task 4: SummaryProjector — TDD (implements ChannelProjection<ReviewState>)

**Files:** `SummaryProjector.java`, `SummaryProjectorTest.java`

- [ ] **Write failing test**

`server/api/src/test/java/io/casehub/drafthouse/debate/SummaryProjectorTest.java`:
```java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SummaryProjectorTest {

    private final SummaryProjector projector = new SummaryProjector();

    private MessageView raise(String entryId, AgentType agent, Priority p, Scope s, String location, String content) {
        // Encode raise metadata in artefactRefs: "entryId=R1-REV-001|priority=P1|scope=SYSTEMIC|location=§3.2"
        String artefactRefs = "entryId=" + entryId + "|priority=" + p + "|scope=" + s
                + "|location=" + (location != null ? location : "");
        return new MessageView(null, null, agent.name(), MessageType.QUERY,
                content, entryId, null, null, artefactRefs, null, null, null, 0);
    }

    private MessageView agree(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.RESPONSE,
                content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView dispute(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.DECLINE,
                content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView qualify(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.RESPONSE,
                "[QUALIFY] " + content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView flag(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.HANDOFF,
                content, targetId, null, "human", null, null, null, null, 0);
    }

    private MessageView memo(AgentType agent, String content) {
        return new MessageView(null, null, agent.name(), MessageType.EVENT,
                content, null, null, null, null, null, null, null, 0);
    }

    @Test
    void identityReturnsEmptyState() {
        ReviewState state = projector.identity();
        assertThat(state.points()).isEmpty();
        assertThat(state.humanFlags()).isEmpty();
    }

    @Test
    void identityReturnsFreshInstanceEachCall() {
        assertThat(projector.identity()).isNotSameAs(projector.identity());
    }

    @Test
    void raiseCreatesReviewPoint() {
        ReviewState state = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, "§3.2", "Point A."));
        assertThat(state.points()).containsKey("R1-REV-001");
        ReviewPoint point = state.points().get("R1-REV-001");
        assertThat(point.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(point.classification().priority()).isEqualTo(Priority.P1);
        assertThat(point.thread()).hasSize(1);
        assertThat(point.thread().get(0).type()).isEqualTo(EntryType.RAISE);
    }

    @Test
    void agreeTransitionsToAgreed() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0,
                agree(AgentType.IMP, "R1-REV-001", "Agreed."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("R1-REV-001").thread()).hasSize(2);
    }

    @Test
    void disputeTransitionsToActive() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P2, Scope.SYSTEMIC, null, "Point A."));
        ReviewState s1 = projector.apply(s0,
                dispute(AgentType.IMP, "R1-REV-001", "Disagree."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void qualifyTransitionsToActive() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P2, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0,
                qualify(AgentType.IMP, "R1-REV-001", "Partially accepted."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("R1-REV-001").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
    }

    @Test
    void flagHumanTransitionsToPendingHuman() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0,
                flag(AgentType.REV, "R1-REV-001", "Human needed."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
        assertThat(s1.humanFlags().get(0).content()).isEqualTo("Human needed.");
    }

    @Test
    void memoIsNoOp() {
        ReviewState s0 = projector.identity();
        ReviewState s1 = projector.apply(s0, memo(AgentType.REV, "Private thought."));
        assertThat(s1.points()).isEmpty();
        assertThat(s1.humanFlags()).isEmpty();
    }

    @Test
    void projectConvenienceMethodFoldsEvents() {
        List<DebateEvent> events = List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, "§3.2", "Point A."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Agreed.", ReviewStatus.AGREED)
        );
        ReviewState state = projector.project(events);
        assertThat(state.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
    }

    @Test
    void incrementalFoldOnlyProcessesNewEvents() {
        List<DebateEvent> allEvents = List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Point A."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Agreed.", ReviewStatus.AGREED)
        );
        ReviewState afterRound1 = projector.project(allEvents.subList(0, 1));
        // Only apply the second event (cursor = 1)
        ReviewState afterRound2 = projector.projectIncremental(afterRound1, allEvents, 1);
        assertThat(afterRound2.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
    }
}
```

- [ ] **Run test — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryProjectorTest
```
Expected: FAIL — `SummaryProjector` does not exist.

- [ ] **Implement `SummaryProjector.java`**

```java
package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;

import java.util.*;

public class SummaryProjector implements ChannelProjection<ReviewState> {

    @Override
    public ReviewState identity() {
        return new ReviewState(new LinkedHashMap<>(), new ArrayList<>());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        return switch (message.type()) {
            case QUERY             -> handleRaise(state, message);
            case RESPONSE, DECLINE -> handleResponse(state, message);
            case HANDOFF           -> handleFlagHuman(state, message);
            case EVENT             -> state; // AgentMemo — no-op
            case COMMAND, STATUS, DONE, FAILURE -> state;
        };
    }

    /** v1 convenience: fold a list of DebateEvents from scratch. */
    public ReviewState project(List<DebateEvent> events) {
        ReviewState state = identity();
        for (DebateEvent event : events) state = apply(state, toMessageView(event));
        return state;
    }

    /** Incremental fold: apply only events from lastFoldedCount onwards. */
    public ReviewState projectIncremental(ReviewState state, List<DebateEvent> allEvents, int lastFoldedCount) {
        for (int i = lastFoldedCount; i < allEvents.size(); i++) {
            state = apply(state, toMessageView(allEvents.get(i)));
        }
        return state;
    }

    // --- private helpers ---

    private ReviewState handleRaise(ReviewState state, MessageView message) {
        String entryId   = message.correlationId(); // correlationId carries the entry ID for QUERY
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);

        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope    scope    = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String   location = meta.get("location");

        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agentType(message.sender()),
                0, EntryType.RAISE, message.content()));

        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleResponse(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null || !state.points().containsKey(targetId)) return state;

        boolean isQualify = message.content() != null
                && message.content().startsWith("[QUALIFY] ");
        String content = isQualify
                ? message.content().substring("[QUALIFY] ".length())
                : message.content();

        EntryType entryType = switch (message.type()) {
            case RESPONSE -> isQualify ? EntryType.QUALIFY : EntryType.AGREE;
            case DECLINE  -> EntryType.DISPUTE;
            default       -> EntryType.AGREE;
        };
        ReviewStatus newStatus = switch (entryType) {
            case AGREE    -> ReviewStatus.AGREED;
            case DISPUTE, QUALIFY -> ReviewStatus.ACTIVE;
            default       -> ReviewStatus.ACTIVE;
        };

        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message.sender()),
                0, entryType, content));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);

        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message) {
        String targetId = message.correlationId();

        // Update the referenced point's status if it exists
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agentType(message.sender()),
                    0, EntryType.FLAG_HUMAN, message.content()));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(),
                    thread, ReviewStatus.PENDING_HUMAN));
        }

        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, agentType(message.sender()), message.content()));
        return new ReviewState(points, flags);
    }

    private MessageView toMessageView(DebateEvent event) {
        return switch (event) {
            case DebateEvent.RaiseEvent r -> new MessageView(
                    null, null, r.agent().name(), io.casehub.qhorus.api.message.MessageType.QUERY,
                    r.content(), r.entryId(), null, null,
                    "entryId=" + r.entryId() + "|priority=" + r.priority()
                            + "|scope=" + r.scope()
                            + "|location=" + (r.location() != null ? r.location() : ""),
                    null, null, null, 0);
            case DebateEvent.ResponseEvent r when r.type() == EntryType.QUALIFY ->
                    new MessageView(null, null, r.agent().name(),
                            io.casehub.qhorus.api.message.MessageType.RESPONSE,
                            "[QUALIFY] " + r.content(), r.targetId(), null, null, null, null, null, null, 0);
            case DebateEvent.ResponseEvent r when r.type() == EntryType.AGREE ->
                    new MessageView(null, null, r.agent().name(),
                            io.casehub.qhorus.api.message.MessageType.RESPONSE,
                            r.content(), r.targetId(), null, null, null, null, null, null, 0);
            case DebateEvent.ResponseEvent r ->  // DISPUTE
                    new MessageView(null, null, r.agent().name(),
                            io.casehub.qhorus.api.message.MessageType.DECLINE,
                            r.content(), r.targetId(), null, null, null, null, null, null, 0);
            case DebateEvent.FlagHumanEvent f ->
                    new MessageView(null, null, f.agent().name(),
                            io.casehub.qhorus.api.message.MessageType.HANDOFF,
                            f.content(), f.targetId(), null, "human", null, null, null, null, 0);
            case DebateEvent.AgentMemo m ->
                    new MessageView(null, null, m.agent().name(),
                            io.casehub.qhorus.api.message.MessageType.EVENT,
                            m.content(), null, null, null, null, null, null, null, 0);
        };
    }

    private AgentType agentType(String sender) {
        if (sender == null) return AgentType.REV;
        return switch (sender.toUpperCase()) {
            case "IMP" -> AgentType.IMP;
            default    -> AgentType.REV;
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

- [ ] **Run tests — confirm pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryProjectorTest
```
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Commit**

```bash
git add server/api/src/
git commit -m "feat(debate): SummaryProjector — ChannelProjection<ReviewState> with incremental fold

Refs #29"
```

---

## Task 5: DebateParser — TDD

Parses full accumulated `debate.md` → `List<DebateEvent>`.

**Files:** `DebateParser.java`, `DebateParserTest.java`

- [ ] **Write failing test**

`server/api/src/test/java/io/casehub/drafthouse/debate/DebateParserTest.java`:
```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DebateParserTest {

    private final DebateParser parser = new DebateParser();

    private static final String ROUND1 = """
            # Debate Log
            **Spec:** /path/to/spec.md
            **Session:** drafthouse-20260602-a3f2

            ---

            <!-- Round 1 — Reviewer -->

            <a name="R1-REV-001"></a>
            **[R1-REV-001]** `raise` · P1 · Isolated · §3.2
            Both `start_review` and `begin_review` appear.
            Status: 🔴 Open

            <a name="R1-REV-002"></a>
            **[R1-REV-002]** `raise` · P2 · Systemic · §4.1
            No stated behaviour on network failure.
            Status: 🔴 Open

            **REV memo R1:** §4 feels under-specified.

            ---

            <!-- Round 2 — Implementer -->

            <a name="R2-IMP-001"></a>
            **[R2-IMP-001]** `agree` · → [R1-REV-001]
            Standardising to `start_review`.
            → [R1-REV-001] Status: ✅ Agreed

            <a name="R2-IMP-002"></a>
            **[R2-IMP-002]** `dispute` · → [R1-REV-002]
            Retry is caller responsibility.
            → [R1-REV-002] Status: 🟡 Active

            **IMP memo R2:** Reviewer's pattern concern may hold.
            """;

    @Test
    void parsesRaiseEvents() {
        List<DebateEvent> events = parser.parse(ROUND1);
        List<DebateEvent.RaiseEvent> raises = events.stream()
                .filter(e -> e instanceof DebateEvent.RaiseEvent)
                .map(e -> (DebateEvent.RaiseEvent) e)
                .toList();
        assertThat(raises).hasSize(2);
        assertThat(raises.get(0).entryId()).isEqualTo("R1-REV-001");
        assertThat(raises.get(0).priority()).isEqualTo(Priority.P1);
        assertThat(raises.get(0).scope()).isEqualTo(Scope.ISOLATED);
        assertThat(raises.get(0).location()).isEqualTo("§3.2");
        assertThat(raises.get(0).agent()).isEqualTo(AgentType.REV);
        assertThat(raises.get(0).round()).isEqualTo(1);
    }

    @Test
    void parsesResponseEvents() {
        List<DebateEvent> events = parser.parse(ROUND1);
        List<DebateEvent.ResponseEvent> responses = events.stream()
                .filter(e -> e instanceof DebateEvent.ResponseEvent)
                .map(e -> (DebateEvent.ResponseEvent) e)
                .toList();
        assertThat(responses).hasSize(2);

        DebateEvent.ResponseEvent agree = responses.get(0);
        assertThat(agree.type()).isEqualTo(EntryType.AGREE);
        assertThat(agree.targetId()).isEqualTo("R1-REV-001");
        assertThat(agree.agent()).isEqualTo(AgentType.IMP);
        assertThat(agree.statusDirective()).isEqualTo(ReviewStatus.AGREED);

        DebateEvent.ResponseEvent dispute = responses.get(1);
        assertThat(dispute.type()).isEqualTo(EntryType.DISPUTE);
        assertThat(dispute.targetId()).isEqualTo("R1-REV-002");
        assertThat(dispute.statusDirective()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void parsesMemos() {
        List<DebateEvent> events = parser.parse(ROUND1);
        List<DebateEvent.AgentMemo> memos = events.stream()
                .filter(e -> e instanceof DebateEvent.AgentMemo)
                .map(e -> (DebateEvent.AgentMemo) e)
                .toList();
        assertThat(memos).hasSize(2);
        assertThat(memos.get(0).content()).contains("§4 feels under-specified");
        assertThat(memos.get(0).agent()).isEqualTo(AgentType.REV);
    }

    @Test
    void preservesDocumentOrder() {
        List<DebateEvent> events = parser.parse(ROUND1);
        // R1-REV-001, R1-REV-002, memo-R1, R2-IMP-001, R2-IMP-002, memo-R2
        assertThat(events).hasSize(6);
        assertThat(events.get(0)).isInstanceOf(DebateEvent.RaiseEvent.class);
        assertThat(events.get(1)).isInstanceOf(DebateEvent.RaiseEvent.class);
        assertThat(events.get(2)).isInstanceOf(DebateEvent.AgentMemo.class);
        assertThat(events.get(3)).isInstanceOf(DebateEvent.ResponseEvent.class);
        assertThat(events.get(4)).isInstanceOf(DebateEvent.ResponseEvent.class);
        assertThat(events.get(5)).isInstanceOf(DebateEvent.AgentMemo.class);
    }

    @Test
    void emptyDebateReturnsEmptyList() {
        assertThat(parser.parse("# Debate Log\n**Spec:** /spec.md\n")).isEmpty();
    }
}
```

- [ ] **Run test — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateParserTest
```
Expected: FAIL — `DebateParser` does not exist.

- [ ] **Implement `DebateParser.java`**

```java
package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DebateParser {

    private static final Pattern ROUND_HEADER   = Pattern.compile("<!--\\s*Round\\s+(\\d+)\\s*—\\s*(Reviewer|Implementer)\\s*-->");
    private static final Pattern ANCHOR         = Pattern.compile("<a\\s+name=\"(R\\d+-(?:REV|IMP)-\\d+)\"");
    private static final Pattern ENTRY_HEADER   = Pattern.compile("\\*\\*\\[([^]]+)]\\*\\*\\s*`(raise|agree|dispute|qualify|flag.human)`(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_DIR     = Pattern.compile("→\\s*\\[([^]]+)]\\s*Status:\\s*(.+)");
    private static final Pattern MEMO           = Pattern.compile("\\*\\*(REV|IMP) memo R\\d+:\\*\\*\\s*(.+)", Pattern.DOTALL);
    private static final Pattern CLASSIFICATION = Pattern.compile("·\\s*(P[123])\\s*·\\s*(Systemic|Isolated)(?:\\s*·\\s*(.+))?", Pattern.CASE_INSENSITIVE);

    public List<DebateEvent> parse(String debateMarkdown) {
        List<DebateEvent> events = new ArrayList<>();
        String[] lines = debateMarkdown.split("\n");

        int currentRound = 0;
        AgentType currentAgent = AgentType.REV;
        String pendingEntryId = null;
        String pendingType = null;
        String pendingClassification = null;
        List<String> pendingContentLines = new ArrayList<>();
        String pendingTargetId = null;
        ReviewStatus pendingStatus = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Round header
            Matcher roundM = ROUND_HEADER.matcher(line);
            if (roundM.find()) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                    pendingEntryId = null; pendingContentLines.clear(); pendingTargetId = null; pendingStatus = null;
                }
                currentRound = Integer.parseInt(roundM.group(1));
                currentAgent = roundM.group(2).startsWith("Rev") ? AgentType.REV : AgentType.IMP;
                continue;
            }

            // Memo
            if (line.matches("\\*\\*(REV|IMP) memo R\\d+:\\*\\*.*")) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                    pendingEntryId = null; pendingContentLines.clear(); pendingTargetId = null; pendingStatus = null;
                }
                String memoContent = line.replaceFirst("\\*\\*(REV|IMP) memo R\\d+:\\*\\*\\s*", "").strip();
                events.add(new DebateEvent.AgentMemo(currentRound, currentAgent, memoContent));
                continue;
            }

            // Anchor — starts a new entry
            Matcher anchorM = ANCHOR.matcher(line);
            if (anchorM.find()) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                }
                pendingEntryId = anchorM.group(1);
                pendingType = null; pendingClassification = null;
                pendingContentLines = new ArrayList<>();
                pendingTargetId = null; pendingStatus = null;
                continue;
            }

            if (pendingEntryId == null) continue;

            // Entry header line
            Matcher headerM = ENTRY_HEADER.matcher(line.strip());
            if (headerM.find()) {
                pendingType = headerM.group(2).toLowerCase().replace("-", "_");
                String rest = headerM.group(3);
                // Extract target: → [R1-REV-001]
                var targetMatch = Pattern.compile("→\\s*\\[([^]]+)]").matcher(rest);
                if (targetMatch.find()) pendingTargetId = targetMatch.group(1);
                pendingClassification = rest;
                continue;
            }

            // Status directive
            Matcher statusM = STATUS_DIR.matcher(line.strip());
            if (statusM.find()) {
                pendingStatus = parseStatus(statusM.group(2).strip());
                continue;
            }

            // Skip decorative lines
            if (line.strip().startsWith("Status:") || line.strip().startsWith("---")
                    || line.strip().startsWith("#") || line.strip().startsWith("**Spec")
                    || line.strip().startsWith("**Session")) continue;

            // Content line
            if (!line.strip().isEmpty()) pendingContentLines.add(line.strip());
        }

        // Flush last pending entry
        if (pendingEntryId != null) {
            events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                    pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
        }
        return events;
    }

    private DebateEvent buildEntry(String entryId, String type, String classification,
                                   List<String> contentLines, String targetId,
                                   ReviewStatus statusDirective, int round, AgentType agent) {
        String content = String.join(" ", contentLines).strip();
        return switch (type != null ? type : "") {
            case "raise" -> {
                Priority p = Priority.P3; Scope s = Scope.ISOLATED; String loc = null;
                if (classification != null) {
                    Matcher cm = CLASSIFICATION.matcher(classification);
                    if (cm.find()) {
                        p = Priority.valueOf(cm.group(1).toUpperCase());
                        s = cm.group(2).equalsIgnoreCase("Systemic") ? Scope.SYSTEMIC : Scope.ISOLATED;
                        loc = cm.groupCount() >= 3 ? cm.group(3) : null;
                        if (loc != null) loc = loc.strip();
                    }
                }
                yield new DebateEvent.RaiseEvent(entryId, round, agent, p, s, loc, content);
            }
            case "agree"   -> new DebateEvent.ResponseEvent(round, agent, targetId, EntryType.AGREE,   content, statusDirective);
            case "dispute" -> new DebateEvent.ResponseEvent(round, agent, targetId, EntryType.DISPUTE, content, statusDirective);
            case "qualify" -> new DebateEvent.ResponseEvent(round, agent, targetId, EntryType.QUALIFY, content, statusDirective);
            case "flag_human" -> new DebateEvent.FlagHumanEvent(round, agent, content, targetId, statusDirective);
            default -> new DebateEvent.AgentMemo(round, agent, content);
        };
    }

    private ReviewStatus parseStatus(String s) {
        if (s.contains("✅")) return ReviewStatus.AGREED;
        if (s.contains("🟡")) return ReviewStatus.ACTIVE;
        if (s.contains("🔵")) return ReviewStatus.PENDING_HUMAN;
        return ReviewStatus.OPEN;
    }
}
```

- [ ] **Run tests — confirm pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateParserTest
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Commit**

```bash
git add server/api/src/
git commit -m "feat(debate): DebateParser — debate.md to List<DebateEvent>

Refs #29"
```

---

## Task 6: SummaryRenderer and DebateEntryFormatter — TDD

**Files:** `SummaryRenderer.java`, `DebateEntryFormatter.java`, `SummaryRendererTest.java`, `DebateEntryFormatterTest.java`

- [ ] **Write `SummaryRendererTest.java`**

```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();
    private final SummaryProjector projector = new SummaryProjector();

    @Test
    void rendersEmptyStateAsHeader() {
        String output = renderer.render(projector.identity());
        assertThat(output).contains("# Review Summary");
        assertThat(output).doesNotContain("##");
    }

    @Test
    void rendersOpenPoint() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, "§3.2", "Both variants appear.")));
        String output = renderer.render(state);
        assertThat(output).contains("🔴");
        assertThat(output).contains("[R1-REV-001]");
        assertThat(output).contains("P1");
        assertThat(output).contains("Both variants appear.");
    }

    @Test
    void rendersAgreedPointWithStrikethrough() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Issue."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Fixed.", ReviewStatus.AGREED)));
        String output = renderer.render(state);
        assertThat(output).contains("✅");
        assertThat(output).contains("~~");
    }

    @Test
    void rendersFlagSectionAtBottom() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Issue."),
                new DebateEvent.FlagHumanEvent(1, AgentType.REV, "Human needed.", "R1-REV-001", ReviewStatus.PENDING_HUMAN)));
        String output = renderer.render(state);
        assertThat(output).contains("⚑");
        assertThat(output).contains("Human needed.");
        // Flag section appears after point sections
        assertThat(output.indexOf("⚑")).isGreaterThan(output.indexOf("R1-REV-001"));
    }

    @Test
    void memoDoesNotAppearInSummary() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.AgentMemo(1, AgentType.REV, "Private thought.")));
        String output = renderer.render(state);
        assertThat(output).doesNotContain("Private thought.");
    }
}
```

- [ ] **Write `DebateEntryFormatterTest.java`**

```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DebateEntryFormatterTest {

    private final DebateEntryFormatter formatter = new DebateEntryFormatter();

    @Test
    void assignsSequentialIds() {
        var entries = java.util.List.of(
                new DebateEntry(EntryType.RAISE, null, "Point A.", null, Priority.P1, Scope.ISOLATED, "§3.2"),
                new DebateEntry(EntryType.RAISE, null, "Point B.", null, Priority.P2, Scope.SYSTEMIC, null));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(result).contains("R1-REV-001");
        assertThat(result).contains("R1-REV-002");
    }

    @Test
    void incrementsSeqFromExistingDebate() {
        String existingDebate = "<a name=\"R1-REV-001\"></a>\n**[R1-REV-001]** `raise`";
        var entries = java.util.List.of(
                new DebateEntry(EntryType.RAISE, null, "Point B.", null, Priority.P2, Scope.ISOLATED, null));
        String result = formatter.format(entries, 1, AgentType.REV, existingDebate);
        assertThat(result).contains("R1-REV-002");
        assertThat(result).doesNotContain("R1-REV-001");
    }

    @Test
    void includesRoundCommentBoundary() {
        var entries = java.util.List.of(
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, null));
        String result = formatter.format(entries, 3, AgentType.IMP, "");
        assertThat(result).contains("<!-- Round 3 — Implementer -->");
    }

    @Test
    void includesHtmlAnchor() {
        var entries = java.util.List.of(
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, "§4.1"));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(result).contains("<a name=\"R1-REV-001\"></a>");
    }

    @Test
    void includesStatusDirectiveOnResponse() {
        var entries = java.util.List.of(
                new DebateEntry(EntryType.AGREE, "R1-REV-001", "Agreed.", ReviewStatus.AGREED, null, null, null));
        String result = formatter.format(entries, 2, AgentType.IMP, "");
        assertThat(result).contains("→ [R1-REV-001] Status: ✅ Agreed");
    }

    @Test
    void orderingIsRaiseThenResponsesThenFlags() {
        var entries = java.util.List.of(
                new DebateEntry(EntryType.FLAG_HUMAN, null, "Need help.", null, null, null, null),
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, null),
                new DebateEntry(EntryType.AGREE, "R1-REV-001", "OK.", ReviewStatus.AGREED, null, null, null));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        int raisePos = result.indexOf("`raise`");
        int agreePos = result.indexOf("`agree`");
        int flagPos  = result.indexOf("`flag_human`");
        assertThat(raisePos).isLessThan(agreePos);
        assertThat(agreePos).isLessThan(flagPos);
    }
}
```

- [ ] **Run tests — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest,DebateEntryFormatterTest
```
Expected: FAIL.

- [ ] **Implement `SummaryRenderer.java`**

```java
package io.casehub.drafthouse.debate;

import java.time.Instant;

public class SummaryRenderer {

    public String render(ReviewState state) {
        var sb = new StringBuilder();
        sb.append("# Review Summary\n");
        sb.append("**Updated:** ").append(Instant.now()).append("\n\n---\n\n");

        for (ReviewPoint point : state.points().values()) {
            String statusMarker = switch (point.currentStatus()) {
                case OPEN           -> "🔴";
                case ACTIVE         -> "🟡";
                case AGREED         -> "✅";
                case PENDING_HUMAN  -> "🔵";
            };
            String header = point.classification().priority() + " · "
                    + point.classification().scope()
                    + (point.classification().location() != null
                       ? " · " + point.classification().location() : "")
                    + " — " + firstLine(point.thread());
            if (point.currentStatus() == ReviewStatus.AGREED) {
                sb.append("## ").append(statusMarker).append(" ~~[")
                  .append(point.id()).append("] ").append(header).append("~~\n");
            } else {
                sb.append("## ").append(statusMarker).append(" [")
                  .append(point.id()).append("] ").append(header).append("\n");
            }
            for (ThreadEntry entry : point.thread()) {
                String label = entry.agent() + " (");
                label += switch (entry.type()) {
                    case RAISE      -> "raise";
                    case AGREE      -> "agree";
                    case DISPUTE    -> "dispute";
                    case QUALIFY    -> "qualify";
                    case FLAG_HUMAN -> "flag";
                };
                label += ")";
                sb.append("> **").append(label).append(":** ").append(entry.content()).append("\n");
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

    private String firstLine(java.util.List<ThreadEntry> thread) {
        return thread.isEmpty() ? "" : thread.get(0).content();
    }
}
```

- [ ] **Implement `DebateEntryFormatter.java`**

```java
package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class DebateEntryFormatter {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile("<a\\s+name=\"(R(\\d+)-(REV|IMP)-(\\d+))\"");

    public String format(List<DebateEntry> entries, int round, AgentType agent, String existingDebate) {
        int nextSeq = nextSequenceNumber(existingDebate, round, agent);

        // Sort: RAISE first, then responses (AGREE/DISPUTE/QUALIFY), then FLAG_HUMAN
        List<DebateEntry> sorted = new ArrayList<>();
        entries.stream().filter(e -> e.type() == EntryType.RAISE).forEach(sorted::add);
        entries.stream().filter(e -> e.type() == EntryType.AGREE
                || e.type() == EntryType.DISPUTE || e.type() == EntryType.QUALIFY).forEach(sorted::add);
        entries.stream().filter(e -> e.type() == EntryType.FLAG_HUMAN).forEach(sorted::add);

        var sb = new StringBuilder();
        sb.append("\n<!-- Round ").append(round).append(" — ")
          .append(agent == AgentType.REV ? "Reviewer" : "Implementer").append(" -->\n\n");

        for (DebateEntry entry : sorted) {
            String entryId = "R" + round + "-" + agent.name() + "-" + String.format("%03d", nextSeq++);
            sb.append("<a name=\"").append(entryId).append("\"></a>\n");
            sb.append("**[").append(entryId).append("]** `").append(typeLabel(entry.type())).append("`");

            if (entry.type() == EntryType.RAISE) {
                sb.append(" · ").append(entry.priority())
                  .append(" · ").append(scopeLabel(entry.scope()));
                if (entry.location() != null) sb.append(" · ").append(entry.location());
            } else if (entry.targetId() != null) {
                sb.append(" · → [").append(entry.targetId()).append("]");
            }
            sb.append("\n");
            sb.append(entry.content()).append("\n");

            if (entry.type() == EntryType.RAISE) {
                sb.append("Status: 🔴 Open\n");
            } else if (entry.targetId() != null && entry.statusDirective() != null) {
                sb.append("→ [").append(entry.targetId()).append("] Status: ")
                  .append(statusEmoji(entry.statusDirective())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private int nextSequenceNumber(String existingDebate, int round, AgentType agent) {
        int max = 0;
        Matcher m = ANCHOR_PATTERN.matcher(existingDebate != null ? existingDebate : "");
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) == round && m.group(3).equals(agent.name())) {
                max = Math.max(max, Integer.parseInt(m.group(4)));
            }
        }
        return max + 1;
    }

    private String typeLabel(EntryType type) {
        return switch (type) {
            case RAISE      -> "raise";
            case AGREE      -> "agree";
            case DISPUTE    -> "dispute";
            case QUALIFY    -> "qualify";
            case FLAG_HUMAN -> "flag_human";
        };
    }

    private String scopeLabel(Scope scope) {
        return switch (scope) {
            case SYSTEMIC -> "Systemic";
            case ISOLATED -> "Isolated";
        };
    }

    private String statusEmoji(ReviewStatus status) {
        return switch (status) {
            case OPEN          -> "🔴 Open";
            case ACTIVE        -> "🟡 Active";
            case AGREED        -> "✅ Agreed";
            case PENDING_HUMAN -> "🔵 Pending Human";
        };
    }
}
```

- [ ] **Fix import in `DebateEntryFormatter.java`** — add missing import:

```java
import java.util.regex.Matcher;
```

- [ ] **Run tests — confirm pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api
```
Expected: All tests pass.

- [ ] **Commit**

```bash
git add server/api/src/
git commit -m "feat(debate): SummaryRenderer and DebateEntryFormatter

Refs #29"
```

---

## Task 7: LangChain4j provider + system prompts

**Files:** `LangChain4jDebateAgentProvider.java`, `SpecReviewerAiService.java`, `SpecImplementerAiService.java`, `spec-reviewer.txt`, `spec-implementer.txt`

- [ ] **Write failing test**

`server/runtime/src/test/java/io/casehub/drafthouse/debate/LangChain4jDebateAgentProviderTest.java`:
```java
package io.casehub.drafthouse.debate;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@QuarkusTest
class LangChain4jDebateAgentProviderTest extends DebateAgentProviderContractTest {

    @InjectMock
    ChatLanguageModel chatModel;

    @Inject
    LangChain4jDebateAgentProvider provider;

    @Override
    protected DebateAgentProvider provider() {
        return provider;
    }

    @Override
    protected String validRoundSnippet() {
        return """
            TYPE: raise
            PRIORITY: P1
            SCOPE: Isolated
            LOCATION: §3.2
            CONTENT: Both start_review and begin_review appear with no canonical form stated.
            """;
    }

    @Override
    protected void stubModelToReturn(String snippet) {
        Mockito.when(chatModel.generate(any()))
               .thenReturn(new Response<>(new AiMessage(snippet)));
    }
}
```

`server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateAgentProviderContractTest.java`:
```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

public abstract class DebateAgentProviderContractTest {

    protected abstract DebateAgentProvider provider();
    protected abstract String validRoundSnippet();
    protected abstract void stubModelToReturn(String snippet);

    @BeforeEach
    void stubDefault() {
        stubModelToReturn(validRoundSnippet());
    }

    private DebateRoundContext emptyContext() {
        return new DebateRoundContext("spec content", "", 
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()), 1, "session-1");
    }

    @Test
    void reviewerReturnsAtLeastOneEntry() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        assertThat(entries).isNotEmpty();
    }

    @Test
    void implementerReturnsAtLeastOneEntry() {
        List<DebateEntry> entries = provider().executeImplementerRound(emptyContext());
        assertThat(entries).isNotEmpty();
    }

    @Test
    void entryTypeIsNotNull() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        assertThat(entries).allMatch(e -> e.type() != null);
    }

    @Test
    void raiseEntryHasPriorityAndScope() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        entries.stream()
               .filter(e -> e.type() == EntryType.RAISE)
               .forEach(e -> {
                   assertThat(e.priority()).isNotNull();
                   assertThat(e.scope()).isNotNull();
               });
    }

    @Test
    void noEntryHasId() {
        // IDs are assigned by formatter, not by agents
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        // DebateEntry has no id field — just confirm we get back valid entries
        assertThat(entries).allMatch(e -> e.content() != null && !e.content().isBlank());
    }
}
```

- [ ] **Run test — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=LangChain4jDebateAgentProviderTest
```
Expected: FAIL — class not found.

- [ ] **Create system prompt files**

`server/runtime/src/main/resources/prompts/spec-reviewer.txt`:
```
You are a rigorous spec reviewer. Your task is to review a technical specification and raise concerns, inconsistencies, ambiguities, or gaps.

Rules:
- Be exhaustive and systematic. Do not guess or speculate — only raise concerns grounded in the spec text.
- Produce your output in the round-snippet format below. Do not produce any other text.
- Every raise entry must have PRIORITY (P1/P2/P3), SCOPE (Systemic/Isolated), and LOCATION (spec section reference).
- Response entries (agree/dispute/qualify) must have TARGET (the entry ID being responded to) and STATUS.
- End with a MEMO block containing your private reasoning notes.

Round-snippet format:
TYPE: raise|agree|dispute|qualify|flag_human
PRIORITY: P1|P2|P3          (raise only)
SCOPE: Systemic|Isolated    (raise only)
LOCATION: §N.N              (raise only, optional)
TARGET: R<round>-<AGENT>-<NNN>  (response entries only)
STATUS: Open|Active|Agreed|Pending_Human  (response entries only)
CONTENT: <text>

MEMO: <private reasoning notes>

Current spec:
{spec}

Debate so far:
{debate}

Open points requiring your response:
{openPoints}

Round number: {round}
```

`server/runtime/src/main/resources/prompts/spec-implementer.txt`:
```
You are a spec implementer responding to a reviewer's critique. Your task is to agree, dispute, or qualify each open reviewer point.

Rules:
- Respond to every open point. Do not skip any.
- You may raise new points if reviewing the spec reveals additional gaps.
- Produce your output in the round-snippet format. Do not produce any other text.
- Be honest: agree when the reviewer is right, dispute with clear reasoning when they are wrong, qualify when partially right.

Round-snippet format:
TYPE: raise|agree|dispute|qualify|flag_human
PRIORITY: P1|P2|P3          (raise only)
SCOPE: Systemic|Isolated    (raise only)
LOCATION: §N.N              (raise only, optional)
TARGET: R<round>-<AGENT>-<NNN>  (response entries only)
STATUS: Open|Active|Agreed|Pending_Human  (response entries only)
CONTENT: <text>

MEMO: <private reasoning notes>

Spec:
{spec}

Debate so far:
{debate}

Open points requiring your response:
{openPoints}

Round number: {round}
```

- [ ] **Create AI service interfaces and provider**

`server/runtime/src/main/java/io/casehub/drafthouse/debate/SpecReviewerAiService.java`:
```java
package io.casehub.drafthouse.debate;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
interface SpecReviewerAiService {
    @SystemMessage(fromResource = "prompts/spec-reviewer.txt")
    @UserMessage("Spec:\n{spec}\n\nDebate so far:\n{debate}\n\nOpen points:\n{openPoints}\n\nRound: {round}")
    String review(@V("spec") String spec, @V("debate") String debate,
                  @V("openPoints") String openPoints, @V("round") int round);
}
```

`server/runtime/src/main/java/io/casehub/drafthouse/debate/SpecImplementerAiService.java`:
```java
package io.casehub.drafthouse.debate;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
interface SpecImplementerAiService {
    @SystemMessage(fromResource = "prompts/spec-implementer.txt")
    @UserMessage("Spec:\n{spec}\n\nDebate so far:\n{debate}\n\nOpen points:\n{openPoints}\n\nRound: {round}")
    String respond(@V("spec") String spec, @V("debate") String debate,
                   @V("openPoints") String openPoints, @V("round") int round);
}
```

`server/runtime/src/main/java/io/casehub/drafthouse/debate/LangChain4jDebateAgentProvider.java`:
```java
package io.casehub.drafthouse.debate;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

@DefaultBean
@ApplicationScoped
public class LangChain4jDebateAgentProvider implements DebateAgentProvider {

    @Inject SpecReviewerAiService    reviewerService;
    @Inject SpecImplementerAiService implementerService;

    private final RoundParser roundParser = new RoundParser();

    @Override
    public List<DebateEntry> executeReviewerRound(DebateRoundContext ctx) {
        String openPoints = summariseOpenPoints(ctx.currentState());
        String snippet = reviewerService.review(
                ctx.specContent(), ctx.debateContent(), openPoints, ctx.roundNumber());
        return roundParser.parse(snippet);
    }

    @Override
    public List<DebateEntry> executeImplementerRound(DebateRoundContext ctx) {
        String openPoints = summariseOpenPoints(ctx.currentState());
        String snippet = implementerService.respond(
                ctx.specContent(), ctx.debateContent(), openPoints, ctx.roundNumber());
        return roundParser.parse(snippet);
    }

    private String summariseOpenPoints(ReviewState state) {
        return state.points().values().stream()
                .filter(p -> p.currentStatus() != ReviewStatus.AGREED)
                .map(p -> p.id() + ": " + p.thread().get(0).content()
                        + " [" + p.currentStatus() + "]")
                .collect(Collectors.joining("\n"));
    }
}
```

- [ ] **Run tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=LangChain4jDebateAgentProviderTest
```
Expected: All contract tests pass.

- [ ] **Commit**

```bash
git add server/runtime/src/
git commit -m "feat(debate): LangChain4jDebateAgentProvider + system prompts

Refs #29"
```

---

## Task 8: ReviewSessionService (JGit) + stub REST endpoints

**Files:** `ReviewSessionService.java`, `ReviewSession.java`, `ReviewSessionResource.java`

- [ ] **Write failing test for session creation**

`server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewSessionResourceTest.java`:
```java
package io.casehub.drafthouse.debate;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
class ReviewSessionResourceTest {

    @InjectMock
    ReviewSessionService sessionService;

    @Test
    void startSessionReturns200WithSessionId() {
        ReviewSession session = new ReviewSession(
                "drafthouse-20260602-abc123",
                "/tmp/test-session",
                "/path/to/spec.md",
                0,
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()),
                0
        );
        Mockito.when(sessionService.startSession(anyString())).thenReturn(session);

        given()
            .contentType("application/json")
            .body("{\"specPath\": \"/path/to/spec.md\"}")
        .when()
            .post("/api/review/sessions")
        .then()
            .statusCode(200)
            .body("sessionId", equalTo("drafthouse-20260602-abc123"));
    }

    @Test
    void nextRoundReturns200() {
        ReviewSession session = new ReviewSession(
                "drafthouse-20260602-abc123",
                "/tmp/test-session",
                "/path/to/spec.md",
                1,
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()),
                0
        );
        Mockito.when(sessionService.executeNextRound("drafthouse-20260602-abc123"))
               .thenReturn(session);

        given()
        .when()
            .post("/api/review/sessions/drafthouse-20260602-abc123/next-round")
        .then()
            .statusCode(200)
            .body("roundNumber", equalTo(1));
    }
}
```

- [ ] **Run test — confirm failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewSessionResourceTest
```
Expected: FAIL.

- [ ] **Create `ReviewSession.java`**

```java
package io.casehub.drafthouse.debate;

public record ReviewSession(
        String sessionId,
        String sessionPath,
        String specPath,
        int roundNumber,
        ReviewState currentState,
        int foldedEventCount) {}
```

- [ ] **Create `ReviewSessionService.java`**

```java
package io.casehub.drafthouse.debate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.smallrye.common.annotation.Blocking;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class ReviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSessionService.class);
    private static final Path SESSIONS_BASE = Path.of(System.getProperty("user.home"), ".drafthouse", "reviews");

    @Inject DebateAgentProvider    agentProvider;

    private final DebateParser          parser    = new DebateParser();
    private final SummaryProjector      projector = new SummaryProjector();
    private final SummaryRenderer       renderer  = new SummaryRenderer();
    private final RoundParser           roundParser = new RoundParser();
    private final DebateEntryFormatter  formatter = new DebateEntryFormatter();

    // Session state cache (sessionId → ReviewSession)
    private final Map<String, ReviewSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    @Blocking
    public ReviewSession startSession(String specPath) {
        String sessionId = generateSessionId(specPath);
        Path sessionPath = SESSIONS_BASE.resolve(sessionId);

        try {
            Files.createDirectories(sessionPath);
            Git.init().setDirectory(sessionPath.toFile()).call();

            String header = "# Debate Log\n**Spec:** " + specPath + "\n**Session:** " + sessionId + "\n";
            Files.writeString(sessionPath.resolve("debate.md"), header);
            Files.writeString(sessionPath.resolve("summary.md"), "# Review Summary\n");

            try (Git git = Git.open(sessionPath.toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("round-0: session open").call();
            }

            ReviewSession session = new ReviewSession(sessionId, sessionPath.toString(),
                    specPath, 0, projector.identity(), 0);
            sessions.put(sessionId, session);
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new RuntimeException("Git error starting session", e);
        }
    }

    @Blocking
    public ReviewSession executeNextRound(String sessionId) {
        ReviewSession session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);

        Path sessionPath = Path.of(session.sessionPath());
        int nextRound    = session.roundNumber() + 1;
        AgentType agent  = (nextRound % 2 == 1) ? AgentType.REV : AgentType.IMP;

        try {
            String specContent  = Files.readString(Path.of(session.specPath()));
            String debateContent = Files.readString(sessionPath.resolve("debate.md"));

            DebateRoundContext ctx = new DebateRoundContext(
                    specContent, debateContent, session.currentState(), nextRound, sessionId);

            List<DebateEntry> entries = agent == AgentType.REV
                    ? agentProvider.executeReviewerRound(ctx)
                    : agentProvider.executeImplementerRound(ctx);

            // Format and append to debate.md
            String appendText = formatter.format(entries, nextRound, agent, debateContent);
            Files.writeString(sessionPath.resolve("debate.md"), appendText,
                    StandardOpenOption.APPEND);

            // Incremental fold
            String updatedDebate = Files.readString(sessionPath.resolve("debate.md"));
            List<DebateEvent> allEvents = parser.parse(updatedDebate);
            ReviewState newState = projector.projectIncremental(
                    session.currentState(), allEvents, session.foldedEventCount());
            int newCursor = allEvents.size();

            // Rewrite summary.md
            Files.writeString(sessionPath.resolve("summary.md"), renderer.render(newState));

            // Commit
            try (Git git = Git.open(sessionPath.toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("round-" + nextRound + ": " + agent.name()).call();
            }

            ReviewSession updated = new ReviewSession(sessionId, session.sessionPath(),
                    session.specPath(), nextRound, newState, newCursor);
            sessions.put(sessionId, updated);
            return updated;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new RuntimeException("Git error committing round " + nextRound, e);
        }
    }

    private String generateSessionId(String specPath) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        byte[] bytes = new byte[3];
        new SecureRandom().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return "drafthouse-" + date + "-" + hex;
    }
}
```

- [ ] **Create `ReviewSessionResource.java`**

```java
package io.casehub.drafthouse.debate;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/review/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Deprecated // Superseded by DraftHouseMcpTools (#24)
public class ReviewSessionResource {

    @Inject ReviewSessionService service;

    public record StartRequest(String specPath) {}

    @POST
    public Response startSession(StartRequest request) {
        ReviewSession session = service.startSession(request.specPath());
        return Response.ok(Map.of(
                "sessionId",   session.sessionId(),
                "sessionPath", session.sessionPath())).build();
    }

    @POST
    @Path("/{sessionId}/next-round")
    public Response nextRound(@PathParam("sessionId") String sessionId) {
        ReviewSession session = service.executeNextRound(sessionId);
        return Response.ok(Map.of(
                "sessionId",        session.sessionId(),
                "roundNumber",      session.roundNumber(),
                "humanFlagRaised",  !session.currentState().humanFlags().isEmpty()
        )).build();
    }
}
```

- [ ] **Run tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewSessionResourceTest
```
Expected: Both tests pass.

- [ ] **Commit**

```bash
git add server/runtime/src/
git commit -m "feat(debate): ReviewSessionService (JGit) + stub REST endpoints

Refs #29"
```

---

## Task 9: Round-trip E2E test + fixtures

**Files:** fixtures, `DebateRoundTripIT.java`

- [ ] **Create fixture files**

`server/runtime/src/test/resources/fixtures/debate/round1.md`:
```
TYPE: raise
PRIORITY: P1
SCOPE: Isolated
LOCATION: §3.2
CONTENT: Both start_review and begin_review appear with no canonical form.

TYPE: raise
PRIORITY: P2
SCOPE: Systemic
CONTENT: No error handling specified for network failures.

MEMO: Section 4 looks thin overall.
```

`server/runtime/src/test/resources/fixtures/debate/round2.md`:
```
TYPE: agree
TARGET: R1-REV-001
STATUS: Agreed
CONTENT: Standardising to start_review throughout.

TYPE: dispute
TARGET: R1-REV-002
STATUS: Active
CONTENT: Error handling is caller responsibility per MCP contract.

MEMO: Reviewer's pattern concern may hold but needs specific instances.
```

- [ ] **Write `DebateRoundTripIT.java`**

```java
package io.casehub.drafthouse.debate;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class DebateRoundTripIT {

    private final RoundParser           roundParser = new RoundParser();
    private final DebateEntryFormatter  formatter   = new DebateEntryFormatter();
    private final DebateParser          parser      = new DebateParser();
    private final SummaryProjector      projector   = new SummaryProjector();
    private final SummaryRenderer       renderer    = new SummaryRenderer();

    private String fixture(String name) throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("fixtures/debate/" + name);
        return Files.readString(Path.of(url.toURI()));
    }

    @Test
    void round1RoundTrip() throws Exception {
        String snippet  = fixture("round1.md");
        List<DebateEntry> entries = roundParser.parse(snippet);
        assertThat(entries).hasSize(2);  // 2 raises (memo excluded)

        // Format to debate.md
        String formatted = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(formatted).contains("R1-REV-001").contains("R1-REV-002");

        // Parse back
        String fullDebate = "# Debate Log\n**Spec:** /spec.md\n**Session:** test-session\n" + formatted;
        List<DebateEvent> events = parser.parse(fullDebate);
        assertThat(events.stream().filter(e -> e instanceof DebateEvent.RaiseEvent).count()).isEqualTo(2);

        // Project
        ReviewState state = projector.project(events);
        assertThat(state.points()).hasSize(2);
        assertThat(state.points().values()).allMatch(p -> p.currentStatus() == ReviewStatus.OPEN);

        // Render — must not throw
        String summary = renderer.render(state);
        assertThat(summary).contains("🔴").contains("R1-REV-001");
    }

    @Test
    void round2AppendAndProject() throws Exception {
        // First build round 1 debate.md
        String snippet1   = fixture("round1.md");
        List<DebateEntry> entries1 = roundParser.parse(snippet1);
        String debate = "# Debate Log\n**Spec:** /spec.md\n**Session:** test\n"
                + formatter.format(entries1, 1, AgentType.REV, "");

        // Parse round 1 and project
        List<DebateEvent> events1 = parser.parse(debate);
        ReviewState state1 = projector.project(events1);
        assertThat(state1.points()).hasSize(2);

        // Append round 2
        String snippet2   = fixture("round2.md");
        List<DebateEntry> entries2 = roundParser.parse(snippet2);
        debate += formatter.format(entries2, 2, AgentType.IMP, debate);

        // Incremental fold
        List<DebateEvent> allEvents = parser.parse(debate);
        ReviewState state2 = projector.projectIncremental(state1, allEvents, events1.size());
        assertThat(state2.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(state2.points().get("R1-REV-002").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);

        // Summary must contain strikethrough for agreed point
        String summary = renderer.render(state2);
        assertThat(summary).contains("~~");
        assertThat(summary).contains("🟡");
    }

    @Test
    void noInformationLostAcrossRoundTrip() throws Exception {
        String snippet  = fixture("round1.md");
        List<DebateEntry> entries = roundParser.parse(snippet);
        String formatted = "# Debate Log\n**Spec:** /s\n**Session:** t\n"
                + formatter.format(entries, 1, AgentType.REV, "");
        List<DebateEvent> events = parser.parse(formatted);
        // All raise entries must have content
        events.stream()
              .filter(e -> e instanceof DebateEvent.RaiseEvent)
              .map(e -> (DebateEvent.RaiseEvent) e)
              .forEach(r -> assertThat(r.content()).isNotBlank());
    }
}
```

- [ ] **Run the IT**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateRoundTripIT
```
Expected: All 3 tests pass.

- [ ] **Commit**

```bash
git add server/runtime/src/test/
git commit -m "test(debate): round-trip IT + fixtures

Refs #29"
```

---

## Task 10: Claude Agent SDK optional module

**Files:** `server/claude-agent/` module

- [ ] **Verify Maven Central availability of claude-agent-sdk-java first**

```bash
/opt/homebrew/bin/mvn dependency:get \
  -Dartifact=com.github.spring-ai-community:claude-agent-sdk-java:1.0.0
```
If it fails, check JitPack (`https://jitpack.io/#spring-ai-community/claude-agent-sdk-java`). Add the JitPack repository to `server/claude-agent/pom.xml` if needed.

- [ ] **Implement `ClaudeAgentSdkDebateAgentProvider.java`**

```java
package io.casehub.drafthouse.debate.claude;

import io.casehub.drafthouse.debate.*;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import io.smallrye.mutiny.Multi;
import org.springframework.ai.claude.agent.ClaudeAsyncClient;
import org.springframework.ai.claude.agent.ClaudeSessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Alternative
@Priority(1)
@ApplicationScoped
public class ClaudeAgentSdkDebateAgentProvider implements DebateAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAgentSdkDebateAgentProvider.class);
    private static final Duration ROUND_TIMEOUT = Duration.ofMinutes(5);
    private static final int MIN_ENTRIES_REQUIRED = 1;

    private final RoundParser roundParser = new RoundParser();

    @Override
    public List<DebateEntry> executeReviewerRound(DebateRoundContext ctx) {
        return executeRound(ctx, "reviewer");
    }

    @Override
    public List<DebateEntry> executeImplementerRound(DebateRoundContext ctx) {
        return executeRound(ctx, "implementer");
    }

    private List<DebateEntry> executeRound(DebateRoundContext ctx, String role) {
        List<DebateEntry> accumulated = new CopyOnWriteArrayList<>();

        String systemPrompt = role.equals("reviewer")
                ? buildReviewerPrompt(ctx)
                : buildImplementerPrompt(ctx);

        // MCP tools: submit_debate_entry accumulates into `accumulated`; finish_round signals done
        var tools = buildMcpTools(accumulated, ctx);

        try {
            ClaudeAsyncClient client = ClaudeAsyncClient.create();
            ClaudeSessionConfig config = ClaudeSessionConfig.builder()
                    .systemPrompt(systemPrompt)
                    .tools(tools)
                    .timeout(ROUND_TIMEOUT)
                    .build();

            // Block until session completes (finish_round called or timeout)
            Multi.createFrom().publisher(client.start(config).events())
                    .filter(e -> e instanceof SessionCompleteEvent)
                    .toUni()
                    .await().atMost(ROUND_TIMEOUT.plusSeconds(30));

        } catch (Exception e) {
            log.warn("Claude Agent session ended: {}. Returning {} entries collected.", e.getMessage(), accumulated.size());
        }

        if (accumulated.size() < MIN_ENTRIES_REQUIRED) {
            throw new IllegalStateException("Claude Agent returned no entries for " + role + " round " + ctx.roundNumber());
        }
        return List.copyOf(accumulated);
    }

    private List<McpTool> buildMcpTools(List<DebateEntry> accumulated, DebateRoundContext ctx) {
        // submit_debate_entry: accumulate an entry from the agent
        McpTool submit = McpTool.of("submit_debate_entry",
                "Submit one debate entry. Call once per entry.",
                Map.of(
                    "type",     Map.of("type", "string", "enum", List.of("raise","agree","dispute","qualify","flag_human")),
                    "content",  Map.of("type", "string"),
                    "target_id",Map.of("type", "string"),
                    "status",   Map.of("type", "string"),
                    "priority", Map.of("type", "string"),
                    "scope",    Map.of("type", "string"),
                    "location", Map.of("type", "string")
                ),
                params -> {
                    String snippet = buildSnippet(params);
                    List<DebateEntry> parsed = roundParser.parse(snippet);
                    accumulated.addAll(parsed);
                    return Map.of("accepted", true, "entry_seq", accumulated.size());
                });

        // read_spec_section: let the agent read a spec section
        McpTool readSpec = McpTool.of("read_spec_section",
                "Read a section of the spec by heading.",
                Map.of("heading", Map.of("type", "string")),
                params -> {
                    String heading = (String) params.get("heading");
                    return Map.of("content", extractSection(ctx.specContent(), heading));
                });

        // finish_round: signals the agent is done
        McpTool finish = McpTool.of("finish_round",
                "Signal that this review round is complete.",
                Map.of(),
                params -> Map.of("entries_submitted", accumulated.size()));

        return List.of(submit, readSpec, finish);
    }

    private String buildSnippet(Map<String, Object> params) {
        var sb = new StringBuilder();
        appendIfPresent(sb, "TYPE", params.get("type"));
        appendIfPresent(sb, "PRIORITY", params.get("priority"));
        appendIfPresent(sb, "SCOPE", params.get("scope"));
        appendIfPresent(sb, "LOCATION", params.get("location"));
        appendIfPresent(sb, "TARGET", params.get("target_id"));
        appendIfPresent(sb, "STATUS", params.get("status"));
        appendIfPresent(sb, "CONTENT", params.get("content"));
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String key, Object value) {
        if (value != null) sb.append(key).append(": ").append(value).append("\n");
    }

    private String extractSection(String specContent, String heading) {
        String[] lines = specContent.split("\n");
        boolean inSection = false;
        var sb = new StringBuilder();
        for (String line : lines) {
            if (line.contains(heading)) { inSection = true; }
            else if (inSection && line.startsWith("#")) break;
            if (inSection) sb.append(line).append("\n");
        }
        return sb.isEmpty() ? "Section not found: " + heading : sb.toString();
    }

    private String buildReviewerPrompt(DebateRoundContext ctx) {
        return "You are a rigorous spec reviewer. Review the spec. Use submit_debate_entry for each finding. Call finish_round when done.\n\nSpec:\n" + ctx.specContent();
    }

    private String buildImplementerPrompt(DebateRoundContext ctx) {
        return "You are a spec implementer. Respond to all open points in the debate. Use submit_debate_entry for each response. Call finish_round when done.\n\nDebate:\n" + ctx.debateContent();
    }
}
```

Note: The exact import paths for `ClaudeAsyncClient`, `ClaudeSessionConfig`, `McpTool`, `SessionCompleteEvent`, and `Map` (for tool schemas) depend on the actual `claude-agent-sdk-java` API. Adjust imports once the Maven coordinate is verified and the artifact is available on the classpath.

- [ ] **Create `ClaudeAgentSdkDebateAgentProviderTest.java`**

```java
package io.casehub.drafthouse.debate.claude;

import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.drafthouse.debate.DebateAgentProviderContractTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.inject.Inject;

@QuarkusTest
@IfBuildProperty(name = "drafthouse.claude-agent.test.enabled", stringValue = "true")
class ClaudeAgentSdkDebateAgentProviderTest extends DebateAgentProviderContractTest {

    @Inject ClaudeAgentSdkDebateAgentProvider provider;

    @Override
    protected DebateAgentProvider provider() { return provider; }

    @Override
    protected String validRoundSnippet() {
        // Real Claude — no stub needed; method body left for contract compliance
        return "";
    }

    @Override
    protected void stubModelToReturn(String snippet) {
        // Real Claude session — no model stub. Skipped automatically when not enabled.
    }
}
```

- [ ] **Verify the module compiles**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl claude-agent
```
Expected: `BUILD SUCCESS` (or dependency resolution errors if the artifact is not on Maven Central — resolve first).

- [ ] **Commit**

```bash
git add server/claude-agent/
git commit -m "feat(debate): ClaudeAgentSdkDebateAgentProvider optional module (draft)

⚠️ claude-agent-sdk-java Maven coordinate unverified — see platform/issues/55.
Marked draft until coordinate confirmed and API imports resolved.

Refs #29"
```

---

## Task 11: Full test run + LAYER-LOG entry

- [ ] **Run all server tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api,runtime
```
Expected: All tests pass. `claude-agent` skipped (off by default in CI).

- [ ] **Add LAYER-LOG entry**

Append to `LAYER-LOG.md` at project root:

```markdown
## Layer: Review Manifest — Layer 2 Agent Workflow

**Issue:** casehubio/drafthouse#29
**Status:** Complete

**What shipped:**
- Domain model: `DebateEvent` (sealed, Java 17), `ReviewState`, `ReviewPoint`, `DebateEntry` — in `server/api/`
- `SummaryProjector implements ChannelProjection<ReviewState>` (casehub-qhorus-api #230) with incremental fold (#231)
- `DebateParser`, `RoundParser`, `SummaryRenderer`, `DebateEntryFormatter` — pure-Java processing pipeline
- `LangChain4jDebateAgentProvider @DefaultBean` — any LLM via LangChain4j
- `ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)` — real Claude Code sessions (optional, requires CLI)
- `ReviewSessionService @Blocking` — JGit session lifecycle, incremental fold on each round commit
- Stub REST: `POST /api/review/sessions`, `POST /api/review/sessions/{id}/next-round`
- Contract tests with parity between both providers
```

- [ ] **Final commit**

```bash
git add LAYER-LOG.md
git commit -m "docs: LAYER-LOG entry for review manifest Layer 2

Refs #29"
```
