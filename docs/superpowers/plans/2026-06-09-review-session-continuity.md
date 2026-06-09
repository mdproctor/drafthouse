# Review Session Continuity — Sub-Agent Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement reasoning memos, sub-agent spawning (VERIFY/ARBITRATE/DEEP_ANALYSIS/CONSISTENCY_CHECK/NEUTRAL_SUMMARY/CUSTOM), and provenance labelling in the DraftHouse debate channel.

**Architecture:** `request_subagent` MCP tool posts a `SUB_TASK_REQUEST` entry to the debate Qhorus channel; `DebateChannelBackend.post()` fires a `SubAgentRequest` CDI event; `SubAgentOrchestrator @ObservesAsync` assembles minimal focused context, calls `SubAgentProvider.analyse()`, and posts a `SUB_TASK_FINDING` or `SUB_TASK_ERROR` back to the channel. `DebateChannelProjection` folds all new entry types into `ReviewState`; `SummaryRenderer` renders them with provenance markers.

**Tech Stack:** Java 21, Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT (ChannelBackend SPI, MessageService, ProjectionService), LangChain4j 1.9.1 (ChatModel), CDI `@ObservesAsync`, JUnit 5, AssertJ.

---

## File Map

**Create (api module):**
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskStatus.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskFinding.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentProvider.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentTask.java`

**Modify (api module):**
- `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java` — add MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java` — add memos + subTaskFindings fields
- `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java` — exhaustiveness fix + render memos + render sub-task findings
- `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java` — add specPath field

**Create (runtime module):**
- `server/runtime/src/main/java/io/casehub/drafthouse/SubAgentRequest.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/SubAgentOrchestrator.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jSubAgentProvider.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/SubAgentOrchestratorTest.java`

**Modify (runtime module):**
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java` — fire CDI event on SUB_TASK_REQUEST
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` — add post_memo + request_subagent
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` — new entry types + updated identity()
- `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java` — new test cases
- `server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java` — sub-task dispatch test
- `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java` — new tool tests

---

## Task 1: New domain types in `api` module

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskStatus.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskFinding.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentProvider.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentTask.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`

- [ ] **Step 1: Create `SubTaskType`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java
package io.casehub.drafthouse.debate;

public enum SubTaskType {
    VERIFY,             // check a claim against the spec
    ARBITRATE,          // neutral read on a disputed point — both arguments, no prior history
    DEEP_ANALYSIS,      // close reading of a spec section before raising points
    CONSISTENCY_CHECK,  // does a proposed resolution contradict prior agreements?
    NEUTRAL_SUMMARY,    // compact neutral summary of the current round
    CUSTOM              // caller provides explicit context string
}
```

- [ ] **Step 2: Create `SubTaskStatus`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskStatus.java
package io.casehub.drafthouse.debate;

public enum SubTaskStatus { PENDING, COMPLETE, ERROR }
```

- [ ] **Step 3: Create `SubTaskFinding`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskFinding.java
package io.casehub.drafthouse.debate;

public record SubTaskFinding(
        String subTaskId,
        SubTaskType taskType,
        String requestingAgent,  // "REV" or "IMP" — provenance: who asked
        String pointId,          // null for NEUTRAL_SUMMARY and CUSTOM
        String finding,          // null while PENDING or on ERROR
        String errorReason,      // null unless ERROR
        SubTaskStatus status
) {}
```

- [ ] **Step 4: Create `RoundMemo`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java
package io.casehub.drafthouse.debate;

public record RoundMemo(String agentRole, int round, String content) {}
```

- [ ] **Step 5: Create `SubAgentTask`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentTask.java
package io.casehub.drafthouse.debate;

public record SubAgentTask(
        SubTaskType taskType,
        String systemPrompt,    // sub-agent persona — enforces fresh-context invariant
        String assembledInput   // minimally scoped context, assembled by SubAgentOrchestrator
) {}
```

- [ ] **Step 6: Create `SubAgentProvider` SPI**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubAgentProvider.java
package io.casehub.drafthouse.debate;

public interface SubAgentProvider {
    /**
     * Invoke an LLM sub-agent with the given task and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread (CDI async observer thread is correct).
     */
    String analyse(SubAgentTask task);
}
```

- [ ] **Step 7: Add four new values to `EntryType`**

Replace the file content with:

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java
package io.casehub.drafthouse.debate;

public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED,
    MEMO,              // per-round reasoning memo from a main agent
    SUB_TASK_REQUEST,  // request for focused sub-agent analysis
    SUB_TASK_FINDING,  // sub-agent result (provenance: fresh context)
    SUB_TASK_ERROR     // sub-agent execution failure
}
```

- [ ] **Step 8: Fix `SummaryRenderer` exhaustiveness — add cases for new entry types**

The switch in `SummaryRenderer.render()` on `entry.type()` is a switch expression — it must be exhaustive. The new entry types will never appear in `ThreadEntry` objects (they're in separate collections), but the compiler doesn't know that. Add them:

In `SummaryRenderer.java`, replace the `typeLabel` switch:

```java
String typeLabel = switch (entry.type()) {
    case RAISE      -> "raise";
    case AGREE      -> "agree";
    case COUNTER    -> "counter";
    case DISPUTE    -> "dispute";
    case QUALIFY    -> "qualify";
    case FLAG_HUMAN -> "flag";
    case DECLINED   -> "declined";
    case MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR -> "";
};
```

- [ ] **Step 9: Build and confirm `api` module compiles**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl api
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add SubTaskType, SubTaskStatus, SubTaskFinding, RoundMemo, SubAgentProvider SPI, extend EntryType

Refs #26"
```

---

## Task 2: Extend `DebateSession` with `specPath`

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` (startDebate only)
- Test: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Write the failing test**

In `DebateMcpToolsTest.java`, add:

```java
@Test
void startDebate_storesSpecPath_inRegistry() {
    // Given a valid spec path
    String specPath = "/tmp/test-spec.md";
    // (this test relies on the registry and mock services already set up in the test class)
    // After startDebate, registry should contain session with specPath
    // NOTE: look at the existing startDebate test pattern in this file and follow it
}
```

**Important:** First read the existing `DebateMcpToolsTest.java` to understand the mock setup before writing this test, then add it following the existing pattern.

- [ ] **Step 2: Run the test — it should fail because `DebateSession` has no `specPath`**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest
```

Expected: compilation error or test failure referencing missing `specPath`.

- [ ] **Step 3: Add `specPath` to `DebateSession`**

```java
// server/api/src/main/java/io/casehub/drafthouse/DebateSession.java
package io.casehub.drafthouse;

import java.util.UUID;

public record DebateSession(
        UUID channelId,
        String debateSessionId,
        String channelName,
        String revInstanceId,
        String impInstanceId,
        String specPath          // absolute path to the spec being debated; may be null if not provided
) {}
```

- [ ] **Step 4: Update `DebateMcpTools.startDebate()` to pass `specPath`**

