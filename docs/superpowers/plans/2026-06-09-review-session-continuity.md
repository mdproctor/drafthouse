# Review Session Continuity — Sub-Agent Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement reasoning memos, sub-agent spawning via per-handler CDI beans, and provenance labelling in the DraftHouse debate channel, structured for clean extraction as the `ChannelAgentHandler` pattern.

**Architecture:** `request_subagent` posts `SUB_TASK_REQUEST` to the debate channel; `DebateChannelBackend.post()` fires a `ChannelAgentRequest` CDI event; `ChannelAgentDispatcher @ObservesAsync` routes to the matching `ChannelAgentHandler` bean, calls `DebateAgentProvider.analyse()`, and posts `SUB_TASK_FINDING` or sanitized `SUB_TASK_ERROR`. Six concrete handler beans (`VerifyHandler`, `ArbitrateHandler`, `DeepAnalysisHandler`, `ConsistencyCheckHandler`, `NeutralSummaryHandler`, `CustomHandler`) each implement only `taskType()` + `prepareTask()` — `buildResponse()` is shared via `AbstractDebateSubAgentHandler`.

**Tech Stack:** Java 21, Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT, LangChain4j 1.9.1, CDI `@ObservesAsync`, JUnit 5, Mockito, AssertJ.

---

## File Map

**Create (api):**
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskStatus.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskFinding.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/AgentTask.java`
- `server/api/src/main/java/io/casehub/drafthouse/debate/DebateAgentProvider.java`

**Modify (api):**
- `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java` — add MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java` — 4-field record
- `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java` — exhaustiveness + render memos/findings
- `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java` — add specPath
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java` — add parseMeta/bodyContent as public static

**Create (runtime — pattern types):**
- `server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentRequest.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/AgentResultParseException.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentDispatcher.java`

**Create (runtime — DraftHouse handlers):**
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/VerifyHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/ArbitrateHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/DeepAnalysisHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/ConsistencyCheckHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/NeutralSummaryHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/handler/CustomHandler.java`
- `server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jDebateAgentProvider.java`

**Create (runtime — tests):**
- `server/runtime/src/test/java/io/casehub/drafthouse/ChannelAgentDispatcherTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/handler/VerifyHandlerTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/handler/ArbitrateHandlerTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/handler/ConsistencyCheckHandlerTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/handler/CustomHandlerTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/handler/NeutralSummaryHandlerTest.java`
- `server/runtime/src/test/java/io/casehub/drafthouse/e2e/SubAgentE2ETest.java`

**Modify (runtime):**
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java` — fire ChannelAgentRequest
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` — uppercase encoding, specPath storage, post_memo, request_subagent
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` — EntryType.valueOf() switch, 4-field ReviewState, new handlers
- `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java` — uppercase META headers, new cases
- `server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java` — ChannelAgentRequest event tests

**Modify (claude-agent):**
- `server/claude-agent/src/main/java/io/casehub/drafthouse/ClaudeAgentSdkDebateAgentProvider.java` (rename from ClaudeSubAgentProvider; now implements DebateAgentProvider with AgentTask)

---

## Task 1: New domain types — `api` module

**Files:** Create SubTaskType, SubTaskStatus, SubTaskFinding, RoundMemo, AgentTask, DebateAgentProvider; modify EntryType

- [ ] **Step 1: Create `SubTaskType`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/SubTaskType.java
package io.casehub.drafthouse.debate;

public enum SubTaskType {
    VERIFY, ARBITRATE, DEEP_ANALYSIS, CONSISTENCY_CHECK, NEUTRAL_SUMMARY, CUSTOM
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
        String requestingAgent,   // "REV" or "IMP"
        String pointId,           // null for NEUTRAL_SUMMARY and CUSTOM
        String finding,           // null while PENDING or on ERROR
        String errorReason,       // fixed sanitized string — never e.getMessage()
        SubTaskStatus status
) {}
```

- [ ] **Step 4: Create `RoundMemo`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/RoundMemo.java
package io.casehub.drafthouse.debate;

public record RoundMemo(String agentRole, int round, String content) {}
```

- [ ] **Step 5: Create `AgentTask`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/AgentTask.java
package io.casehub.drafthouse.debate;

// systemPrompt before assembledInput — LLM API convention: system before user
public record AgentTask(String systemPrompt, String assembledInput) {}
```

- [ ] **Step 6: Create `DebateAgentProvider` SPI**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/DebateAgentProvider.java
package io.casehub.drafthouse.debate;

public interface DebateAgentProvider {
    /**
     * Invoke an LLM and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread.
     */
    String analyse(AgentTask task);
}
```

- [ ] **Step 7: Add four values to `EntryType`**

```java
// server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java
package io.casehub.drafthouse.debate;

public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED,
    MEMO,               // per-round reasoning memo
    SUB_TASK_REQUEST,   // request for focused sub-agent analysis
    SUB_TASK_FINDING,   // sub-agent result (provenance: fresh context)
    SUB_TASK_ERROR      // sub-agent execution failure
}
```

- [ ] **Step 8: Build `api` module**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl api
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/debate/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add SubTaskType, SubTaskStatus, SubTaskFinding, RoundMemo, AgentTask, DebateAgentProvider SPI, extend EntryType

Refs #26"
```

---

## Task 2: `ReviewState`, `DebateSession`, and `SummaryRenderer` — `api` module

**Files:** ReviewState.java, DebateSession.java, SummaryRenderer.java

- [ ] **Step 1: Write failing test for ReviewState fields**

Add to `DebateChannelProjectionTest.java`:

```java
@Test
void identity_hasEmptyMemosAndSubTaskFindings() {
    ReviewState s = new DebateChannelProjection().identity();
    assertThat(s.memos()).isEmpty();
    assertThat(s.subTaskFindings()).isEmpty();
}
```

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test-compile -pl runtime`
Expected: compilation error (no memos/subTaskFindings on ReviewState).

- [ ] **Step 2: Replace `ReviewState` with 4-field record**

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
        String specPath          // absolute path to spec; null if not provided
) {}
```

- [ ] **Step 4: Update `SummaryRenderer` — exhaustiveness fix**

In `SummaryRenderer.java`, replace the `typeLabel` switch expression to add the new entry types. These types never appear in `ThreadEntry.thread()`; the throw makes the invariant machine-checked:

```java
String typeLabel = switch (entry.type()) {
    case RAISE           -> "raise";
    case AGREE           -> "agree";
    case COUNTER         -> "counter";
    case DISPUTE         -> "dispute";
    case QUALIFY         -> "qualify";
    case FLAG_HUMAN      -> "flag";
    case DECLINED        -> "declined";
    // Invariant: these types never appear in ThreadEntry.thread()
    case MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR ->
        throw new IllegalStateException("entry type " + entry.type() + " must not appear in ThreadEntry");
};
```

- [ ] **Step 5: Add memo and sub-task rendering to `SummaryRenderer.render()`**

After the existing points loop and humanFlags section, add:

```java
// Sub-task findings with null pointId (NEUTRAL_SUMMARY, CUSTOM) — standalone section
List<SubTaskFinding> standaloneFindings = state.subTaskFindings().values().stream()
        .filter(f -> f.pointId() == null)
        .toList();
if (!standaloneFindings.isEmpty()) {
    sb.append("\n---\n\n**Sub-task findings**\n\n");
    for (SubTaskFinding f : standaloneFindings) {
        sb.append(renderFinding(f));
    }
}

// Memos section
if (!state.memos().isEmpty()) {
    sb.append("\n---\n\n**Agent Memos**\n\n");
    for (RoundMemo memo : state.memos()) {
        sb.append("**").append(memo.agentRole()).append(" memo — Round ").append(memo.round())
          .append(":** ").append(memo.content()).append("\n\n");
    }
}
```

In the per-point loop, after rendering thread entries and before the `---` separator, add inline findings for that point:

```java
state.subTaskFindings().values().stream()
        .filter(f -> point.id().equals(f.pointId()))
        .forEach(f -> sb.append(renderFinding(f)));
```

Add the `renderFinding` helper method:

```java
private String renderFinding(SubTaskFinding f) {
    return switch (f.status()) {
        case PENDING  -> "  ⏳ **" + f.taskType() + "** pending...\n";
        case ERROR    -> "  ✗ **" + f.taskType() + "** failed: " + f.errorReason() + "\n";
        case COMPLETE -> "  ⊕ **" + f.taskType() + "** _(fresh context — no prior round knowledge)_\n"
                       + "  " + f.finding() + "\n";
    };
}
```

- [ ] **Step 6: Update `DebateMcpTools.startDebate()` to store `specPath`**

In `DebateMcpTools.java`, find the `DebateSession` constructor call in `startDebate()` and add `specPath`:

```java
DebateSession session = new DebateSession(
        channel.id, debateSessionId, resolvedName,
        revInstanceId, impInstanceId, specPath);
```

- [ ] **Step 7: Update all `new ReviewState(...)` in `DebateChannelProjection` to 4-field form**

All construction sites must pass the new fields. This is a compile-required fix — the constructor changed. Pass `new ArrayList<>(state.memos())` and `new LinkedHashMap<>(state.subTaskFindings())` forward from existing state; add `List.of()` and `Map.of()` in `identity()`.

- [ ] **Step 8: Build and run existing tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all existing projection tests pass plus the new `identity_hasEmptyMemosAndSubTaskFindings` test.

- [ ] **Step 9: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/ server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): ReviewState 4-field, DebateSession.specPath, SummaryRenderer exhaustiveness + rendering

Refs #26"
```

---

## Task 3: `DebateProtocol` — public static `parseMeta` and `bodyContent`

**Files:** `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java`

`parseMeta()` and `bodyContent()` are currently private methods in `DebateChannelProjection`. Handlers and the backend both need them. Move them to `DebateProtocol` as public static methods.

- [ ] **Step 1: Add static methods to `DebateProtocol`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java
package io.casehub.drafthouse.debate;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class DebateProtocol {

    public static final String META_SENTINEL = "DHMETA:";

    private static final Logger LOG = Logger.getLogger(DebateProtocol.class.getName());

    private DebateProtocol() {}

    /**
     * Parse META sentinel header from encoded message content.
     * Format: META_SENTINEL + "key=value|key=value\n\n<body>"
     * Returns empty map if sentinel absent (plain content — not an error).
     */
    public static Map<String, String> parseMeta(String content) {
        Map<String, String> map = new HashMap<>();
        if (content == null || content.isBlank() || !content.startsWith(META_SENTINEL)) return map;
        int headerEnd = content.indexOf("\n\n");
        String headerLine = headerEnd > 0
                ? content.substring(META_SENTINEL.length(), headerEnd)
                : content.substring(META_SENTINEL.length());
        for (String part : headerLine.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        if (map.get("entryType") == null && !map.isEmpty()) {
            LOG.warning("Structured debate message (sentinel present) has no entryType. Header: "
                    + headerLine.substring(0, Math.min(80, headerLine.length())));
        }
        return map;
    }

    /**
     * Strip the sentinel header and return only the human-readable body.
     * Returns content unchanged if sentinel absent.
     */
    public static String bodyContent(String content) {
        if (content == null || !content.startsWith(META_SENTINEL)) return content;
        int headerEnd = content.indexOf("\n\n");
        return headerEnd > 0 ? content.substring(headerEnd + 2) : "";
    }
}
```

- [ ] **Step 2: Update `DebateChannelProjection` to call `DebateProtocol.parseMeta()` and `DebateProtocol.bodyContent()`**

Remove the private `parseMeta()` and `bodyContent()` methods from `DebateChannelProjection`. Replace all calls with `DebateProtocol.parseMeta(...)` and `DebateProtocol.bodyContent(...)`.

- [ ] **Step 3: Build and run projection tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/debate/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor(debate): move parseMeta/bodyContent to DebateProtocol as public static utilities

Refs #26"
```

---

## Task 4: Pattern types — `ChannelAgentHandler`, `ChannelAgentRequest`, `AgentResultParseException`

**Files:** Create three pattern-type files in `runtime/`

- [ ] **Step 1: Create `ChannelAgentRequest`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentRequest.java
package io.casehub.drafthouse;

import io.casehub.qhorus.api.gateway.OutboundMessage;
import java.util.UUID;

public record ChannelAgentRequest(
        UUID channelId,
        String correlationId,     // subTaskId — ID of the triggering SUB_TASK_REQUEST message
        OutboundMessage message   // full trigger message; handlers parse META from message.content()
) {}
```

- [ ] **Step 2: Create `AgentResultParseException`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/AgentResultParseException.java
package io.casehub.drafthouse;

