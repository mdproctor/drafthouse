# Reviewer Channel Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `DocumentReviewer @RegisterAiService`, `ReviewerChannelBackend`, and `ReviewerChannelBackendFactory` so that a Qhorus QUERY on a `drafthouse/{sessionId}` channel triggers an LLM review and dispatches RESPONSE or DECLINE.

**Architecture:** `ReviewerChannelBackendFactory` (`@ApplicationScoped`, implements `ReviewSessionRegistry`) observes `ChannelInitialisedEvent` and registers a per-session `ReviewerChannelBackend` for every `drafthouse/` channel that has an active session. The backend receives QUERY messages via `post(ChannelRef, OutboundMessage)`, loads documents from Qhorus SharedData, calls `DocumentReviewer @RegisterAiService`, and dispatches RESPONSE or DECLINE back through `MessageService.dispatch()`. `DraftHouseConfig` is a Quarkus `@ConfigMapping` in the `runtime/` module.

**Tech Stack:** Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT, quarkus-langchain4j-anthropic 1.9.1, JUnit 5, Mockito (quarkus-junit5-mockito), H2 in-memory for integration tests.

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java` | Create | `@ConfigMapping` for `personality` and `maxDocChars` |
| `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java` | Create | `@RegisterAiService` returning `ReviewResult` |
| `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java` | Create | `ChannelBackend` impl — filters QUERY, calls LLM, dispatches reply |
| `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java` | Create | `@ApplicationScoped ReviewSessionRegistry` — holds session map, observes `ChannelInitialisedEvent` |
| `server/runtime/src/main/java/io/casehub/drafthouse/CritiqueResource.java` | Delete | 501 stub replaced by Qhorus channel model |
| `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java` | Create | Pure Mockito unit tests for `post()` logic |
| `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java` | Create | `@QuarkusTest` integration test — QUERY→RESPONSE→Commitment FULFILLED |
| `server/runtime/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java` | Delete | Test for the deleted stub |
| `server/runtime/pom.xml` | Modify | Add `quarkus-junit5-mockito` test dependency |
| `server/runtime/src/main/resources/application.properties` | Verify | Already has `casehub.drafthouse.reviewer.*` properties — no change needed |

---

## Task 1: Add Mockito test dependency + DraftHouseConfig

**Files:**
- Modify: `server/runtime/pom.xml`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java`

- [ ] **Step 1.1: Add quarkus-junit5-mockito to pom.xml**

In `server/runtime/pom.xml`, after the `rest-assured` test dependency, add:

```xml
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5-mockito</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 1.2: Create DraftHouseConfig.java**

```java
package io.casehub.drafthouse;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.drafthouse.reviewer")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DraftHouseConfig {

    String personality();

    @WithDefault("100000")
    int maxDocChars();
}
```

- [ ] **Step 1.3: Verify build passes**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests -pl runtime
```

Expected: `BUILD SUCCESS`. If it fails, the `@ConfigMapping` prefix does not match `application.properties` — check the `casehub.drafthouse.reviewer.personality` and `casehub.drafthouse.reviewer.max-doc-chars` keys.

- [ ] **Step 1.4: Commit**

```bash
git add server/runtime/pom.xml server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java
git commit -m "feat(config): DraftHouseConfig @ConfigMapping + quarkus-junit5-mockito test dep  Refs #23"
```

---

## Task 2: DocumentReviewer @RegisterAiService

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java`

**Context:** `@RegisterAiService` with a record return type (`ReviewResult`) activates LangChain4j's structured output pipeline — JSON schema instructions are injected into the prompt automatically. The `@SystemMessage("{{personality}}")` uses the runtime dynamic variant (resolved from the `personality` parameter at call time). The `maven.compiler.parameters=true` property in pom.xml is required for LangChain4j parameter name resolution (already set).

- [ ] **Step 2.1: Create DocumentReviewer.java**

```java
package io.casehub.drafthouse;