In `DebateMcpTools.java`, find the `DebateSession` constructor call in `startDebate()` and add `specPath`:

```java
DebateSession session = new DebateSession(
        channel.id, debateSessionId, resolvedName,
        revInstanceId, impInstanceId, specPath);
```

- [ ] **Step 5: Run the test — should pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest
```

Expected: all `DebateMcpToolsTest` tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/DebateSession.java server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add specPath to DebateSession — required for VERIFY/DEEP_ANALYSIS input assembly

Refs #26"
```

---

## Task 3: Extend `ReviewState` with `memos` and `subTaskFindings`

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` — update all `new ReviewState(...)` construction sites and `identity()`
- Test: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`

- [ ] **Step 1: Write a failing test for new `ReviewState` fields**

Add to `DebateChannelProjectionTest.java`:

```java
@Test
void identity_hasEmptyMemosAndSubTaskFindings() {
    ReviewState s = new DebateChannelProjection().identity();
    assertThat(s.memos()).isEmpty();
    assertThat(s.subTaskFindings()).isEmpty();
}

@Test
void apply_preservesMemosAndSubTaskFindingsOnUnrelatedMessage() {
    DebateChannelProjection proj = new DebateChannelProjection();
    ReviewState base = proj.identity();
    // apply a raise message — memos and subTaskFindings should remain empty
    ReviewState after = proj.apply(base,
            msg(MessageType.QUERY, "pt-1",
                    ratefacts("raise", "REV", 1, "P1", "ISOLATED"),
                    "Some issue."));
    assertThat(after.memos()).isEmpty();
    assertThat(after.subTaskFindings()).isEmpty();
}
```

- [ ] **Step 2: Run — expect compilation failure (no `memos()` or `subTaskFindings()` on `ReviewState`)**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime
```

Expected: compilation error.

- [ ] **Step 3: Replace `ReviewState` with the extended record**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java
package io.casehub.drafthouse.debate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReviewState(
        Map<String, ReviewPoint> points,
        List<FlagEntry> humanFlags,
        List<RoundMemo> memos,
        Map<String, SubTaskFinding> subTaskFindings
) {
    public ReviewState {
        points           = Collections.unmodifiableMap(new LinkedHashMap<>(points));
        humanFlags       = Collections.unmodifiableList(List.copyOf(humanFlags));
        memos            = Collections.unmodifiableList(List.copyOf(memos));
        subTaskFindings  = Collections.unmodifiableMap(new LinkedHashMap<>(subTaskFindings));
    }
}
```

- [ ] **Step 4: Update all `new ReviewState(...)` construction sites in `DebateChannelProjection`**

Update `identity()`:
```java
@Override
public ReviewState identity() {
    return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
}
```

Update `handleRaise()` — replace the final return:
```java
return new ReviewState(points, new ArrayList<>(state.humanFlags()),
        new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
```

Update `appendToPoint()` — replace the final return:
```java
return new ReviewState(points, new ArrayList<>(state.humanFlags()),
        new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
```

Update `handleFlagHuman()` — replace the final return:
```java
return new ReviewState(points, flags,
        new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
```

- [ ] **Step 5: Run all projection tests — should pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all existing tests pass plus the two new tests.

- [ ] **Step 6: Run the full reactor build to confirm no other break**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): extend ReviewState with memos and subTaskFindings fields

Refs #26"
```

---

## Task 4: `SubAgentRequest` event + `SubAgentOrchestrator`

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/SubAgentRequest.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/SubAgentOrchestrator.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/SubAgentOrchestratorTest.java`

- [ ] **Step 1: Create `SubAgentRequest` CDI event record**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/SubAgentRequest.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.SubTaskType;
import java.util.UUID;

public record SubAgentRequest(
        UUID channelId,
        String subTaskId,         // UUID string — correlationId of the SUB_TASK_REQUEST message
        SubTaskType taskType,
        String requestingAgent,   // "REV" or "IMP"
        String pointId,           // correlationId of the debate point; null for NEUTRAL_SUMMARY, CUSTOM
        String customInput,       // null unless taskType == CUSTOM
        String specPath           // absolute path to spec; null if not provided at session start
) {}
```

- [ ] **Step 2: Write the failing orchestrator tests**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/SubAgentOrchestratorTest.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubAgentOrchestratorTest {

    @Mock MessageService messageService;
    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock SubAgentProvider subAgentProvider;

    SubAgentOrchestrator orchestrator;

    UUID channelId = UUID.randomUUID();
    String revInstanceId = "drafthouse-rev-" + channelId;
    String impInstanceId = "drafthouse-imp-" + channelId;
    DebateSession session = new DebateSession(channelId, channelId.toString(),
            "drafthouse/debate/d-test", revInstanceId, impInstanceId, "/tmp/spec.md");

    @BeforeEach
    void setUp() {
        orchestrator = new SubAgentOrchestrator(
                subAgentProvider, messageService, projectionService, debateProjection, registry);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
    }

    @Test
    void arbitrate_assemblesOnlyRaiseAndMostRecentResponse_noExtraContext() {
        // Projection state: one point with raise + dispute thread
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The claim content."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.DISPUTE, "Counter argument.")
        );
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                thread, ReviewStatus.DISPUTED);
        ReviewState state = new ReviewState(
                Map.of("pt-1", point), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
        when(subAgentProvider.analyse(any())).thenReturn("Sub-agent finding text.");

        SubAgentRequest event = new SubAgentRequest(
                channelId, "sub-1", SubTaskType.ARBITRATE, "REV", "pt-1", null, null);
        orchestrator.onSubAgentRequest(event);

        ArgumentCaptor<SubAgentTask> taskCaptor = ArgumentCaptor.forClass(SubAgentTask.class);
        verify(subAgentProvider).analyse(taskCaptor.capture());
        String input = taskCaptor.getValue().assembledInput();
        assertThat(input).contains("The claim content.");
        assertThat(input).contains("Counter argument.");
        // no history beyond raise + most recent response
    }

    @Test
    void arbitrate_sessionNotFound_silentDrop() {
        when(registry.find(channelId)).thenReturn(Optional.empty());
        SubAgentRequest event = new SubAgentRequest(
                channelId, "sub-1", SubTaskType.ARBITRATE, "REV", "pt-1", null, null);
        orchestrator.onSubAgentRequest(event);
        verifyNoInteractions(subAgentProvider, messageService);
    }

    @Test
    void custom_usesCustomInputVerbatim() {
        ReviewState state = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
        when(subAgentProvider.analyse(any())).thenReturn("Custom finding.");

        SubAgentRequest event = new SubAgentRequest(
                channelId, "sub-2", SubTaskType.CUSTOM, "IMP", null, "Custom context here.", null);
        orchestrator.onSubAgentRequest(event);

        ArgumentCaptor<SubAgentTask> taskCaptor = ArgumentCaptor.forClass(SubAgentTask.class);
        verify(subAgentProvider).analyse(taskCaptor.capture());
        assertThat(taskCaptor.getValue().assembledInput()).isEqualTo("Custom context here.");
    }

    @Test
    void onSubAgentProviderFailure_dispatchesSubTaskError() {
        ReviewState state = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
        when(subAgentProvider.analyse(any())).thenThrow(new RuntimeException("LLM timeout"));
        // findByCorrelationId for inReplyTo lookup
        when(messageService.findByCorrelationId("sub-3")).thenReturn(Optional.empty());

        SubAgentRequest event = new SubAgentRequest(
                channelId, "sub-3", SubTaskType.CUSTOM, "REV", null, "Some input.", null);
        orchestrator.onSubAgentRequest(event);

        ArgumentCaptor<MessageDispatch> dispatchCaptor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(dispatchCaptor.capture());
        MessageDispatch dispatched = dispatchCaptor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.STATUS);
        assertThat(dispatched.content()).contains("SUB_TASK_ERROR");
        // Fixed sanitized body — never e.getMessage() (qhorus-dispatch-exception-sanitization.md)
        assertThat(dispatched.content()).contains("Sub-agent analysis failed.");
        assertThat(dispatched.content()).doesNotContain("LLM timeout");
    }

    @Test
    void consistencyCheck_includesOnlyAgreedPointsAndCustomInput() {
        // Two points: one AGREED, one OPEN
        var agreedThread = List.of(
                new ThreadEntry("pt-a", AgentType.REV, 1, EntryType.RAISE, "Agreed point."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.AGREE, "Agreed.")
        );
        var openThread = List.of(
                new ThreadEntry("pt-b", AgentType.REV, 1, EntryType.RAISE, "Open point.")
        );
        ReviewState state = new ReviewState(
                Map.of(
                    "pt-a", new ReviewPoint("pt-a",
                            new PointClassification(Priority.P1, Scope.ISOLATED, null),
                            agreedThread, ReviewStatus.AGREED),
                    "pt-b", new ReviewPoint("pt-b",
                            new PointClassification(Priority.P2, Scope.ISOLATED, null),
                            openThread, ReviewStatus.OPEN)
                ),
                List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
        when(subAgentProvider.analyse(any())).thenReturn("No contradiction.");

        SubAgentRequest event = new SubAgentRequest(
                channelId, "sub-4", SubTaskType.CONSISTENCY_CHECK, "IMP", null,
                "Proposed resolution text.", null);
        orchestrator.onSubAgentRequest(event);

        ArgumentCaptor<SubAgentTask> taskCaptor = ArgumentCaptor.forClass(SubAgentTask.class);
        verify(subAgentProvider).analyse(taskCaptor.capture());
        String input = taskCaptor.getValue().assembledInput();
        assertThat(input).contains("Agreed point.");   // agreed point included
        assertThat(input).doesNotContain("Open point."); // open point excluded
        assertThat(input).contains("Proposed resolution text."); // customInput included
    }
}
```