public class AgentResultParseException extends RuntimeException {
    public AgentResultParseException(String message) { super(message); }
    public AgentResultParseException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: Create `ChannelAgentHandler`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentHandler.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.qhorus.api.message.MessageDispatch;
import java.util.UUID;

/**
 * SPI for channel-reactive agent handlers.
 * Handlers must have non-overlapping handles() predicates — first-match routing.
 * Will be extracted to the patterns repo when a second application (devtown) adopts this pattern.
 */
public interface ChannelAgentHandler {

    /** Return true if this handler should process the request. */
    boolean handles(ChannelAgentRequest request);

    /**
     * Assemble focused LLM input. Deliberately minimal — no extraneous context.
     * @throws IllegalArgumentException if required inputs are absent.
     */
    AgentTask prepareTask(ChannelAgentRequest request);

    /**
     * Build the Qhorus MessageDispatch from the LLM output.
     * @throws AgentResultParseException if LLM output cannot be parsed.
     */
    MessageDispatch buildResponse(UUID channelId, String senderId,
                                  String llmOutput, ChannelAgentRequest trigger)
            throws AgentResultParseException;
}
```

- [ ] **Step 4: Build**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl runtime
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentRequest.java server/runtime/src/main/java/io/casehub/drafthouse/AgentResultParseException.java server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentHandler.java && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(pattern): add ChannelAgentHandler SPI, ChannelAgentRequest CDI event, AgentResultParseException

Refs #26"
```

---

## Task 5: `ChannelAgentDispatcher` and `LangChain4jDebateAgentProvider`

- [ ] **Step 1: Write failing dispatcher test**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/ChannelAgentDispatcherTest.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelAgentDispatcherTest {

    @Mock DebateAgentProvider debateAgentProvider;
    @Mock MessageService messageService;

    // Stub handler that always matches
    ChannelAgentHandler matchingHandler = new ChannelAgentHandler() {
        public boolean handles(ChannelAgentRequest r) { return true; }
        public AgentTask prepareTask(ChannelAgentRequest r) {
            return new AgentTask("system", "user");
        }
        public MessageDispatch buildResponse(UUID channelId, String senderId,
                                             String llmOutput, ChannelAgentRequest trigger) {
            return MessageDispatch.builder()
                    .channelId(channelId).sender(senderId)
                    .type(MessageType.RESPONSE).content("FINDING: " + llmOutput)
                    .correlationId(trigger.correlationId())
                    .actorType(io.casehub.platform.api.identity.ActorType.AGENT)
                    .build();
        }
    };

    ChannelAgentDispatcher dispatcher;

    UUID channelId = UUID.randomUUID();

    // Build a minimal OutboundMessage stub — use Mockito mock
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    @BeforeEach
    void setUp() {
        when(outboundMessage.content()).thenReturn(
                io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1\n\n");
        when(outboundMessage.correlationId()).thenReturn(null);
        when(debateAgentProvider.analyse(any())).thenReturn("LLM finding text.");
        when(messageService.findByCorrelationId(any())).thenReturn(java.util.Optional.empty());

        dispatcher = new ChannelAgentDispatcher(
                debateAgentProvider, messageService,
                jakarta.enterprise.inject.Instance.of(matchingHandler));
    }

    @Test
    void handler_found_dispatches_finding() {
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        verify(messageService).dispatch(argThat(d -> d.content().contains("FINDING:")));
    }

    @Test
    void provider_throws_dispatches_sanitized_error() {
        when(debateAgentProvider.analyse(any())).thenThrow(new RuntimeException("timeout"));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(cap.getValue().content()).contains("Sub-agent analysis failed.");
        assertThat(cap.getValue().content()).doesNotContain("timeout");
    }

    @Test
    void parse_exception_dispatches_distinct_error() {
        ChannelAgentHandler throwingHandler = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return true; }
            public AgentTask prepareTask(ChannelAgentRequest r) { return new AgentTask("s", "u"); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t)
                    throws AgentResultParseException {
                throw new AgentResultParseException("bad format");
            }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                jakarta.enterprise.inject.Instance.of(throwingHandler));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().content()).contains("Sub-agent returned an unreadable result.");
    }

    @Test
    void no_handler_dispatches_error() {
        ChannelAgentHandler noMatch = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return false; }
            public AgentTask prepareTask(ChannelAgentRequest r) { throw new UnsupportedOperationException(); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t) { throw new UnsupportedOperationException(); }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                jakarta.enterprise.inject.Instance.of(noMatch));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        verify(messageService).dispatch(any());  // error dispatch
        verify(debateAgentProvider, never()).analyse(any());
    }
}
```

Run: `mvn test-compile -pl runtime` — expected: compilation error (no `ChannelAgentDispatcher`).

- [ ] **Step 2: Create `ChannelAgentDispatcher`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentDispatcher.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class ChannelAgentDispatcher {

    static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

    private static final Logger LOG = Logger.getLogger(ChannelAgentDispatcher.class.getName());

    private final DebateAgentProvider debateAgentProvider;
    private final MessageService messageService;
    private final Instance<ChannelAgentHandler> handlers;

    @Inject
    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           @Any Instance<ChannelAgentHandler> handlers) {
        this.debateAgentProvider = debateAgentProvider;
        this.messageService = messageService;
        this.handlers = handlers;
    }

    // Test constructor — no CDI, no @PostConstruct
    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           Instance<ChannelAgentHandler> handlers) {
        this.debateAgentProvider = debateAgentProvider;
        this.messageService = messageService;
        this.handlers = handlers;
    }

    @Inject InstanceService instanceService;

    @PostConstruct
    void registerSenderInstance() {
        // InstanceService.register() is an upsert — idempotent on restart, no prior deregister needed
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                java.util.List.of("document-debate-subagent"));
    }

    @ObservesAsync
    public void onChannelAgentRequest(ChannelAgentRequest request) {
        ChannelAgentHandler handler = handlers.stream()
                .filter(h -> h.handles(request))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            LOG.warning("ChannelAgentDispatcher: no handler matched on channel "
                    + request.channelId() + " — dispatching error");
            dispatchError(request, "No handler matched this sub-task request.");
            return;
        }

        try {
            AgentTask task = handler.prepareTask(request);
            String llmOutput = debateAgentProvider.analyse(task);
            try {
                MessageDispatch response = handler.buildResponse(
                        request.channelId(), SUBAGENT_INSTANCE_ID, llmOutput, request);
                messageService.dispatch(response);
            } catch (AgentResultParseException e) {
                LOG.warning("ChannelAgentDispatcher: parse failure [" + request.correlationId()
                        + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                dispatchError(request, "Sub-agent returned an unreadable result.");
            }
        } catch (Exception e) {
            LOG.warning("ChannelAgentDispatcher: sub-agent failed [" + request.correlationId()
                    + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            dispatchError(request, "Sub-agent analysis failed.");
        }
    }

    // NEVER pass exception messages — see qhorus-dispatch-exception-sanitization.md
    private void dispatchError(ChannelAgentRequest request, String fixedReason) {
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_ERROR|subTaskId=" + request.correlationId()
                + "|taskType=" + meta.getOrDefault("taskType", "UNKNOWN")
                + "|agent=" + meta.getOrDefault("agent", "UNKNOWN")
                + "\n\n" + fixedReason;
        Long inReplyTo = messageService.findByCorrelationId(request.correlationId())
                .map(m -> m.id).orElse(null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(request.channelId())
                .sender(SUBAGENT_INSTANCE_ID)
                .type(MessageType.STATUS)
                .content(encoded)
                .correlationId(request.correlationId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());
    }
}
```