import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.annotations.Blocking;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("{{personality}}")
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            User query: {{query}}

            If this query is outside the scope of document review (e.g. general knowledge, \
            unrelated topics), respond with declined=true and explain why in content.
            Otherwise respond with declined=false and your review in content.
            """)
    ReviewResult review(String personality, String documentA,
                        String documentB, String selectionContext, String query);
}
```

- [ ] **Step 2.2: Verify augmentation succeeds**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests -pl runtime
```

Expected: `BUILD SUCCESS`. If augmentation fails with `Duplicate key null`, the `maven.compiler.parameters=true` property is missing from `<properties>` in `server/runtime/pom.xml` — it is already present, but verify (GE-20260525-a8bd9a).

- [ ] **Step 2.3: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java
git commit -m "feat(llm): DocumentReviewer @RegisterAiService — QUERY → ReviewResult  Refs #23"
```

---

## Task 3: ReviewerChannelBackend — TDD

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`

**Context:** `ReviewerChannelBackend` is a plain Java class (not CDI-managed) instantiated by the factory. `ChannelBackend.post()` signature is `post(ChannelRef channel, OutboundMessage message)`. `OutboundMessage.correlationId` is `UUID` (must call `.toString()` for `MessageDispatch`). `inReplyTo` is obtained by calling `messageService.findByCorrelationId(correlationId.toString())` and extracting the `.id` Long field from the `Message` entity. If `inReplyTo` is null (lookup miss — should not happen in practice), log a warning and return without dispatching. `MessageDispatch.builder()` requires `.actorType()` — use `ActorType.AGENT`.

The `SharedData` class is at `io.casehub.qhorus.runtime.data.SharedData`; access its `content` field directly. The `Message` class is at `io.casehub.qhorus.runtime.message.Message`; access its `id` field directly.

- [ ] **Step 3.1: Write the failing test file**

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;

class ReviewerChannelBackendTest {

    private static final UUID CHANNEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private DataService dataService;
    private MessageService messageService;
    private DocumentReviewer llm;
    private ReviewerChannelBackend backend;
    private ChannelRef channelRef;

    @BeforeEach
    void setUp() {
        dataService = mock(DataService.class);
        messageService = mock(MessageService.class);
        llm = mock(DocumentReviewer.class);

        ReviewSession session = new ReviewSession(
                CHANNEL_ID, "sess-1", "drafthouse-reviewer-sess-1",
                "drafthouse/sess-1/doc-a", "drafthouse/sess-1/doc-b",
                null, null, "You are a reviewer.");
        backend = new ReviewerChannelBackend(session, dataService, messageService, llm);
        channelRef = new ChannelRef(CHANNEL_ID, "drafthouse/sess-1");

        SharedData docA = new SharedData();
        docA.content = "Original text";
        SharedData docB = new SharedData();
        docB.content = "Revised text";
        when(dataService.getByKey("drafthouse/sess-1/doc-a")).thenReturn(Optional.of(docA));
        when(dataService.getByKey("drafthouse/sess-1/doc-b")).thenReturn(Optional.of(docB));

        Message queryMsg = new Message();
        queryMsg.id = 42L;
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.of(queryMsg));
    }

    @Test
    void queryDispatches_response_onSuccess() {
        when(llm.review(any(), eq("Original text"), eq("Revised text"), any(), eq("Is this clear?")))
                .thenReturn(new ReviewResult(false, "The revision is clear."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(d.inReplyTo()).isEqualTo(42L);
        assertThat(d.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(d.sender()).isEqualTo("drafthouse-reviewer-sess-1");
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(d.content()).isEqualTo("The revision is clear.");
    }

    @Test
    void queryDispatches_decline_whenReviewerDeclines() {
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.decline("Out of scope."));

        backend.post(channelRef, query("What is the weather?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DECLINE);
        assertThat(d.content()).isEqualTo("Out of scope.");
        assertThat(d.inReplyTo()).isEqualTo(42L);
    }

    @Test
    void queryDispatches_decline_onLlmException_withSanitizedMessage() {
        when(llm.review(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("sk-ant-api03-SECRET-KEY"));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DECLINE);
        assertThat(d.content()).isEqualTo("Reviewer encountered an error.");
        assertThat(d.content()).doesNotContain("sk-ant-api03-SECRET-KEY");
    }

    @Test
    void nonQueryMessages_areIgnored() {
        backend.post(channelRef, new OutboundMessage(
                UUID.randomUUID(), "user", MessageType.EVENT, "ping",
                CORRELATION_ID, null, ActorType.HUMAN));
        verifyNoInteractions(llm, messageService);
    }

    @Test
    void commandMessages_areIgnored() {
        backend.post(channelRef, new OutboundMessage(
                UUID.randomUUID(), "orchestrator", MessageType.COMMAND, "do something",
                CORRELATION_ID, null, ActorType.AGENT));
        verifyNoInteractions(llm, messageService);
    }

    @Test
    void queryWithNoSelection_passesEmptySelectionContext() {
        when(llm.review(any(), any(), any(), eq(""), any()))
                .thenReturn(new ReviewResult(false, "Looks good."));

        backend.post(channelRef, query("Is this clear?"));

        verify(messageService).dispatch(any());
    }

    private OutboundMessage query(String content) {
        return new OutboundMessage(
                UUID.randomUUID(), "human:tester", MessageType.QUERY, content,
                CORRELATION_ID, null, ActorType.HUMAN);
    }
}
```

- [ ] **Step 3.2: Run test to confirm it fails**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerChannelBackendTest
```

Expected: FAIL with `cannot find symbol: ReviewerChannelBackend`.

- [ ] **Step 3.3: Create ReviewerChannelBackend.java**

```java
package io.casehub.drafthouse;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.message.MessageService;

public class ReviewerChannelBackend implements ChannelBackend {

    static final String BACKEND_ID = "drafthouse-reviewer";

    private static final Logger LOG = Logger.getLogger(ReviewerChannelBackend.class.getName());

    private final ReviewSession session;
    private final DataService dataService;
    private final MessageService messageService;
    private final DocumentReviewer llm;

    ReviewerChannelBackend(ReviewSession session, DataService dataService,
                           MessageService messageService, DocumentReviewer llm) {
        this.session = session;
        this.dataService = dataService;
        this.messageService = messageService;
        this.llm = llm;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.type() != MessageType.QUERY) return;

        Long inReplyTo = messageService
                .findByCorrelationId(message.correlationId().toString())
                .map(m -> m.id)
                .orElse(null);
        if (inReplyTo == null) {
            LOG.warning("Could not resolve inReplyTo for correlationId " + message.correlationId()
                    + " on channel " + channel.name() + " — skipping dispatch");
            return;
        }

        String docA = dataService.getByKey(session.docAKey()).map(d -> d.content).orElse("");
        String docB = dataService.getByKey(session.docBKey()).map(d -> d.content).orElse("");
        String selectionContext = buildSelectionContext(session);

        ReviewResult result;
        try {
            result = llm.review(session.personality(), docA, docB, selectionContext, message.content());
        } catch (Exception e) {
            result = ReviewResult.decline("Reviewer encountered an error.");
        }

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

- [ ] **Step 3.4: Run tests to confirm they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerChannelBackendTest
```

Expected: All 6 tests PASS.

- [ ] **Step 3.5: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java \
        server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java
git commit -m "feat(backend): ReviewerChannelBackend — QUERY → LLM → RESPONSE/DECLINE  Refs #23"
```

---

## Task 4: ReviewerChannelBackendFactory — TDD

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`

**Context:** The factory is `@ApplicationScoped` and implements `ReviewSessionRegistry`. It holds a `ConcurrentHashMap<UUID, ReviewSession>`. `onChannelInitialised()` uses the deregister-then-register pattern from `ClaudonyChannelBackend` for idempotency. The factory skips registration when no session exists for the channel (startup recovery gap — acceptable for Phase 2). The integration test uses `@QuarkusTest` with H2 Qhorus (already configured in `application.properties`) and `@InjectMock DocumentReviewer`. After dispatching a QUERY, the backend fires synchronously inside `MessageService.dispatch()` (fanOut is synchronous), so the RESPONSE is already persisted by the time `dispatch()` returns.

The `messageService.findResponseByCorrelationId(UUID channelId, String correlationId)` method returns the first RESPONSE message for a given channelId + correlationId.

- [ ] **Step 4.1: Write the failing integration test**

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ReviewSessionLifecycleIT {

    @Inject ReviewerChannelBackendFactory factory;
    @Inject ChannelService channelService;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DataService dataService;
    @Inject InstanceService instanceService;

    @InjectMock DocumentReviewer documentReviewer;

    @BeforeEach
    void setUp() {
        when(documentReviewer.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Good revision."));
    }

    @Test
    void query_dispatchesResponse_andReviewIsFound() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String channelName = "drafthouse/" + sessionId;
        String instanceId = "drafthouse-reviewer-" + sessionId;

        Channel channel = channelService.create(channelName, "Test session", ChannelSemantic.APPEND, null);
        instanceService.register(instanceId, "Test reviewer", List.of("document-review"));

        dataService.store("drafthouse/" + sessionId + "/doc-a", null, instanceId, "Original text", false, true);
        dataService.store("drafthouse/" + sessionId + "/doc-b", null, instanceId, "Revised text", false, true);

        ReviewSession session = new ReviewSession(
                channel.id, sessionId, instanceId,
                "drafthouse/" + sessionId + "/doc-a",
                "drafthouse/" + sessionId + "/doc-b",
                null, null, "You are a reviewer.");
        factory.put(session);

        gateway.initChannel(channel.id, new ChannelRef(channel.id, channelName));

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Is this revision clear?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        Optional<Message> response = messageService.findResponseByCorrelationId(channel.id, correlationId);
        assertThat(response).isPresent();
        assertThat(response.get().messageType).isEqualTo(MessageType.RESPONSE);
        assertThat(response.get().sender).isEqualTo(instanceId);
        assertThat(response.get().content).isEqualTo("Good revision.");
    }

    @Test
    void startupRecovery_skipsChannelWithNoSession() {
        Channel channel = channelService.create(
                "drafthouse/orphan-" + UUID.randomUUID(), "Orphan", ChannelSemantic.APPEND, null);

        // No session put — factory must not register a backend
        gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name));

        // Channel exists but no backend registered — QUERY sits as OPEN commitment, no RESPONSE
        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Hello?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        Optional<Message> response = messageService.findResponseByCorrelationId(channel.id, correlationId);
        assertThat(response).isEmpty();
    }

    @Test
    void factory_find_returnsSession_afterPut() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        assertThat(factory.find(channelId)).contains(session);
    }

    @Test
    void factory_remove_clearsSession() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        factory.remove(channelId);
        assertThat(factory.find(channelId)).isEmpty();
    }

    @Test
    void factory_updateSelection_replacesSelectionFields() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        factory.updateSelection(channelId, DocumentSide.A, "selected text");
        ReviewSession updated = factory.find(channelId).orElseThrow();
        assertThat(updated.selectionSide()).isEqualTo(DocumentSide.A);
        assertThat(updated.selectionText()).isEqualTo("selected text");
    }
}
```

- [ ] **Step 4.2: Run test to confirm it fails**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewSessionLifecycleIT
```

Expected: FAIL with `cannot find symbol: ReviewerChannelBackendFactory`.

- [ ] **Step 4.3: Create ReviewerChannelBackendFactory.java**

```java
package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;

@ApplicationScoped
public class ReviewerChannelBackendFactory implements ReviewSessionRegistry {

    @Inject ChannelGateway gateway;
    @Inject DataService dataService;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;

    private final ConcurrentHashMap<UUID, ReviewSession> sessions = new ConcurrentHashMap<>();

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        ReviewSession session = sessions.get(event.channelId());
        if (session == null) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                session, dataService, messageService, llm);
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, "agent");
    }

    @Override
    public Optional<ReviewSession> find(UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(ReviewSession session) {
        sessions.put(session.channelId(), session);
    }

    @Override
    public void remove(UUID channelId) {
        sessions.remove(channelId);
    }

    @Override
    public void updateSelection(UUID channelId, DocumentSide side, String text) {
        sessions.computeIfPresent(channelId, (id, s) -> s.withSelection(side, text));
    }
}
```

- [ ] **Step 4.4: Run integration tests to confirm they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewSessionLifecycleIT
```

Expected: All 5 tests PASS.

- [ ] **Step 4.5: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java \
        server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java
git commit -m "feat(backend): ReviewerChannelBackendFactory + ReviewSessionRegistry impl  Refs #23"
```

---

## Task 5: Delete CritiqueResource

**Files:**
- Delete: `server/runtime/src/main/java/io/casehub/drafthouse/CritiqueResource.java`
- Delete: `server/runtime/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java`

- [ ] **Step 5.1: Delete both files**

```bash
rm server/runtime/src/main/java/io/casehub/drafthouse/CritiqueResource.java
rm server/runtime/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java
```

- [ ] **Step 5.2: Run full test suite to confirm nothing breaks**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: All tests PASS. If `CritiqueResourceTest` was testing the 501 response, it's now deleted — confirm no other test references `CritiqueResource`.

- [ ] **Step 5.3: Commit**

```bash
git add -A
git commit -m "refactor: delete CritiqueResource 501 stub — replaced by Qhorus channel model  Refs #23"
```

---

## Task 6: Full build verification

- [ ] **Step 6.1: Run full reactor build including tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install
```

Expected: `BUILD SUCCESS` with all tests passing. If any test fails, investigate before proceeding.

- [ ] **Step 6.2: Verify IntelliJ sees no errors**

Use IntelliJ MCP diagnostics or open the project and check for red underlines in the new files.

- [ ] **Step 6.3: Request code review before final commit**

Invoke `superpowers:requesting-code-review` before the PR commit.