- [ ] **Step 3: Run — expect compilation failure (no `SubAgentOrchestrator`)**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime
```

- [ ] **Step 4: Create `SubAgentOrchestrator`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/SubAgentOrchestrator.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.*;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class SubAgentOrchestrator {

    static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

    private static final Logger LOG = Logger.getLogger(SubAgentOrchestrator.class.getName());

    private final SubAgentProvider subAgentProvider;
    private final MessageService messageService;
    private final ProjectionService projectionService;
    private final DebateChannelProjection debateProjection;
    private final DebateSessionRegistry registry;
    private final InstanceService instanceService;

    @Inject
    SubAgentOrchestrator(SubAgentProvider subAgentProvider,
                         MessageService messageService,
                         ProjectionService projectionService,
                         DebateChannelProjection debateProjection,
                         DebateSessionRegistry registry,
                         InstanceService instanceService) {
        this.subAgentProvider  = subAgentProvider;
        this.messageService    = messageService;
        this.projectionService = projectionService;
        this.debateProjection  = debateProjection;
        this.registry          = registry;
        this.instanceService   = instanceService;
    }

    @PostConstruct
    void registerSubAgentInstance() {
        // Qhorus validates sender instances at dispatch time — must be registered before any finding is dispatched.
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                List.of("document-debate-subagent"));
    }

    // Constructor for unit tests (no CDI — pass null for instanceService, @PostConstruct not called)
    SubAgentOrchestrator(SubAgentProvider subAgentProvider,
                         MessageService messageService,
                         ProjectionService projectionService,
                         DebateChannelProjection debateProjection,
                         DebateSessionRegistry registry,
                         boolean _testMode) {
        this(subAgentProvider, messageService, projectionService, debateProjection, registry, null);
    }

    public void onSubAgentRequest(@ObservesAsync SubAgentRequest event) {
        DebateSession session = registry.find(event.channelId()).orElse(null);
        if (session == null) {
            LOG.warning("SubAgentOrchestrator: no session for channel " + event.channelId() + " — dropped");
            return;
        }
        try {
            SubAgentTask task = assembleTask(event, session);
            String finding = subAgentProvider.analyse(task);
            dispatchFinding(event, finding);
        } catch (Exception e) {
            // Log full detail for debuggability — never pass e.getMessage() to the ledger
            LOG.warning("SubAgentOrchestrator: sub-agent failed [subTaskId=" + event.subTaskId()
                    + ", type=" + event.taskType() + "]: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
            dispatchError(event);
        }
    }

    // ── input assembly ────────────────────────────────────────────────────────

    private SubAgentTask assembleTask(SubAgentRequest event, DebateSession session) {
        ReviewState state = projectionService.project(event.channelId(), debateProjection).state();
        return switch (event.taskType()) {
            case VERIFY            -> assembleVerify(event, session, state);
            case ARBITRATE         -> assembleArbitrate(event, state);
            case DEEP_ANALYSIS     -> assembleDeepAnalysis(event, session, state);
            case CONSISTENCY_CHECK -> assembleConsistencyCheck(event, state);
            case NEUTRAL_SUMMARY   -> assembleNeutralSummary(event, state);
            case CUSTOM            -> assembleCustom(event);
        };
    }

    private SubAgentTask assembleVerify(SubAgentRequest event, DebateSession session, ReviewState state) {
        String claim = raiseContent(state, event.pointId());
        String spec  = readSpecSafe(session.specPath());
        String input = "Claim to verify:\n" + claim + "\n\nSpec:\n" + spec;
        String system = "You are a spec verifier with no knowledge of this debate's prior rounds. "
                + "Determine only whether this claim is supported by the spec. Be precise.";
        return new SubAgentTask(SubTaskType.VERIFY, system, input);
    }

    private SubAgentTask assembleArbitrate(SubAgentRequest event, ReviewState state) {
        ReviewPoint point = state.points().get(event.pointId());
        String raiseContent  = point == null ? "(point not found)" : point.thread().get(0).content();
        List<ThreadEntry> thread = point == null ? List.of() : point.thread();
        String latestResponse = thread.size() > 1 ? thread.get(thread.size() - 1).content() : "(no response yet)";
        String input = "Original claim:\n" + raiseContent + "\n\nMost recent response:\n" + latestResponse;
        String system = "You are a neutral arbitrator. You have not seen this debate before. "
                + "Assess these two positions on their merits only. Do not favour either side.";
        return new SubAgentTask(SubTaskType.ARBITRATE, system, input);
    }

    private SubAgentTask assembleDeepAnalysis(SubAgentRequest event, DebateSession session, ReviewState state) {
        ReviewPoint point = event.pointId() != null ? state.points().get(event.pointId()) : null;
        String focusHint = (point != null && point.classification().location() != null)
                ? point.classification().location() : "(no specific section indicated)";
        String spec  = readSpecSafe(session.specPath());
        String input = "Focus section: " + focusHint + "\n\nFull spec:\n" + spec;
        String system = "You are a spec analyst reading this spec with fresh eyes. "
                + "Focus on the indicated section. Identify issues.";
        return new SubAgentTask(SubTaskType.DEEP_ANALYSIS, system, input);
    }

    private SubAgentTask assembleConsistencyCheck(SubAgentRequest event, ReviewState state) {
        String agreedPoints = state.points().values().stream()
                .filter(p -> p.currentStatus() == ReviewStatus.AGREED)
                .map(p -> "- [" + p.id() + "] " + p.thread().get(0).content())
                .collect(Collectors.joining("\n"));
        if (agreedPoints.isBlank()) agreedPoints = "(no agreed points yet)";
        String input = "Prior agreed points:\n" + agreedPoints
                + "\n\nProposed resolution:\n" + Objects.requireNonNullElse(event.customInput(), "");
        String system = "You have no memory of this debate. Determine only whether the proposed "
                + "resolution contradicts any of these prior agreements.";
        return new SubAgentTask(SubTaskType.CONSISTENCY_CHECK, system, input);
    }

    private SubAgentTask assembleNeutralSummary(SubAgentRequest event, ReviewState state) {
        // round is encoded in the SUB_TASK_REQUEST META header but not in SubAgentRequest directly.
        // We summarise all current-state points as a proxy for the round's entries.
        String entries = state.points().values().stream()
                .map(p -> "[" + p.id() + "] " + p.thread().stream()
                        .map(e -> e.agent() + "/" + e.type() + ": " + e.content())
                        .collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
        if (entries.isBlank()) entries = "(no debate entries)";
        String input = "Debate entries to summarise:\n" + entries;
        String system = "Summarise this debate neutrally. You have not participated in it.";
        return new SubAgentTask(SubTaskType.NEUTRAL_SUMMARY, system, input);
    }

    private SubAgentTask assembleCustom(SubAgentRequest event) {
        String system = "You are a focused analyst. Answer only the question posed. "
                + "You have no knowledge of the broader debate.";
        return new SubAgentTask(SubTaskType.CUSTOM, system,
                Objects.requireNonNullElse(event.customInput(), ""));
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    private void dispatchFinding(SubAgentRequest event, String finding) {
        Long inReplyTo = messageService.findByCorrelationId(event.subTaskId())
                .map(m -> m.id).orElse(null);
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_FINDING|subTaskId=" + event.subTaskId()
                + "|taskType=" + event.taskType()
                + "|agent=" + event.requestingAgent()
                + (event.pointId() != null ? "|pointId=" + event.pointId() : "")
                + "\n\n" + finding;
        messageService.dispatch(MessageDispatch.builder()
                .channelId(event.channelId())
                .sender(SUBAGENT_INSTANCE_ID)
                .type(MessageType.RESPONSE)
                .content(encoded)
                .correlationId(event.subTaskId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());
    }

    private void dispatchError(SubAgentRequest event) {
        // NEVER pass exception messages here — the Qhorus ledger is immutable and tamper-evident.
        // Exception messages may contain stack traces, API keys, or internal paths.
        // Log full detail above (in the caller); dispatch a fixed sanitised category string only.
        // See: qhorus-dispatch-exception-sanitization.md
        Long inReplyTo = messageService.findByCorrelationId(event.subTaskId())
                .map(m -> m.id).orElse(null);
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_ERROR|subTaskId=" + event.subTaskId()
                + "|taskType=" + event.taskType()
                + "|agent=" + event.requestingAgent()
                + "\n\nSub-agent analysis failed.";
        messageService.dispatch(MessageDispatch.builder()
                .channelId(event.channelId())
                .sender(SUBAGENT_INSTANCE_ID)
                .type(MessageType.STATUS)
                .content(encoded)
                .correlationId(event.subTaskId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String raiseContent(ReviewState state, String pointId) {
        if (pointId == null) return "(no point specified)";
        ReviewPoint p = state.points().get(pointId);
        if (p == null || p.thread().isEmpty()) return "(point not found: " + pointId + ")";
        return p.thread().get(0).content();
    }

    private String readSpecSafe(String specPath) {
        if (specPath == null || specPath.isBlank()) return "(spec path not provided)";
        try {
            return Files.readString(Path.of(specPath));
        } catch (IOException e) {
            LOG.warning("Could not read spec at " + specPath + ": " + e.getMessage());
            return "(could not read spec: " + e.getMessage() + ")";
        }
    }
}
```