- [ ] **Step 3: Create `LangChain4jDebateAgentProvider`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jDebateAgentProvider.java
package io.casehub.drafthouse;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.DefaultBean;
import jakarta.inject.Inject;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class LangChain4jDebateAgentProvider implements DebateAgentProvider {

    private final ChatLanguageModel chatModel;

    @Inject
    LangChain4jDebateAgentProvider(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String analyse(AgentTask task) {
        var response = chatModel.generate(List.of(
                SystemMessage.from(task.systemPrompt()),
                UserMessage.from(task.assembledInput())
        ));
        return response.content().text();
    }
}
```

- [ ] **Step 4: Run dispatcher tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ChannelAgentDispatcherTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Update `ClaudeAgentSdkDebateAgentProvider` in `claude-agent/`**

Replace the existing `ClaudeSubAgentProvider.java` — rename file and class, update the interface it implements (`DebateAgentProvider` with `AgentTask`):

```java
// server/claude-agent/src/main/java/io/casehub/drafthouse/ClaudeAgentSdkDebateAgentProvider.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Claude CLI-backed DebateAgentProvider. Named per ARC42STORIES.MD.
 * Activates by classpath presence; displaces LangChain4jDebateAgentProvider @DefaultBean.
 * Pending casehubio/platform#55 — stub until that ships.
 */
@ApplicationScoped
public class ClaudeAgentSdkDebateAgentProvider implements DebateAgentProvider {

    @Override
    public String analyse(AgentTask task) {
        throw new UnsupportedOperationException(
                "ClaudeAgentSdkDebateAgentProvider is a stub pending casehubio/platform#55.");
    }
}
```

- [ ] **Step 6: Build full reactor**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/ChannelAgentDispatcher.java server/runtime/src/main/java/io/casehub/drafthouse/LangChain4jDebateAgentProvider.java server/runtime/src/test/java/io/casehub/drafthouse/ChannelAgentDispatcherTest.java server/claude-agent/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): ChannelAgentDispatcher + LangChain4jDebateAgentProvider + ClaudeAgentSdkDebateAgentProvider stub

Refs #26"
```

---

## Task 6: `AbstractDebateSubAgentHandler`

**File:** `server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java`

- [ ] **Step 1: Create the abstract base class**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java
package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.AgentResultParseException;
import io.casehub.drafthouse.ChannelAgentHandler;
import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.debate.*;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

abstract class AbstractDebateSubAgentHandler implements ChannelAgentHandler {

    private static final Logger LOG = Logger.getLogger(AbstractDebateSubAgentHandler.class.getName());

    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;

    /** The SubTaskType this handler processes. */
    abstract SubTaskType taskType();

    @Override
    public final boolean handles(ChannelAgentRequest request) {
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        try {
            return SubTaskType.valueOf(meta.getOrDefault("taskType", "")) == taskType();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public final MessageDispatch buildResponse(UUID channelId, String senderId,
                                               String llmOutput, ChannelAgentRequest trigger)
            throws AgentResultParseException {
        Map<String, String> meta = DebateProtocol.parseMeta(trigger.message().content());
        String subTaskId = meta.getOrDefault("subTaskId", trigger.correlationId());
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        String pointId = meta.get("pointId");
        Long inReplyTo = messageService.findByCorrelationId(subTaskId).map(m -> m.id).orElse(null);
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_FINDING|subTaskId=" + subTaskId
                + "|taskType=" + taskType().name()
                + "|agent=" + agent
                + (pointId != null ? "|pointId=" + pointId : "")
                + "\n\n" + llmOutput;
        return MessageDispatch.builder()
                .channelId(channelId).sender(senderId)
                .type(MessageType.RESPONSE).content(encoded)
                .correlationId(subTaskId).inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT).build();
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    protected ReviewState currentState(UUID channelId) {
        return projectionService.project(channelId, debateProjection).state();
    }

    protected DebateSession requireSession(UUID channelId) {
        return registry.find(channelId).orElseThrow(() ->
            new IllegalArgumentException("No active debate session for channel " + channelId));
    }

    protected String requireSpecPath(DebateSession session) {
        if (session.specPath() == null || session.specPath().isBlank())
            throw new IllegalArgumentException(taskType()
                    + " requires specPath — start_debate must receive a spec path");
        return session.specPath();
    }

    protected String requirePointRaiseContent(ReviewState state, String pointId) {
        if (pointId == null)
            throw new IllegalArgumentException(taskType() + " requires a pointId");
        ReviewPoint p = state.points().get(pointId);
        if (p == null)
            throw new IllegalArgumentException(taskType() + ": pointId " + pointId
                    + " not found in projected state");
        return p.thread().get(0).content();
    }

    protected String readSpec(String specPath) {
        try { return Files.readString(Path.of(specPath)); }
        catch (IOException e) {
            LOG.warning("Could not read spec at " + specPath + ": " + e.getMessage());
            return "(spec file could not be read)";
        }
    }

    protected Map<String, String> metaFrom(ChannelAgentRequest request) {
        return DebateProtocol.parseMeta(request.message().content());
    }
}
```

- [ ] **Step 2: Build**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml compile -pl runtime
```

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/handler/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): AbstractDebateSubAgentHandler — shared handles() + buildResponse() for all DraftHouse handlers

Refs #26"
```

---

## Task 7: `VerifyHandler` and `ArbitrateHandler` (with unit tests)

- [ ] **Step 1: Write failing tests**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/handler/VerifyHandlerTest.java
package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerifyHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    @TempDir Path tempDir;

    VerifyHandler handler;
    UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        handler = new VerifyHandler();
        // inject via field access (package-private in test) or reflection
        setField(handler, "projectionService", projectionService);
        setField(handler, "debateProjection", debateProjection);
        setField(handler, "registry", registry);
        setField(handler, "messageService", messageService);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ChannelAgentRequest requestFor(String pointId) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=VERIFY|subTaskId=sub-1"
                + (pointId != null ? "|pointId=" + pointId : "")
                + "\n\n";
        when(outboundMessage.content()).thenReturn(content);
        when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage);
    }

    private void setupState(String pointId, String raiseContent) {
        var thread = List.of(new ThreadEntry(pointId, AgentType.REV, 1, EntryType.RAISE, raiseContent));
        var point = new ReviewPoint(pointId,
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                thread, ReviewStatus.OPEN);
        var state = new ReviewState(Map.of(pointId, point), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
    }

    @Test
    void assembles_claim_and_spec_content(@TempDir Path dir) throws IOException {
        Path specFile = dir.resolve("spec.md");
        java.nio.file.Files.writeString(specFile, "# The Spec\nThis is the spec content.");

        when(registry.find(channelId)).thenReturn(Optional.of(new DebateSession(
                channelId, channelId.toString(), "ch", "rev", "imp", specFile.toString())));
        setupState("pt-1", "The claim content.");

        AgentTask task = handler.prepareTask(requestFor("pt-1"));
        assertThat(task.assembledInput()).contains("The claim content.");
        assertThat(task.assembledInput()).contains("The Spec");
    }

    @Test
    void throws_on_null_specPath() {
        when(registry.find(channelId)).thenReturn(Optional.of(new DebateSession(
                channelId, channelId.toString(), "ch", "rev", "imp", null)));
        setupState("pt-1", "Some claim.");
        assertThatThrownBy(() -> handler.prepareTask(requestFor("pt-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specPath");
    }

    @Test
    void throws_on_null_pointId() {
        when(registry.find(channelId)).thenReturn(Optional.of(new DebateSession(
                channelId, channelId.toString(), "ch", "rev", "imp", "/some/spec.md")));
        var state = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        assertThatThrownBy(() -> handler.prepareTask(requestFor(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
// server/runtime/src/test/java/io/casehub/drafthouse/handler/ArbitrateHandlerTest.java
package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArbitrateHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    ArbitrateHandler handler;
    UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        handler = new ArbitrateHandler();
        for (var name : List.of("projectionService", "debateProjection", "registry", "messageService")) {
            var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(handler, switch (name) {
                case "projectionService" -> projectionService;
                case "debateProjection" -> debateProjection;
                case "registry" -> registry;
                case "messageService" -> messageService;
                default -> throw new IllegalStateException();
            });
        }
        when(outboundMessage.content()).thenReturn(DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1\n\n");
        when(outboundMessage.correlationId()).thenReturn(null);
        when(registry.find(channelId)).thenReturn(Optional.of(new DebateSession(
                channelId, channelId.toString(), "ch", "rev", "imp", null)));
    }

    private ReviewState stateWith(List<ThreadEntry> thread) {
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                thread, ReviewStatus.DISPUTED);
        return new ReviewState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
    }

    @Test
    void uses_last_dispute_qualify_counter_not_thread_last() {
        // Thread: RAISE → QUALIFY → FLAG_HUMAN
        // Expected: QUALIFY content used, not FLAG_HUMAN
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.QUALIFY, "The qualify."),
                new ThreadEntry(null, AgentType.REV, 3, EntryType.FLAG_HUMAN, "Flag!")
        );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("The qualify.");
        assertThat(task.assembledInput()).doesNotContain("Flag!");
    }

    @Test
    void uses_last_of_multiple_responses() {
        // Thread: RAISE → DISPUTE → COUNTER → QUALIFY
        // Expected: QUALIFY (last of DISPUTE/QUALIFY/COUNTER)
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.DISPUTE, "Dispute."),
                new ThreadEntry(null, AgentType.REV, 3, EntryType.COUNTER, "Counter."),
                new ThreadEntry(null, AgentType.IMP, 4, EntryType.QUALIFY, "Qualify.")
        );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("Qualify.");
        assertThat(task.assembledInput()).doesNotContain("Counter.");
        assertThat(task.assembledInput()).doesNotContain("Dispute.");
    }

    @Test
    void no_response_yet_uses_sentinel() {
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise.")
        );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("(no response yet)");
    }
}
```

- [ ] **Step 2: Create `VerifyHandler`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/handler/VerifyHandler.java
package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class VerifyHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.VERIFY; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        var session = requireSession(request.channelId());
        String specPath = requireSpecPath(session);
        ReviewState state = currentState(request.channelId());
        String claim = requirePointRaiseContent(state, pointId);
        String spec = readSpec(specPath);
        return new AgentTask(
                "You are a spec verifier. You have no knowledge of this debate's prior rounds. "
                + "Determine only whether this claim is supported by the spec. Be precise.",
                "Claim to verify:\n" + claim + "\n\nSpec:\n" + spec
        );
    }
}
```

- [ ] **Step 3: Create `ArbitrateHandler`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/handler/ArbitrateHandler.java
package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
class ArbitrateHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.ARBITRATE; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        ReviewState state = currentState(request.channelId());
        String raiseContent = requirePointRaiseContent(state, pointId);
        ReviewPoint point = state.points().get(pointId);
        List<ThreadEntry> thread = point.thread();
        String lastResponse = thread.stream()
                .filter(e -> e.type() == EntryType.DISPUTE
                          || e.type() == EntryType.QUALIFY
                          || e.type() == EntryType.COUNTER)
                .reduce((a, b) -> b)
                .map(ThreadEntry::content)
                .orElse("(no response yet)");
        return new AgentTask(
                "You are a neutral arbitrator. You have not seen this debate before. "
                + "Assess these two positions on their merits only. Do not favour either side.",
                "Original claim:\n" + raiseContent + "\n\nMost recent response:\n" + lastResponse
        );
    }
}
```