**Note on test constructor:** The test uses a package-private boolean-sentinel constructor that avoids CDI and passes `null` for `instanceService` (the `@PostConstruct` is not called in tests — no Qhorus instance registration needed). In the test `setUp()`, call it as:
```java
orchestrator = new SubAgentOrchestrator(
        subAgentProvider, messageService, projectionService, debateProjection, registry, true);
```

Update `SubAgentOrchestratorTest.setUp()` to use this constructor.

- [ ] **Step 5: Run orchestrator tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SubAgentOrchestratorTest
```

Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/SubAgentRequest.java server/runtime/src/main/java/io/casehub/drafthouse/SubAgentOrchestrator.java server/runtime/src/test/java/io/casehub/drafthouse/SubAgentOrchestratorTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add SubAgentRequest CDI event + SubAgentOrchestrator with input assembly

Refs #26"
```

---

## Task 5: `LangChain4jSubAgentProvider` — default implementation

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jSubAgentProvider.java`

- [ ] **Step 1: Write a failing test**

Add to `SubAgentOrchestratorTest.java` (or a new `LangChain4jSubAgentProviderTest.java`):

```java
// server/runtime/src/test/java/io/casehub/drafthouse/LangChain4jSubAgentProviderTest.java
package io.casehub.drafthouse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.casehub.drafthouse.debate.SubAgentTask;
import io.casehub.drafthouse.debate.SubTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LangChain4jSubAgentProviderTest {

    @Mock ChatLanguageModel chatModel;

    @Test
    void analyse_callsModelWithSystemAndUserMessages_returnsText() {
        when(chatModel.generate(anyList()))
                .thenReturn(new Response<>(AiMessage.from("The finding text.")));

        var provider = new LangChain4jSubAgentProvider(chatModel);
        var task = new SubAgentTask(SubTaskType.VERIFY, "System prompt.", "User input.");

        String result = provider.analyse(task);

        assertThat(result).isEqualTo("The finding text.");
        verify(chatModel).generate(anyList());
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime
```

- [ ] **Step 3: Create `LangChain4jSubAgentProvider`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jSubAgentProvider.java
package io.casehub.drafthouse;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.casehub.drafthouse.debate.SubAgentProvider;
import io.casehub.drafthouse.debate.SubAgentTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.DefaultBean;
import jakarta.inject.Inject;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class LangChain4jSubAgentProvider implements SubAgentProvider {

    private final ChatLanguageModel chatModel;

    @Inject
    LangChain4jSubAgentProvider(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    // Test constructor
    LangChain4jSubAgentProvider(ChatLanguageModel chatModel, boolean _testMode) {
        this(chatModel);
    }

    @Override
    public String analyse(SubAgentTask task) {
        var response = chatModel.generate(List.of(
                SystemMessage.from(task.systemPrompt()),
                UserMessage.from(task.assembledInput())
        ));
        return response.content().text();
    }
}
```

Update the test to use:
```java
var provider = new LangChain4jSubAgentProvider(chatModel, true);
```

- [ ] **Step 4: Run — should pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=LangChain4jSubAgentProviderTest
```

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jSubAgentProvider.java server/runtime/src/test/java/io/casehub/drafthouse/LangChain4jSubAgentProviderTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add LangChain4jSubAgentProvider @DefaultBean — CI-friendly sub-agent executor

Refs #26"
```

---

## Task 6: `DebateChannelBackend` — fire CDI event on `SUB_TASK_REQUEST`

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java`
- Test: `server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java`

- [ ] **Step 1: Write a failing test**

Read `DebateChannelBackendFactoryTest.java` first to understand the existing pattern, then add:

```java
@Test
void post_subTaskRequest_firesSubAgentRequestEvent() {
    // Build an OutboundMessage whose content has SUB_TASK_REQUEST in the META header
    String content = DebateProtocol.META_SENTINEL
            + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1"
            + "\n\n";
    // (mock OutboundMessage and ChannelRef, verify Event.fireAsync called)
    // Follow the existing test pattern in DebateChannelBackendFactoryTest
}
```

**Important:** Read the existing test file first to understand how to mock `OutboundMessage` and `ChannelRef` before writing this test.

- [ ] **Step 2: Run — should fail**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelBackendFactoryTest
```

- [ ] **Step 3: Update `DebateChannelBackend`**

Replace the entire file:

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.SubTaskType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ChannelBackend for debate channels.
 *
 * Thin adapter: observes SUB_TASK_REQUEST messages and fires a SubAgentRequest CDI event.
 * All sub-agent orchestration logic lives in SubAgentOrchestrator — not here.
 * All other message types are ignored (debate is peer-to-peer via DebateMcpTools).
 */
@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {

    static final String BACKEND_ID   = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(DebateChannelBackend.class.getName());

    @Inject Event<SubAgentRequest> subAgentEvent;
    @Inject DebateSessionRegistry registry;

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        Map<String, String> meta = parseMeta(message.content());
        if (!"SUB_TASK_REQUEST".equals(meta.get("entryType"))) return;

        DebateSession session = registry.find(channel.id()).orElse(null);
        if (session == null) {
            LOG.warning("DebateChannelBackend: SUB_TASK_REQUEST on channel " + channel.id()
                    + " but no session found — dropped");
            return;
        }

        SubTaskType taskType;
        try {
            taskType = SubTaskType.valueOf(meta.getOrDefault("taskType", "CUSTOM"));
        } catch (IllegalArgumentException e) {
            LOG.warning("DebateChannelBackend: unknown taskType '" + meta.get("taskType") + "' — defaulting to CUSTOM");
            taskType = SubTaskType.CUSTOM;
        }

        String subTaskId      = message.correlationId() != null ? message.correlationId().toString() : UUID.randomUUID().toString();
        String requestingAgent = meta.getOrDefault("agent", "REV");
        String pointId        = meta.get("pointId");
        String customInput    = bodyContent(message.content());

        subAgentEvent.fireAsync(new SubAgentRequest(
                channel.id(), subTaskId, taskType, requestingAgent,
                pointId, customInput.isBlank() ? null : customInput,
                session.specPath()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> parseMeta(String content) {
        Map<String, String> map = new HashMap<>();
        if (content == null || !content.startsWith(DebateProtocol.META_SENTINEL)) return map;
        int headerEnd = content.indexOf("\n\n");
        String headerLine = headerEnd > 0
                ? content.substring(DebateProtocol.META_SENTINEL.length(), headerEnd)
                : content.substring(DebateProtocol.META_SENTINEL.length());
        for (String part : headerLine.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        return map;
    }

    private String bodyContent(String content) {
        if (content == null || !content.startsWith(DebateProtocol.META_SENTINEL)) return "";
        int headerEnd = content.indexOf("\n\n");
        return headerEnd > 0 ? content.substring(headerEnd + 2) : "";
    }
}
```

- [ ] **Step 4: Run backend tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelBackendFactoryTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelBackend fires SubAgentRequest CDI event on SUB_TASK_REQUEST messages

Refs #26"
```

---

## Task 7: `DebateChannelProjection` — new entry type dispatch

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- Test: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java`

- [ ] **Step 1: Write failing tests for new entry types**

Add to `DebateChannelProjectionTest.java`:

```java
// ── memo ─────────────────────────────────────────────────────────────────────

@Test
void memo_addsToMemosList_doesNotAddPoint() {
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.STATUS, null, "entryType=MEMO|agent=REV|round=2", "My working notes."));
    assertThat(s.memos()).hasSize(1);
    assertThat(s.memos().get(0).agentRole()).isEqualTo("REV");
    assertThat(s.memos().get(0).round()).isEqualTo(2);
    assertThat(s.memos().get(0).content()).isEqualTo("My working notes.");
    assertThat(s.points()).isEmpty();
}

// ── sub-task request ──────────────────────────────────────────────────────────

@Test
void subTaskRequest_addsPendingFinding() {
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-1",
                    "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1",
                    ""));
    assertThat(s.subTaskFindings()).containsKey("sub-1");
    SubTaskFinding f = s.subTaskFindings().get("sub-1");
    assertThat(f.status()).isEqualTo(SubTaskStatus.PENDING);
    assertThat(f.taskType()).isEqualTo(SubTaskType.ARBITRATE);
    assertThat(f.requestingAgent()).isEqualTo("REV");
    assertThat(f.pointId()).isEqualTo("pt-1");
    assertThat(f.finding()).isNull();
}

// ── sub-task finding ──────────────────────────────────────────────────────────

@Test
void subTaskFinding_completesExistingPendingEntry() {
    ReviewState s0 = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-1",
                    "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1",
                    ""));
    ReviewState s1 = proj.apply(s0,
            msg(MessageType.RESPONSE, "sub-1",
                    "entryType=SUB_TASK_FINDING|subTaskId=sub-1|taskType=ARBITRATE|agent=REV",
                    "The finding text."));
    assertThat(s1.subTaskFindings().get("sub-1").status()).isEqualTo(SubTaskStatus.COMPLETE);
    assertThat(s1.subTaskFindings().get("sub-1").finding()).isEqualTo("The finding text.");
}