- [ ] **Step 4: Run handler tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="VerifyHandlerTest,ArbitrateHandlerTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/handler/ server/runtime/src/test/java/io/casehub/drafthouse/handler/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): VerifyHandler + ArbitrateHandler with unit tests

Refs #26"
```

---

## Task 8: Remaining four handlers — `DeepAnalysis`, `ConsistencyCheck`, `NeutralSummary`, `Custom`

- [ ] **Step 1: Write failing tests**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/handler/ConsistencyCheckHandlerTest.java
package io.casehub.drafthouse.handler;

// Boilerplate same as ArbitrateHandlerTest — omit for brevity; follow the same injection pattern.
// Tests:
// 1. null customInput → IAE
// 2. AGREED and OPEN points in state: only AGREED point content appears; OPEN is absent

@ExtendWith(MockitoExtension.class)
class ConsistencyCheckHandlerTest {
    // ... follow ArbitrateHandlerTest structure
    @Test void null_customInput_throws() { /* ... */ }
    @Test void only_agreed_points_included() { /* ... */ }
}
```

```java
// server/runtime/src/test/java/io/casehub/drafthouse/handler/CustomHandlerTest.java
class CustomHandlerTest {
    @Test void null_customInput_throws() { /* ... */ }
    @Test void custom_input_verbatim_in_assembled() { /* ... */ }
}
```