// ── sub-task error ────────────────────────────────────────────────────────────

@Test
void subTaskError_setsErrorStatus_withReason() {
    ReviewState s0 = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-2",
                    "entryType=SUB_TASK_REQUEST|agent=IMP|taskType=VERIFY|subTaskId=sub-2|pointId=pt-1",
                    ""));
    ReviewState s1 = proj.apply(s0,
            msg(MessageType.STATUS, "sub-2",
                    "entryType=SUB_TASK_ERROR|subTaskId=sub-2|taskType=VERIFY|agent=IMP",
                    "LLM timeout"));
    assertThat(s1.subTaskFindings().get("sub-2").status()).isEqualTo(SubTaskStatus.ERROR);
    assertThat(s1.subTaskFindings().get("sub-2").errorReason()).isEqualTo("LLM timeout");
}

@Test
void subTaskFinding_withoutPriorRequest_isStillAdded() {
    // Findings may arrive in any order; they are always accepted
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.RESPONSE, "sub-3",
                    "entryType=SUB_TASK_FINDING|subTaskId=sub-3|taskType=CUSTOM|agent=REV",
                    "Unexpected finding."));
    assertThat(s.subTaskFindings()).containsKey("sub-3");
    assertThat(s.subTaskFindings().get("sub-3").status()).isEqualTo(SubTaskStatus.COMPLETE);
}
```

- [ ] **Step 2: Run — expect compilation failure (no `SubTaskStatus`, etc. visible to test)**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime
```

- [ ] **Step 3: Update `DebateChannelProjection.apply()` switch**

In `DebateChannelProjection.java`, extend the `switch (entryType)` block with new cases and update `identity()`:

```java
@Override
public ReviewState identity() {
    return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
}

@Override
public ReviewState apply(ReviewState state, MessageView message) {
    Map<String, String> meta = parseMeta(message.content());
    String entryType = meta.get("entryType");
    if (entryType == null) return state;
    return switch (entryType) {
        case "raise"            -> handleRaise(state, message, meta);
        case "agree"            -> handleAgree(state, message, meta);
        case "dispute"          -> handleDispute(state, message, meta);
        case "qualify"          -> handleQualify(state, message, meta);
        case "counter"          -> handleCounter(state, message, meta);
        case "flag-human"       -> handleFlagHuman(state, message, meta);
        case "MEMO"             -> handleMemo(state, message, meta);
        case "SUB_TASK_REQUEST" -> handleSubTaskRequest(state, message, meta);
        case "SUB_TASK_FINDING" -> handleSubTaskFinding(state, message, meta);
        case "SUB_TASK_ERROR"   -> handleSubTaskError(state, message, meta);
        default                 -> state;
    };
}
```

Add these handlers to `DebateChannelProjection`:

```java
private ReviewState handleMemo(ReviewState state, MessageView message, Map<String, String> meta) {
    String agent   = meta.getOrDefault("agent", "UNKNOWN");
    int round      = parseRound(meta);
    String content = bodyContent(Objects.requireNonNullElse(message.content(), ""));
    var memos = new ArrayList<>(state.memos());
    memos.add(new RoundMemo(agent, round, content));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            memos, new LinkedHashMap<>(state.subTaskFindings()));
}

private ReviewState handleSubTaskRequest(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId       = meta.get("subTaskId");
    if (subTaskId == null) subTaskId = Objects.requireNonNullElse(message.correlationId(), "unknown");
    String taskTypeStr     = meta.getOrDefault("taskType", "CUSTOM");
    SubTaskType taskType;
    try { taskType = SubTaskType.valueOf(taskTypeStr); } catch (Exception e) { taskType = SubTaskType.CUSTOM; }
    String requestingAgent = meta.getOrDefault("agent", "UNKNOWN");
    String pointId         = meta.get("pointId");
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, requestingAgent, pointId, null, null, SubTaskStatus.PENDING));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}

private ReviewState handleSubTaskFinding(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId       = meta.get("subTaskId");
    if (subTaskId == null) return state;
    String taskTypeStr     = meta.getOrDefault("taskType", "CUSTOM");
    SubTaskType taskType;
    try { taskType = SubTaskType.valueOf(taskTypeStr); } catch (Exception e) { taskType = SubTaskType.CUSTOM; }
    String requestingAgent = meta.getOrDefault("agent", "UNKNOWN");
    String pointId         = meta.get("pointId");
    String finding         = bodyContent(Objects.requireNonNullElse(message.content(), ""));
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    SubTaskFinding existing = findings.get(subTaskId);
    String resolvedPointId = (existing != null && existing.pointId() != null) ? existing.pointId() : pointId;
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, requestingAgent,
            resolvedPointId, finding, null, SubTaskStatus.COMPLETE));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}

private ReviewState handleSubTaskError(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId       = meta.get("subTaskId");
    if (subTaskId == null) return state;
    String taskTypeStr     = meta.getOrDefault("taskType", "CUSTOM");
    SubTaskType taskType;
    try { taskType = SubTaskType.valueOf(taskTypeStr); } catch (Exception e) { taskType = SubTaskType.CUSTOM; }
    String requestingAgent = meta.getOrDefault("agent", "UNKNOWN");
    String reason          = bodyContent(Objects.requireNonNullElse(message.content(), "unknown error"));
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    SubTaskFinding existing = findings.get(subTaskId);
    String resolvedPointId = existing != null ? existing.pointId() : null;
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, requestingAgent,
            resolvedPointId, null, reason, SubTaskStatus.ERROR));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}
```

Also update `handleRaise()`, `appendToPoint()`, `handleFlagHuman()` to carry the new fields in `ReviewState` construction (replace all `new ReviewState(points, ...)` with the 4-arg version as in Task 3 Step 4).

- [ ] **Step 4: Run projection tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelProjection handles MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR

Refs #26"
```

---

## Task 8: `SummaryRenderer` — render memos and sub-task findings

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`
- Test: `server/api/src/test/java/io/casehub/drafthouse/debate/ReviewConversationRendererTest.java` (or a new `SummaryRendererTest.java`)

- [ ] **Step 1: Write failing render tests**