```java
// server/runtime/src/test/java/io/casehub/drafthouse/handler/NeutralSummaryHandlerTest.java
class NeutralSummaryHandlerTest {
    @Test void empty_state_does_not_throw() { /* ... */ }
    @Test void points_appear_in_assembled_input() { /* ... */ }
}
```

**Important:** Write the full test bodies following the same injection pattern as `VerifyHandlerTest.setUp()` above before implementing the handlers.

- [ ] **Step 2: Create `DeepAnalysisHandler`**

```java
@ApplicationScoped
class DeepAnalysisHandler extends AbstractDebateSubAgentHandler {
    @Override SubTaskType taskType() { return SubTaskType.DEEP_ANALYSIS; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        var session = requireSession(request.channelId());
        String specPath = requireSpecPath(session);
        String spec = readSpec(specPath);
        // pointId is optional for DEEP_ANALYSIS — used for location hint only
        String focusHint = "(no section indicated)";
        String pointId = meta.get("pointId");
        if (pointId != null) {
            ReviewState state = currentState(request.channelId());
            ReviewPoint p = state.points().get(pointId);
            if (p != null && p.classification().location() != null) {
                focusHint = p.classification().location();
            }
        }
        return new AgentTask(
                "You are a spec analyst reading this spec with fresh eyes. "
                + "Focus on the indicated section. Identify issues.",
                "Focus section: " + focusHint + "\n\nFull spec:\n" + spec
        );
    }
}
```

- [ ] **Step 3: Create `ConsistencyCheckHandler`**

```java
@ApplicationScoped
class ConsistencyCheckHandler extends AbstractDebateSubAgentHandler {
    @Override SubTaskType taskType() { return SubTaskType.CONSISTENCY_CHECK; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String customInput = meta.get("customInput");
        // customInput is in the message body, not in the META header
        String body = DebateProtocol.bodyContent(request.message().content());
        if (body == null || body.isBlank())
            throw new IllegalArgumentException("CONSISTENCY_CHECK requires customInput (proposed resolution text)");
        ReviewState state = currentState(request.channelId());
        int[] idx = {1};
        String agreedList = state.points().values().stream()
                .filter(p -> p.currentStatus() == ReviewStatus.AGREED)
                .map(p -> idx[0]++ + ". [" + p.id() + "] " + p.thread().get(0).content())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (agreedList.isBlank()) agreedList = "(no agreed points yet)";
        return new AgentTask(
                "You have no memory of this debate. Determine only whether the proposed "
                + "resolution contradicts any of these prior agreements.",
                "Prior agreed points:\n" + agreedList + "\n\nProposed resolution:\n" + body
        );
    }
}
```

- [ ] **Step 4: Create `NeutralSummaryHandler`**

```java
@ApplicationScoped
class NeutralSummaryHandler extends AbstractDebateSubAgentHandler {
    @Override SubTaskType taskType() { return SubTaskType.NEUTRAL_SUMMARY; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        ReviewState state = currentState(request.channelId());
        String entries = state.points().values().stream()
                .map(p -> "[" + p.id() + "] " + p.thread().stream()
                        .map(e -> e.agent() + "/" + e.type().name() + ": " + e.content())
                        .collect(java.util.stream.Collectors.joining(" | ")))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (entries.isBlank()) entries = "(no debate entries)";
        return new AgentTask(
                "Summarise this debate neutrally. You have not participated in it.",
                "Debate entries:\n" + entries
        );
    }
}
```

- [ ] **Step 5: Create `CustomHandler`**

```java
@ApplicationScoped
class CustomHandler extends AbstractDebateSubAgentHandler {
    @Override SubTaskType taskType() { return SubTaskType.CUSTOM; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        String body = DebateProtocol.bodyContent(request.message().content());
        if (body == null || body.isBlank())
            throw new IllegalArgumentException("CUSTOM requires customInput — message body must not be empty");
        return new AgentTask(
                "You are a focused analyst. Answer only the question posed. "
                + "You have no knowledge of the broader debate.",
                body
        );
    }
}
```

- [ ] **Step 6: Run all handler tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="DeepAnalysisHandler*,ConsistencyCheck*,NeutralSummary*,CustomHandler*"
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/handler/ server/runtime/src/test/java/io/casehub/drafthouse/handler/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DeepAnalysisHandler, ConsistencyCheckHandler, NeutralSummaryHandler, CustomHandler

Refs #26"
```

---

## Task 9: `DebateChannelBackend` — fire `ChannelAgentRequest` CDI event

- [ ] **Step 1: Write failing test**

In `DebateChannelBackendFactoryTest.java`, add a test that verifies a `ChannelAgentRequest` CDI event is fired when a `SUB_TASK_REQUEST` message arrives. Read the existing test file first to follow the mock pattern, then add:

```java
@Test
void post_subTaskRequest_firesChannelAgentRequest() {
    // Build mock OutboundMessage with SUB_TASK_REQUEST content
    // Assert Event<ChannelAgentRequest>.fireAsync() is called with correct channelId
    // Other entry types: assert fireAsync is NOT called
}
```

- [ ] **Step 2: Update `DebateChannelBackend`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {

    static final String BACKEND_ID   = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(DebateChannelBackend.class.getName());

    @Inject Event<ChannelAgentRequest> channelAgentEvent;
    @Inject DebateSessionRegistry registry;

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        Map<String, String> meta = DebateProtocol.parseMeta(message.content());
        if (!"SUB_TASK_REQUEST".equals(meta.get("entryType"))) return;

        DebateSession session = registry.find(channel.id()).orElse(null);
        if (session == null) {
            LOG.warning("DebateChannelBackend: SUB_TASK_REQUEST on " + channel.id()
                    + " — no active session, dropped");
            return;
        }

        String correlationId = message.correlationId() != null
                ? message.correlationId().toString() : UUID.randomUUID().toString();
        channelAgentEvent.fireAsync(new ChannelAgentRequest(channel.id(), correlationId, message));
    }
}
```