```java
// server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();

    private ReviewState emptyState() {
        return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    }

    @Test
    void memo_appearsInRenderWithRoundLabel() {
        var memos = List.of(new RoundMemo("REV", 2, "My working notes for round 2."));
        ReviewState state = new ReviewState(Map.of(), List.of(), memos, Map.of());
        String rendered = renderer.render(state);
        assertThat(rendered).contains("REV memo — Round 2");
        assertThat(rendered).contains("My working notes for round 2.");
    }

    @Test
    void subTaskFinding_pending_rendersWithPendingMarker() {
        var findings = Map.of("sub-1",
                new SubTaskFinding("sub-1", SubTaskType.ARBITRATE, "REV", "pt-1",
                        null, null, SubTaskStatus.PENDING));
        var thread = List.of(new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The point."));
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, null), thread, ReviewStatus.OPEN);
        ReviewState state = new ReviewState(Map.of("pt-1", point), List.of(), List.of(), findings);
        String rendered = renderer.render(state);
        assertThat(rendered).contains("⏳");
        assertThat(rendered).contains("ARBITRATE");
    }

    @Test
    void subTaskFinding_complete_rendersWithProvenanceMarker() {
        var findings = Map.of("sub-1",
                new SubTaskFinding("sub-1", SubTaskType.ARBITRATE, "REV", "pt-1",
                        "The arbitration finding.", null, SubTaskStatus.COMPLETE));
        var thread = List.of(new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The point."));
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, null), thread, ReviewStatus.OPEN);
        ReviewState state = new ReviewState(Map.of("pt-1", point), List.of(), List.of(), findings);
        String rendered = renderer.render(state);
        assertThat(rendered).contains("⊕");
        assertThat(rendered).contains("fresh context");
        assertThat(rendered).contains("The arbitration finding.");
    }

    @Test
    void subTaskFinding_error_rendersWithErrorMarker() {
        var findings = Map.of("sub-2",
                new SubTaskFinding("sub-2", SubTaskType.VERIFY, "IMP", null,
                        null, "LLM timeout", SubTaskStatus.ERROR));
        ReviewState state = new ReviewState(Map.of(), List.of(), List.of(), findings);
        String rendered = renderer.render(state);
        assertThat(rendered).contains("✗");
        assertThat(rendered).contains("VERIFY");
        assertThat(rendered).contains("LLM timeout");
    }

    @Test
    void subTaskFinding_noPointId_rendersInStandaloneSection() {
        var findings = Map.of("sub-3",
                new SubTaskFinding("sub-3", SubTaskType.NEUTRAL_SUMMARY, "REV", null,
                        "Round 2 summary text.", null, SubTaskStatus.COMPLETE));
        ReviewState state = new ReviewState(Map.of(), List.of(), List.of(), findings);
        String rendered = renderer.render(state);
        assertThat(rendered).contains("Sub-task findings");
        assertThat(rendered).contains("Round 2 summary text.");
    }
}
```

- [ ] **Step 2: Run — expect failures**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest
```

- [ ] **Step 3: Update `SummaryRenderer.render()` to include memos and sub-task findings**

After the existing points loop and human flags section, add:

```java
// After the humanFlags section in render():

// Memos section — grouped by round for readability
if (!state.memos().isEmpty()) {
    sb.append("\n---\n\n**Agent Memos**\n\n");
    for (RoundMemo memo : state.memos()) {
        sb.append("**").append(memo.agentRole()).append(" memo — Round ").append(memo.round())
          .append(":** ").append(memo.content()).append("\n\n");
    }
}

// Sub-task findings — those with a pointId are rendered inline with the point (see below),
// those without are rendered in a standalone section here.
List<SubTaskFinding> standaloneFindings = state.subTaskFindings().values().stream()
        .filter(f -> f.pointId() == null)
        .toList();
if (!standaloneFindings.isEmpty()) {
    sb.append("\n---\n\n**Sub-task findings**\n\n");
    for (SubTaskFinding f : standaloneFindings) {
        sb.append(renderSubTaskFinding(f)).append("\n");
    }
}
```

And in the point loop, after rendering thread entries, add inline sub-task findings for that point:

```java
// Inside the for (ReviewPoint point : ...) loop, after rendering thread entries:
state.subTaskFindings().values().stream()
        .filter(f -> point.id().equals(f.pointId()))
        .forEach(f -> sb.append(renderSubTaskFinding(f)).append("\n"));
```

Add the helper:

```java
private String renderSubTaskFinding(SubTaskFinding f) {
    return switch (f.status()) {
        case PENDING  -> "  ⏳ **" + f.taskType() + "** pending...\n";
        case ERROR    -> "  ✗ **" + f.taskType() + "** failed: " + f.errorReason() + "\n";
        case COMPLETE -> "  ⊕ **" + f.taskType() + "** _(fresh context — no prior round knowledge)_\n"
                       + "  " + f.finding() + "\n";
    };
}
```

- [ ] **Step 4: Run renderer tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=SummaryRendererTest
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java server/api/src/test/java/io/casehub/drafthouse/debate/SummaryRendererTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): SummaryRenderer renders memos and sub-task findings with provenance markers

Refs #26"
```

---

## Task 9: New MCP tools — `post_memo` and `request_subagent`

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Test: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Write failing tests**

Read `DebateMcpToolsTest.java` first to understand mock setup, then add:

```java
@Test
void postMemo_dispatchesMemoEntryToChannel() {
    // Given active session
    // When post_memo called with round=2 content
    // Then messageService.dispatch called with MessageType.STATUS and MEMO in content
    // Follow existing test mock patterns
}

@Test
void requestSubagent_dispatchesSubTaskRequest_returnsSubTaskId() {
    // Given active session
    // When request_subagent called with taskType=ARBITRATE, pointId=pt-1
    // Then messageService.dispatch called with QUERY type and SUB_TASK_REQUEST in content
    // And return JSON contains "subTaskId"
}

@Test
void requestSubagent_invalidSessionId_returnsError() {
    String result = tools.requestSubagent("not-a-uuid", "REV", "ARBITRATE", null, null);
    assertThat(result).startsWith("error:");
}

@Test
void requestSubagent_sessionNotFound_returnsError() {
    when(registry.find(any())).thenReturn(Optional.empty());
    String result = tools.requestSubagent(UUID.randomUUID().toString(), "REV", "ARBITRATE", null, null);
    assertThat(result).startsWith("error:");
}
```

- [ ] **Step 2: Run — expect compilation failure**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime
```

- [ ] **Step 3: Add `post_memo` and `request_subagent` to `DebateMcpTools`**

Add after the existing tools in `DebateMcpTools.java`:

```java
@Tool(name = "post_memo",
      description = "Write a per-round reasoning memo to the debate channel. Call after your last "
              + "raise/respond of a round to record your current working model of the spec, patterns "
              + "noticed, and why concessions feel solid vs provisional. Memos help a cold agent resume "
              + "this debate without losing context.")