- [ ] **Step 3: Run backend tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelBackendFactoryTest
```

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java server/runtime/src/test/java/io/casehub/drafthouse/DebateChannelBackendFactoryTest.java && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelBackend fires ChannelAgentRequest CDI event on SUB_TASK_REQUEST

Refs #26"
```

---

## Task 10: `DebateChannelProjection` — encoding standard + new entry types

- [ ] **Step 1: Write failing tests (new entry types + uppercase encoding)**

Update `DebateChannelProjectionTest.java`:
1. Change ALL `msg()` helper calls to use uppercase entryType strings: `"entryType=RAISE|..."` not `"entryType=raise|..."`
2. Add tests:

```java
@Test
void memo_addsToMemosList_doesNotAddPoint() {
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.STATUS, null, "entryType=MEMO|agent=REV|round=2", "Working notes."));
    assertThat(s.memos()).hasSize(1);
    assertThat(s.memos().get(0).agentRole()).isEqualTo("REV");
    assertThat(s.memos().get(0).round()).isEqualTo(2);
    assertThat(s.memos().get(0).content()).isEqualTo("Working notes.");
    assertThat(s.points()).isEmpty();
}

@Test
void subTaskRequest_addsPendingFinding() {
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-1",
                    "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1", ""));
    assertThat(s.subTaskFindings()).containsKey("sub-1");
    assertThat(s.subTaskFindings().get("sub-1").status()).isEqualTo(SubTaskStatus.PENDING);
    assertThat(s.subTaskFindings().get("sub-1").taskType()).isEqualTo(SubTaskType.ARBITRATE);
}

@Test
void subTaskFinding_completesExistingEntry() {
    ReviewState s0 = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-1",
                    "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1", ""));
    ReviewState s1 = proj.apply(s0,
            msg(MessageType.RESPONSE, "sub-1",
                    "entryType=SUB_TASK_FINDING|subTaskId=sub-1|taskType=ARBITRATE|agent=REV|pointId=pt-1",
                    "The finding."));
    assertThat(s1.subTaskFindings().get("sub-1").status()).isEqualTo(SubTaskStatus.COMPLETE);
    assertThat(s1.subTaskFindings().get("sub-1").finding()).isEqualTo("The finding.");
}

@Test
void subTaskError_setsErrorStatus() {
    ReviewState s0 = proj.apply(proj.identity(),
            msg(MessageType.QUERY, "sub-2",
                    "entryType=SUB_TASK_REQUEST|agent=IMP|taskType=VERIFY|subTaskId=sub-2|pointId=pt-1", ""));
    ReviewState s1 = proj.apply(s0,
            msg(MessageType.STATUS, "sub-2",
                    "entryType=SUB_TASK_ERROR|subTaskId=sub-2|taskType=VERIFY|agent=IMP",
                    "Sub-agent analysis failed."));
    assertThat(s1.subTaskFindings().get("sub-2").status()).isEqualTo(SubTaskStatus.ERROR);
    assertThat(s1.subTaskFindings().get("sub-2").errorReason()).isEqualTo("Sub-agent analysis failed.");
}

@Test
void outOfOrder_findingBeforeRequest_createdAtComplete() {
    ReviewState s = proj.apply(proj.identity(),
            msg(MessageType.RESPONSE, "sub-3",
                    "entryType=SUB_TASK_FINDING|subTaskId=sub-3|taskType=CUSTOM|agent=REV",
                    "Late finding."));
    assertThat(s.subTaskFindings()).containsKey("sub-3");
    assertThat(s.subTaskFindings().get("sub-3").status()).isEqualTo(SubTaskStatus.COMPLETE);
}
```

- [ ] **Step 2: Update `DebateChannelProjection.apply()` — replace string switch with `EntryType.valueOf()`**

```java
@Override
public ReviewState apply(ReviewState state, MessageView message) {
    Map<String, String> meta = DebateProtocol.parseMeta(message.content());
    String entryTypeStr = meta.get("entryType");
    if (entryTypeStr == null) return state;

    EntryType entryType;
    try {
        entryType = EntryType.valueOf(entryTypeStr);
    } catch (IllegalArgumentException e) {
        LOG.warning("Unknown entryType '" + entryTypeStr + "' — discarded");
        return state;
    }

    return switch (entryType) {
        case RAISE            -> handleRaise(state, message, meta);
        case AGREE            -> handleAgree(state, message, meta);
        case COUNTER          -> handleCounter(state, message, meta);
        case DISPUTE          -> handleDispute(state, message, meta);
        case QUALIFY          -> handleQualify(state, message, meta);
        case FLAG_HUMAN       -> handleFlagHuman(state, message, meta);
        case DECLINED         -> state;
        case MEMO             -> handleMemo(state, message, meta);
        case SUB_TASK_REQUEST -> handleSubTaskRequest(state, message, meta);
        case SUB_TASK_FINDING -> handleSubTaskFinding(state, message, meta);
        case SUB_TASK_ERROR   -> handleSubTaskError(state, message, meta);
    };
}

@Override
public ReviewState identity() {
    return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
}
```

- [ ] **Step 3: Add the four new handlers in `DebateChannelProjection`**

All follow the same pattern — create new `ReviewState` carrying existing fields forward:

```java
private ReviewState handleMemo(ReviewState state, MessageView message, Map<String, String> meta) {
    String agent = meta.getOrDefault("agent", "UNKNOWN");
    int round = parseRound(meta);
    String content = DebateProtocol.bodyContent(message.content());
    var memos = new ArrayList<>(state.memos());
    memos.add(new RoundMemo(agent, round, content));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            memos, new LinkedHashMap<>(state.subTaskFindings()));
}

private ReviewState handleSubTaskRequest(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId = meta.getOrDefault("subTaskId",
            message.correlationId() != null ? message.correlationId() : "unknown");
    SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
    String requestingAgent = meta.getOrDefault("agent", "UNKNOWN");
    String pointId = meta.get("pointId");
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, requestingAgent,
            pointId, null, null, SubTaskStatus.PENDING));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}

private ReviewState handleSubTaskFinding(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId = meta.getOrDefault("subTaskId",
            message.correlationId() != null ? message.correlationId() : "unknown");
    SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
    String agent = meta.getOrDefault("agent", "UNKNOWN");
    String pointId = meta.get("pointId");
    String finding = DebateProtocol.bodyContent(message.content());
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    SubTaskFinding existing = findings.get(subTaskId);
    String resolvedPointId = existing != null && existing.pointId() != null ? existing.pointId() : pointId;
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, agent,
            resolvedPointId, finding, null, SubTaskStatus.COMPLETE));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}

private ReviewState handleSubTaskError(ReviewState state, MessageView message, Map<String, String> meta) {
    String subTaskId = meta.getOrDefault("subTaskId",
            message.correlationId() != null ? message.correlationId() : "unknown");
    SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
    String agent = meta.getOrDefault("agent", "UNKNOWN");
    String reason = DebateProtocol.bodyContent(message.content());
    var findings = new LinkedHashMap<>(state.subTaskFindings());
    SubTaskFinding existing = findings.get(subTaskId);
    String resolvedPointId = existing != null ? existing.pointId() : null;
    findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, agent,
            resolvedPointId, null, reason, SubTaskStatus.ERROR));
    return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
            new ArrayList<>(state.memos()), findings);
}

private SubTaskType parseSubTaskType(String s) {
    try { return SubTaskType.valueOf(s); } catch (IllegalArgumentException e) { return SubTaskType.CUSTOM; }
}
```

Also update all existing `handleRaise()`, `appendToPoint()`, `handleFlagHuman()` calls to return the 4-field `ReviewState` form.

- [ ] **Step 4: Run all projection tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateChannelProjectionTest
```

Expected: all existing + new tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateChannelProjectionTest.java && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): DebateChannelProjection uses EntryType.valueOf() switch; handles MEMO/SUB_TASK_* entries

Refs #26"
```

---

## Task 11: New MCP tools and encoding updates in `DebateMcpTools`

- [ ] **Step 1: Update all `entryType=lowercase` encodings in `DebateMcpTools`**

Find every `entryType=` string and uppercase it:
- `entryType=raise` → `entryType=RAISE`
- `entryType=agree` → `entryType=AGREE`
- `entryType=dispute` → `entryType=DISPUTE`
- `entryType=qualify` → `entryType=QUALIFY`
- `entryType=counter` → `entryType=COUNTER`
- `entryType=flag-human` → `entryType=FLAG_HUMAN`

- [ ] **Step 2: Add `post_memo` and `request_subagent` tools**

```java
@Tool(name = "post_memo",
      description = "Write a per-round reasoning memo to the debate channel. Call after your last "
              + "raise/respond of a round to record working hypotheses, patterns noticed, and why "
              + "concessions feel solid vs provisional.")
public String postMemo(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
        @ToolArg(description = "Current round number") int round,
        @ToolArg(description = "Your reasoning memo content") String content) {
    try {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);
        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole))
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
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
      description = "Dispatch a fresh-context sub-agent for focused analysis. Finding appears in "
              + "get_debate_summary (⏳ while pending). You may continue raising/responding while it runs. "
              + "taskType: VERIFY | ARBITRATE | DEEP_ANALYSIS | CONSISTENCY_CHECK | NEUTRAL_SUMMARY | CUSTOM. "
              + "customInput: for CUSTOM — the full context; for CONSISTENCY_CHECK — the proposed resolution text.")
public String requestSubagent(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
        @ToolArg(description = "Sub-task type") String taskType,
        @ToolArg(description = "pointId from raise_point. Null for NEUTRAL_SUMMARY or CUSTOM.") String pointId,
        @ToolArg(description = "For CUSTOM: full context. For CONSISTENCY_CHECK: proposed resolution. Null otherwise.") String customInput) {
    try {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);
        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole))
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        String subTaskId = UUID.randomUUID().toString();
        StringBuilder header = new StringBuilder(DebateProtocol.META_SENTINEL)
                .append("entryType=SUB_TASK_REQUEST")
                .append("|agent=").append(agentRole)
                .append("|taskType=").append(Objects.requireNonNullElse(taskType, "CUSTOM"))
                .append("|subTaskId=").append(subTaskId);
        if (pointId != null && !pointId.isBlank()) header.append("|pointId=").append(pointId);
        String encoded = header + "\n\n" + Objects.requireNonNullElse(customInput, "");
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

- [ ] **Step 3: Run `DebateMcpToolsTest` — existing tests must still pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest
```

Expected: all pass.

- [ ] **Step 4: Full reactor build + all tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(debate): add post_memo + request_subagent MCP tools; uppercase encoding throughout DebateMcpTools

Refs #26"
```

---

## Task 12: E2E — `SubAgentE2ETest`

**File:** `server/runtime/src/test/java/io/casehub/drafthouse/e2e/SubAgentE2ETest.java`

- [ ] **Step 1: Create `MockDebateAgentProvider`**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/MockDebateAgentProvider.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative @Priority(1) @ApplicationScoped
public class MockDebateAgentProvider implements DebateAgentProvider {
    @Override
    public String analyse(AgentTask task) {
        return "Mock finding for task type " + task.systemPrompt().split("\\.")[0] + ".";
    }
}
```

- [ ] **Step 2: Write the E2E test**

Read an existing E2E test (e.g. `server/runtime/src/test/java/io/casehub/drafthouse/e2e/`) to understand the Playwright + `@QuarkusTest` pattern and HTTP/MCP call format. Then write `SubAgentE2ETest` covering:

1. Start debate session via `start_debate`
2. Raise a point via `raise_point`
3. Call `request_subagent(ARBITRATE, pointId)` — verify return contains `"subTaskId"`
4. Call `get_debate_summary` — verify it contains `"⏳"` or `"ARBITRATE"` (pending)
5. Wait briefly (CDI async needs a moment) then call `get_debate_summary` again — verify `"⊕"` and mock finding text appear
6. Call `post_memo` — verify `{"status":"dispatched"}`
7. Call `get_debate_summary` — verify memo text appears
8. Call `end_debate`

- [ ] **Step 3: Run E2E tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SubAgentE2ETest
```

Expected: test passes.

- [ ] **Step 4: Run the complete test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: all tests green.

- [ ] **Step 5: Push branch**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/test/ && git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(debate): SubAgentE2ETest — full sub-agent lifecycle with MockDebateAgentProvider

Refs #26"
git -C /Users/mdproctor/claude/casehub/drafthouse push -u origin issue-26-review-session-continuity
```