public String postMemo(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
        @ToolArg(description = "Current round number") int round,
        @ToolArg(description = "Your reasoning memo content") String content) {
    try {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);
        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=MEMO|agent=" + agentRole + "|round=" + round
                + "\n\n" + Objects.requireNonNullElse(content, "");
        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(MessageType.STATUS)
                .content(encoded)
                .actorType(ActorType.AGENT)
                .build());
        return "{\"status\":\"dispatched\"}";
    } catch (Exception e) {
        LOG.warning("post_memo failed: " + e.getMessage());
        return "error: " + e.getMessage();
    }
}

@Tool(name = "request_subagent",
      description = "Dispatch a fresh-context sub-agent for focused analysis. The sub-agent has no "
              + "knowledge of prior debate rounds — only the minimal context assembled for its task type. "
              + "The finding appears in get_debate_summary when complete (⏳ while pending). "
              + "You may continue raising/responding while it runs. "
              + "taskType: VERIFY | ARBITRATE | DEEP_ANALYSIS | CONSISTENCY_CHECK | NEUTRAL_SUMMARY | CUSTOM")
public String requestSubagent(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
        @ToolArg(description = "Sub-task type: VERIFY, ARBITRATE, DEEP_ANALYSIS, CONSISTENCY_CHECK, NEUTRAL_SUMMARY, or CUSTOM") String taskType,
        @ToolArg(description = "pointId from raise_point this task relates to. Null for NEUTRAL_SUMMARY or CUSTOM.") String pointId,
        @ToolArg(description = "For CUSTOM only: the exact context the sub-agent receives. Null for all other task types.") String customInput) {
    try {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);
        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }
        String subTaskId = UUID.randomUUID().toString();
        StringBuilder metaHeader = new StringBuilder(DebateProtocol.META_SENTINEL)
                .append("entryType=SUB_TASK_REQUEST")
                .append("|agent=").append(agentRole)
                .append("|taskType=").append(Objects.requireNonNullElse(taskType, "CUSTOM"))
                .append("|subTaskId=").append(subTaskId);
        if (pointId != null && !pointId.isBlank()) {
            metaHeader.append("|pointId=").append(pointId);
        }
        String encoded = metaHeader + "\n\n" + Objects.requireNonNullElse(customInput, "");
        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(MessageType.QUERY)
                .content(encoded)
                .correlationId(subTaskId)
                .actorType(ActorType.AGENT)
                .build());
        return "{\"subTaskId\":\"" + subTaskId + "\",\"status\":\"dispatched\"}";
    } catch (Exception e) {
        LOG.warning("request_subagent failed: " + e.getMessage());
        return "error: " + e.getMessage();
    }
}
```

Add `import java.util.Objects;` if not present.

- [ ] **Step 4: Run all `DebateMcpToolsTest` tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest
```

Expected: all tests pass.

- [ ] **Step 5: Full reactor build**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add post_memo and request_subagent MCP tools

Refs #26"
```

---

## Task 10: E2E — `SubAgentE2ETest`

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/SubAgentE2ETest.java`

- [ ] **Step 1: Create a mock `SubAgentProvider` for tests**

Create `server/runtime/src/test/java/io/casehub/drafthouse/MockSubAgentProvider.java`:

```java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.SubAgentProvider;
import io.casehub.drafthouse.debate.SubAgentTask;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockSubAgentProvider implements SubAgentProvider {
    @Override
    public String analyse(SubAgentTask task) {
        return "Mock finding for " + task.taskType() + ": analysis complete.";
    }
}
```

- [ ] **Step 2: Write the E2E test**

Read an existing E2E test (e.g. `server/runtime/src/test/java/io/casehub/drafthouse/e2e/`) to understand the Playwright + `@QuarkusTest` pattern used in this project. Then write:

```java
// server/runtime/src/test/java/io/casehub/drafthouse/e2e/SubAgentE2ETest.java
// Follow the exact pattern of existing E2E tests in this directory.
// The test should:
// 1. Call start_debate via MCP to get a debateSessionId
// 2. Call raise_point to create a debate point (save the pointId)
// 3. Call request_subagent(ARBITRATE, pointId) — verify return contains "subTaskId"
// 4. Call get_debate_summary — verify it contains "⏳" or "ARBITRATE" (pending)
// 5. Wait briefly for async dispatch to complete
// 6. Call get_debate_summary again — verify it contains "⊕" and "Mock finding for ARBITRATE"
// 7. Call post_memo — verify return is {"status":"dispatched"}
// 8. Call get_debate_summary — verify memo appears with "memo — Round"
// 9. Call end_debate
```

**Important:** The E2E pattern for DraftHouse uses Playwright to call the MCP endpoints. Read `server/runtime/src/test/java/io/casehub/drafthouse/e2e/` for the exact HTTP/MCP call pattern before writing this test. Do not guess the API call structure.

- [ ] **Step 3: Run E2E tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SubAgentE2ETest
```

Expected: test passes.

- [ ] **Step 4: Run the full test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: all tests pass (or previously-skipped tests remain skipped).

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/test/java/io/casehub/drafthouse/e2e/SubAgentE2ETest.java server/runtime/src/test/java/io/casehub/drafthouse/MockSubAgentProvider.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(debate): add SubAgentE2ETest — full sub-agent lifecycle with mock provider

Refs #26"
```

---

## Task 11: `ClaudeSubAgentProvider` stub in `claude-agent` module

**Files:**
- Create: `server/claude-agent/src/main/java/io/casehub/drafthouse/ClaudeSubAgentProvider.java`

- [ ] **Step 1: Create the stub**

```java
// server/claude-agent/src/main/java/io/casehub/drafthouse/ClaudeSubAgentProvider.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.SubAgentProvider;
import io.casehub.drafthouse.debate.SubAgentTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Claude CLI-backed SubAgentProvider.
 *
 * Activates by classpath presence of this module, displacing LangChain4jSubAgentProvider @DefaultBean.
 * Implementation deferred pending casehubio/platform#55 (ClaudeAgentProvider SPI availability).
 *
 * CDI priority: @ApplicationScoped (no @DefaultBean) displaces @DefaultBean by CDI rules.
 * See docs/protocols/casehub/ai-agent-provider-cdi-priority.md.
 */
@ApplicationScoped
public class ClaudeSubAgentProvider implements SubAgentProvider {

    // TODO platform#55: inject AgentProvider (casehub-platform-agent-api) once shipped.
    // ClaudeAgentProvider @ApplicationScoped in agent-claude/ module will be available.

    @Override
    public String analyse(SubAgentTask task) {
        throw new UnsupportedOperationException(
                "ClaudeSubAgentProvider is a stub pending casehubio/platform#55. "
                + "Use LangChain4jSubAgentProvider (the @DefaultBean) for now.");
    }
}
```

- [ ] **Step 2: Build the claude-agent module**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests -pl claude-agent
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/claude-agent/src/main/java/io/casehub/drafthouse/ClaudeSubAgentProvider.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(claude-agent): add ClaudeSubAgentProvider stub — pending platform#55

Refs #26"
```

---

## Final verification

- [ ] **Full reactor build + all tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Push project branch**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse push -u origin issue-26-review-session-continuity
```
