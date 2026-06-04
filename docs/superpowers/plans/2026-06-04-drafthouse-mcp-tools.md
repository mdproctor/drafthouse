# DraftHouseMcpTools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `DraftHouseMcpTools` — a Quarkus MCP tool surface that lets any LLM client start, query, update selection on, and end document review sessions backed by Qhorus channels and a LangChain4j reviewer agent. Simultaneously fixes a stale-snapshot bug in `ReviewerChannelBackend` and removes the deprecated `ReviewSessionResource`.

**Architecture:** `DraftHouseMcpTools` creates a Qhorus APPEND channel per review session, stores document content directly on `ReviewSession` (no DataService), puts the session in `ReviewSessionRegistry` before calling `initChannel()` (which synchronously fires `ChannelInitialisedEvent` → `ReviewerChannelBackendFactory` registers `ReviewerChannelBackend`). The backend is fixed to read the live session from the registry on every `post()` call, eliminating the stale-snapshot bug. Caller's session handle is `channel.id.toString()` — O(1) registry lookup with no secondary index.

**Tech Stack:** Java 21, Quarkus 3.34.3, casehub-qhorus 0.2-SNAPSHOT, quarkus-mcp-server, JUnit 5, Mockito, AssertJ.

---

## File Map

| Action | File |
|--------|------|
| MODIFY | `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java` |
| MODIFY | `server/api/src/test/java/io/casehub/drafthouse/ReviewSessionTest.java` |
| MODIFY | `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java` |
| MODIFY | `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java` |
| CREATE | `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java` |
| DELETE | `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionResource.java` |
| REWRITE | `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java` |
| MODIFY | `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java` |
| CREATE | `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java` |
| DELETE | `server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewSessionResourceTest.java` |

---

## Task 1: Update ReviewSession (api module)

Replace `docAKey`/`docBKey` with `docAContent`/`docBContent`. Add `channelName` (needed by `end_review` to delete the channel by name — the channel name slug is independent from `channel.id`, so it must be stored explicitly).

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/ReviewSessionTest.java`

- [ ] **Step 1.1: Write failing test** — add a test that uses the new field names (will fail because fields don't exist yet)

Replace the entire contents of `ReviewSessionTest.java`:

```java
package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();

    private ReviewSession minimal() {
        return new ReviewSession(
                CHANNEL_ID,
                CHANNEL_ID.toString(),
                "drafthouse/test-channel",
                "drafthouse-reviewer-" + CHANNEL_ID,
                "Content of document A",
                "Content of document B",
                null,
                null,
                "You are a reviewer."
        );
    }

    @Test
    void constructsWithRequiredFields() {
        var s = minimal();
        assertEquals(CHANNEL_ID, s.channelId());
        assertEquals(CHANNEL_ID.toString(), s.sessionId());
        assertEquals("drafthouse/test-channel", s.channelName());
        assertEquals("drafthouse-reviewer-" + CHANNEL_ID, s.instanceId());
        assertEquals("Content of document A", s.docAContent());
        assertEquals("Content of document B", s.docBContent());
        assertNull(s.selectionSide());
        assertNull(s.selectionText());
        assertEquals("You are a reviewer.", s.personality());
    }

    @Test
    void constructsWithSelection() {
        var s = new ReviewSession(
                CHANNEL_ID, "sid", "cname", "iid", "docA", "docB",
                DocumentSide.A, "selected text", "persona"
        );
        assertEquals(DocumentSide.A, s.selectionSide());
        assertEquals("selected text", s.selectionText());
    }

    @Test
    void halfNullSelectionStateRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "cn", "i", "a", "b",
                        DocumentSide.A, null, "p"));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "cn", "i", "a", "b",
                        null, "text", "p"));
    }

    @Test
    void equalityByValue() {
        assertEquals(minimal(), minimal());
    }

    @Test
    void withSelectionReturnsNewRecord() {
        var original = minimal();
        var updated = original.withSelection(DocumentSide.B, "some text");
        assertNull(original.selectionSide());
        assertEquals(DocumentSide.B, updated.selectionSide());
        assertEquals("some text", updated.selectionText());
        assertEquals(original.channelId(), updated.channelId());
        assertEquals(original.sessionId(), updated.sessionId());
        assertEquals(original.channelName(), updated.channelName());
        assertEquals(original.instanceId(), updated.instanceId());
        assertEquals(original.docAContent(), updated.docAContent());
        assertEquals(original.docBContent(), updated.docBContent());
        assertEquals(original.personality(), updated.personality());
    }

    @Test
    void withSelectionClearsSelection() {
        var withSel = new ReviewSession(
                CHANNEL_ID, "s", "cn", "i", "a", "b", DocumentSide.A, "text", "p"
        );
        var cleared = withSel.withSelection(null, null);
        assertNull(cleared.selectionSide());
        assertNull(cleared.selectionText());
    }
}
```

- [ ] **Step 1.2: Run test to confirm it fails**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ReviewSessionTest 2>&1 | tail -20
```

Expected: COMPILATION ERROR — `channelName()`, `docAContent()`, `docBContent()` not found.

- [ ] **Step 1.3: Update ReviewSession.java**

Replace the entire file:

```java
package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active document review session.
 *
 * selectionSide and selectionText are both null when no selection is active,
 * or both non-null when a selection is active. Mixed state is rejected by the
 * compact constructor.
 *
 * channelName is stored explicitly because the naming slug ("drafthouse/{uuid}")
 * is independent from channel.id — it cannot be reconstructed from sessionId.
 *
 * docAContent and docBContent store the full document text. Content is session-private
 * and ephemeral; using Qhorus DataService (cross-agent shared bus) would be the wrong
 * abstraction for this use case.
 */
public record ReviewSession(
        UUID channelId,       // registry key; also UUID.fromString(sessionId)
        String sessionId,     // channel.id.toString() — the caller's stable handle
        String channelName,   // "drafthouse/{slug}" — needed by end_review for deletion
        String instanceId,    // "drafthouse-reviewer-{sessionId}"
        String docAContent,   // full text of document A (bounded by maxDocChars)
        String docBContent,   // full text of document B (bounded by maxDocChars)
        DocumentSide selectionSide,  // null = no selection active (must match selectionText)
        String selectionText,        // null = no selection active (must match selectionSide)
        String personality
) {
    public ReviewSession {
        if ((selectionSide == null) != (selectionText == null)) {
            throw new IllegalArgumentException(
                    "selectionSide and selectionText must both be null or both be non-null");
        }
    }

    public ReviewSession withSelection(final DocumentSide side, final String text) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, side, text, personality
        );
    }
}
```

- [ ] **Step 1.4: Run test to confirm it passes**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=ReviewSessionTest 2>&1 | tail -15
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 1.5: Run full api test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl api 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — all other api tests should still pass (they don't reference ReviewSession fields by name except through the record constructor, which the debate tests don't use).

- [ ] **Step 1.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java server/api/src/test/java/io/casehub/drafthouse/ReviewSessionTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor(api): replace docAKey/docBKey with docAContent/docBContent on ReviewSession  Refs #24"
```

---

## Task 2: Fix ReviewerChannelBackend (live session lookup)

The current backend holds `final ReviewSession session` — a stale snapshot. `update_selection` swaps the registry entry but the backend never sees the update. Fix: inject `ReviewSessionRegistry` + `UUID channelId`; read live session on every `post()` call.

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`
- Rewrite: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java`

- [ ] **Step 2.1: Write failing tests** — rewrite ReviewerChannelBackendTest.java for new design

Replace the entire file:

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

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;

class ReviewerChannelBackendTest {

    private static final UUID CHANNEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ReviewSessionRegistry registry;
    private MessageService messageService;
    private DocumentReviewer llm;
    private ReviewerChannelBackend backend;
    private ChannelRef channelRef;
    private ReviewSession session;

    @BeforeEach
    void setUp() {
        registry = mock(ReviewSessionRegistry.class);
        messageService = mock(MessageService.class);
        llm = mock(DocumentReviewer.class);

        session = new ReviewSession(
                CHANNEL_ID, CHANNEL_ID.toString(), "drafthouse/sess-1",
                "drafthouse-reviewer-" + CHANNEL_ID,
                "Original text", "Revised text",
                null, null, "You are a reviewer.");

        backend = new ReviewerChannelBackend(registry, CHANNEL_ID, messageService, llm, 100_000);
        channelRef = new ChannelRef(CHANNEL_ID, "drafthouse/sess-1");

        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(session));

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
        assertThat(d.sender()).isEqualTo(session.instanceId());
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
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Out of scope.");
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
    void queryWithNoSelection_passesEmptySelectionContext() {
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Looks good."));

        backend.post(channelRef, query("Is this clear?"));

        verify(llm).review(any(), any(), any(), eq(""), any());
    }

    @Test
    void queryWithSelection_passesSelectionContext() {
        ReviewSession withSelection = session.withSelection(DocumentSide.B, "key paragraph");
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(withSelection));
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Noted."));

        backend.post(channelRef, query("Is this clear?"));

        verify(llm).review(any(), any(), any(),
                eq("Selected text (Document B): key paragraph"), any());
    }

    @Test
    void liveSessionRead_reflectsSelectionUpdatedAfterConstruction() {
        // Backend is constructed with no selection. Then selection is updated in the registry.
        // Because post() reads from registry on every call, it should see the updated selection.
        ReviewSession updated = session.withSelection(DocumentSide.A, "updated selection");
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(updated));
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "OK"));

        backend.post(channelRef, query("Review?"));

        verify(llm).review(any(), any(), any(),
                eq("Selected text (Document A): updated selection"), any());
    }

    @Test
    void endedSession_silentlyIgnoresQuery() {
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.empty());

        backend.post(channelRef, query("Is this clear?"));

        verifyNoInteractions(llm);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void inReplyToNotFound_skipsDispatch() {
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.empty());

        backend.post(channelRef, query("Is this clear?"));

        verify(messageService, never()).dispatch(any());
        verifyNoInteractions(llm);
    }

    @Test
    void documentsExceedingMaxSize_dispatchDecline_withoutCallingLlm() {
        String huge = "x".repeat(100_001);
        ReviewSession bigSession = new ReviewSession(
                CHANNEL_ID, CHANNEL_ID.toString(), "drafthouse/sess-1",
                session.instanceId(), huge, "Revised text",
                null, null, "You are a reviewer.");
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(bigSession));

        backend.post(channelRef, query("Review this"));

        verifyNoInteractions(llm);
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Documents exceed the maximum size for review.");
    }

    private OutboundMessage query(String content) {
        return new OutboundMessage(
                UUID.randomUUID(), "human:tester", MessageType.QUERY, content,
                CORRELATION_ID, null, ActorType.HUMAN);
    }
}
```

- [ ] **Step 2.2: Run tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests -q && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerChannelBackendTest 2>&1 | tail -20
```

Expected: COMPILATION ERROR — `ReviewerChannelBackend` constructor mismatch.

- [ ] **Step 2.3: Rewrite ReviewerChannelBackend.java**

Replace entire file:

```java
package io.casehub.drafthouse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;

public class ReviewerChannelBackend implements ChannelBackend {

    static final String BACKEND_ID = "drafthouse-reviewer";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(ReviewerChannelBackend.class.getName());

    private final ReviewSessionRegistry registry;
    private final UUID channelId;
    private final MessageService messageService;
    private final DocumentReviewer llm;
    private final int maxDocChars;

    ReviewerChannelBackend(ReviewSessionRegistry registry, UUID channelId,
                           MessageService messageService, DocumentReviewer llm,
                           int maxDocChars) {
        this.registry = registry;
        this.channelId = channelId;
        this.messageService = messageService;
        this.llm = llm;
        this.maxDocChars = maxDocChars;
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
            result = llm.review(session.personality(), session.docAContent(),
                    session.docBContent(), selectionContext, message.content());
        } catch (Exception e) {
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

- [ ] **Step 2.4: Update ReviewerChannelBackendFactory.java**

Change `onChannelInitialised()` to pass `this` (the factory IS the registry) and `channelId` instead of `session` and `dataService`. Remove the `DataService` injection entirely.

Replace entire file:

```java
package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;

@ApplicationScoped
public class ReviewerChannelBackendFactory implements ReviewSessionRegistry {

    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;
    @Inject DraftHouseConfig config;

    private final ConcurrentHashMap<UUID, ReviewSession> sessions = new ConcurrentHashMap<>();

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        if (!sessions.containsKey(event.channelId())) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                this, event.channelId(), messageService, llm, config.maxDocChars());
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, ReviewerChannelBackend.BACKEND_TYPE);
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

- [ ] **Step 2.5: Update ReviewSessionLifecycleIT.java** — remove DataService usage; use docAContent/docBContent directly

Replace the body of `query_dispatchesResponse_andResponseIsFound()` and the factory tests that use old constructor:

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
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

    @InjectMock DocumentReviewer documentReviewer;

    @BeforeEach
    void setUp() {
        when(documentReviewer.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Good revision."));
    }

    @Test
    void query_dispatchesResponse_andResponseIsFound() {
        Channel channel = channelService.create(
                "drafthouse/" + UUID.randomUUID(), "Test session", ChannelSemantic.APPEND, null);
        String sessionId = channel.id.toString();
        String instanceId = "drafthouse-reviewer-" + sessionId;

        ReviewSession session = new ReviewSession(
                channel.id, sessionId, channel.name, instanceId,
                "Original text", "Revised text",
                null, null, "You are a reviewer.");
        factory.put(session);

        gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name));

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Is this revision clear?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        Optional<Message> response = Optional.empty();
        for (int i = 0; i < 40 && response.isEmpty(); i++) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            response = messageService.findResponseByCorrelationId(channel.id, correlationId);
        }
        assertThat(response).isPresent();
        assertThat(response.get().messageType).isEqualTo(MessageType.RESPONSE);
        assertThat(response.get().sender).isEqualTo(instanceId);
        assertThat(response.get().content).isEqualTo("Good revision.");
    }

    @Test
    void startupRecovery_skipsChannelWithNoSession() {
        Channel channel = channelService.create(
                "drafthouse/orphan-" + UUID.randomUUID(), "Orphan", ChannelSemantic.APPEND, null);
        gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name));

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Hello?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        assertThat(messageService.findResponseByCorrelationId(channel.id, correlationId)).isEmpty();
    }

    @Test
    void factory_find_returnsSession_afterPut() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "iid", "docA", "docB", null, null, "personality");
        factory.put(session);
        assertThat(factory.find(channelId)).contains(session);
    }

    @Test
    void factory_remove_clearsSession() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "iid", "docA", "docB", null, null, "personality");
        factory.put(session);
        factory.remove(channelId);
        assertThat(factory.find(channelId)).isEmpty();
    }

    @Test
    void factory_updateSelection_replacesSelectionFields() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "iid", "docA", "docB", null, null, "personality");
        factory.put(session);
        factory.updateSelection(channelId, DocumentSide.A, "selected text");
        ReviewSession updated = factory.find(channelId).orElseThrow();
        assertThat(updated.selectionSide()).isEqualTo(DocumentSide.A);
        assertThat(updated.selectionText()).isEqualTo("selected text");
    }
}
```

- [ ] **Step 2.6: Run all non-E2E runtime tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests -q && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="ReviewerChannelBackendTest,ReviewSessionLifecycleIT" 2>&1 | tail -20
```

Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`

- [ ] **Step 2.7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java server/runtime/src/test/java/io/casehub/drafthouse/ReviewerChannelBackendTest.java server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionLifecycleIT.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "fix(backend): read live session from registry on each post() — eliminate stale snapshot  Refs #24"
```

---

## Task 3: Delete ReviewSessionResource

`ReviewSessionResource` is `@Deprecated // Superseded by DraftHouseMcpTools (#24)`. Remove it and its test.

**Files:**
- Delete: `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionResource.java`
- Delete: `server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewSessionResourceTest.java`

- [ ] **Step 3.1: Delete the files**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse rm server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionResource.java server/runtime/src/test/java/io/casehub/drafthouse/debate/ReviewSessionResourceTest.java
```

- [ ] **Step 3.2: Build to confirm no compile errors**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor: delete deprecated ReviewSessionResource (superseded by DraftHouseMcpTools)  Refs #24"
```

---

## Task 4: Implement DraftHouseMcpTools (TDD)

Four tools: `start_review`, `update_selection`, `query_review`, `end_review`. Write all failing tests first, then implement.

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java`

- [ ] **Step 4.1: Write all failing tests — create DraftHouseMcpToolsTest.java**

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;

class DraftHouseMcpToolsTest {

    @TempDir Path tempDir;

    private ChannelService channelService;
    private ChannelGateway channelGateway;
    private InstanceService instanceService;
    private MessageService messageService;
    private ReviewSessionRegistry registry;
    private DraftHouseConfig config;
    private DraftHouseMcpTools tools;

    private Channel stubChannel;
    private Instance stubInstance;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        channelGateway = mock(ChannelGateway.class);
        instanceService = mock(InstanceService.class);
        messageService = mock(MessageService.class);
        registry = mock(ReviewSessionRegistry.class);
        config = mock(DraftHouseConfig.class);

        tools = new DraftHouseMcpTools();
        tools.channelService = channelService;
        tools.channelGateway = channelGateway;
        tools.instanceService = instanceService;
        tools.messageService = messageService;
        tools.registry = registry;
        tools.config = config;

        when(config.maxDocChars()).thenReturn(100_000);
        when(config.personality()).thenReturn("You are a reviewer.");

        stubChannel = new Channel();
        stubChannel.id = UUID.randomUUID();
        stubChannel.name = "drafthouse/" + UUID.randomUUID();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(stubChannel);

        stubInstance = new Instance();
        stubInstance.id = UUID.randomUUID();
        when(instanceService.register(anyString(), anyString(), any())).thenReturn(stubInstance);
    }

    // ── start_review ─────────────────────────────────────────────────────────

    @Test
    void startReview_happyPath_returnsSessionIdAndCreatesSession() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "Content A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "Content B");

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).contains(stubChannel.id.toString());

        ArgumentCaptor<ReviewSession> sessionCaptor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(registry).put(sessionCaptor.capture());
        ReviewSession session = sessionCaptor.getValue();
        assertThat(session.channelId()).isEqualTo(stubChannel.id);
        assertThat(session.sessionId()).isEqualTo(stubChannel.id.toString());
        assertThat(session.channelName()).isEqualTo(stubChannel.name);
        assertThat(session.docAContent()).isEqualTo("Content A");
        assertThat(session.docBContent()).isEqualTo("Content B");
        assertThat(session.personality()).isEqualTo("You are a reviewer.");
        assertThat(session.selectionSide()).isNull();
        assertThat(session.selectionText()).isNull();
    }

    @Test
    void startReview_registryPutBeforeInitChannel() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        var order = inOrder(registry, channelGateway);
        tools.startReview(docA.toString(), docB.toString());
        order.verify(registry).put(any());
        order.verify(channelGateway).initChannel(eq(stubChannel.id), any(ChannelRef.class));
    }

    @Test
    void startReview_docTooLarge_returnsError_noQhorusCalls() throws IOException {
        String huge = "x".repeat(100_001);
        Path docA = Files.writeString(tempDir.resolve("a.md"), huge);
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_fileNotFound_returnsError_noQhorusCalls() {
        String result = tools.startReview("/nonexistent/path/doc.md", "/also/nonexistent.md");

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_channelServiceThrows_cleanupAttempted() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");
        when(channelService.create(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).startsWith("error:");
        // channel creation failed before registry.put — no cleanup needed
        verify(registry, never()).put(any());
    }

    // ── update_selection ──────────────────────────────────────────────────────

    @Test
    void updateSelection_happyPath_updatesRegistry() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.updateSelection(channelId.toString(), "A", "selected text");

        assertThat(result).contains("ok");
        verify(registry).updateSelection(channelId, DocumentSide.A, "selected text");
    }

    @Test
    void updateSelection_nullSideAndText_clearsSelection() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), null, null);

        assertThat(result).contains("ok");
        verify(registry).updateSelection(channelId, null, null);
    }

    @Test
    void updateSelection_invalidSide_returnsError_noRegistryUpdate() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), "LEFT", "text");

        assertThat(result).startsWith("error:");
        verify(registry, never()).updateSelection(any(), any(), any());
    }

    @Test
    void updateSelection_sessionNotFound_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.updateSelection(channelId.toString(), "A", "text");

        assertThat(result).startsWith("error:");
    }

    @Test
    void updateSelection_invalidSessionId_returnsError() {
        String result = tools.updateSelection("not-a-uuid", "A", "text");
        assertThat(result).startsWith("error:");
    }

    // ── query_review ──────────────────────────────────────────────────────────

    @Test
    void queryReview_happyPath_dispatchesQuery() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.queryReview(channelId.toString(), "Is this revision clear?");

        assertThat(result).contains("dispatched");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.QUERY);
        assertThat(d.channelId()).isEqualTo(channelId);
        assertThat(d.content()).isEqualTo("Is this revision clear?");
        assertThat(d.sender()).isEqualTo(DraftHouseMcpTools.HUMAN_INSTANCE_ID);
        assertThat(d.correlationId()).isNotBlank();
    }

    @Test
    void queryReview_sessionNotFound_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.queryReview(channelId.toString(), "Question?");

        assertThat(result).startsWith("error:");
        verifyNoInteractions(messageService);
    }

    @Test
    void queryReview_invalidSessionId_returnsError() {
        String result = tools.queryReview("bad-uuid", "Question?");
        assertThat(result).startsWith("error:");
    }

    // ── end_review ────────────────────────────────────────────────────────────

    @Test
    void endReview_happyPath_removesSession() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.endReview(channelId.toString(), false);

        assertThat(result).contains("ended");
        verify(registry).remove(channelId);
        verify(channelService, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void endReview_withDeleteChannel_deletesChannel() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.endReview(channelId.toString(), true);

        assertThat(result).contains("ended");
        verify(registry).remove(channelId);
        verify(channelService).delete(session.channelName(), true);
    }

    @Test
    void endReview_sessionNotFound_returnsNotFound_idempotent() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.endReview(channelId.toString(), false);

        assertThat(result).contains("not-found");
        verify(registry, never()).remove(any());
    }

    @Test
    void endReview_invalidSessionId_returnsError() {
        String result = tools.endReview("not-a-uuid", false);
        assertThat(result).startsWith("error:");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReviewSession minimalSession(UUID channelId) {
        return new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "drafthouse-reviewer-" + channelId,
                "Doc A", "Doc B", null, null, "You are a reviewer.");
    }
}
```

- [ ] **Step 4.2: Run tests to confirm they fail**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests -q && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DraftHouseMcpToolsTest 2>&1 | tail -15
```

Expected: COMPILATION ERROR — `DraftHouseMcpTools` class not found.

- [ ] **Step 4.3: Implement DraftHouseMcpTools.java**

```java
package io.casehub.drafthouse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * MCP tool surface for DraftHouse document review sessions.
 *
 * File path policy: docAPath and docBPath are read from the local filesystem with no
 * path restriction — DraftHouse is a local-only tool and this is intentional, consistent
 * with FileResource. Harden with a base-directory restriction before any networked
 * deployment.
 */
@ApplicationScoped
public class DraftHouseMcpTools {

    static final String HUMAN_INSTANCE_ID = "drafthouse-human";

    private static final Logger LOG = Logger.getLogger(DraftHouseMcpTools.class.getName());

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ReviewSessionRegistry registry;
    @Inject DraftHouseConfig config;

    @PostConstruct
    void registerHumanInstance() {
        instanceService.register(HUMAN_INSTANCE_ID, "DraftHouse human reviewer",
                List.of("document-review-human"));
    }

    @Tool(name = "start_review",
          description = "Start a document review session. Returns JSON with sessionId (use for all subsequent calls) and channel name.")
    public String startReview(
            @ToolArg(description = "Absolute path to document A (the 'before' version)") String docAPath,
            @ToolArg(description = "Absolute path to document B (the 'after' version)") String docBPath) {

        String docAContent = readFile(docAPath);
        if (docAContent == null) return "error: could not read document A: " + docAPath;

        String docBContent = readFile(docBPath);
        if (docBContent == null) return "error: could not read document B: " + docBPath;

        if (docAContent.length() > config.maxDocChars()) {
            return "error: document A exceeds maximum size of " + config.maxDocChars() + " characters";
        }
        if (docBContent.length() > config.maxDocChars()) {
            return "error: document B exceeds maximum size of " + config.maxDocChars() + " characters";
        }

        String channelSlug = UUID.randomUUID().toString();
        String channelName = "drafthouse/" + channelSlug;

        Channel channel = null;
        try {
            channel = channelService.create(channelName, "DraftHouse review session",
                    ChannelSemantic.APPEND, null);

            String sessionId = channel.id.toString();
            String instanceId = "drafthouse-reviewer-" + sessionId;
            instanceService.register(instanceId, "DraftHouse reviewer " + sessionId,
                    List.of("document-review"));

            ReviewSession session = new ReviewSession(
                    channel.id, sessionId, channelName, instanceId,
                    docAContent, docBContent, null, null, config.personality());

            // MUST put session in registry before initChannel — onChannelInitialised()
            // reads from the registry synchronously during the CDI event.
            registry.put(session);
            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, channelName));

            return "{\"sessionId\":\"" + sessionId + "\",\"channel\":\"" + channelName + "\"}";

        } catch (Exception e) {
            LOG.warning("start_review failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channelName, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "update_selection",
          description = "Update the selected text in the review session. Pass null for side and selectedText to clear the selection.")
    public String updateSelection(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "Document side: 'A' or 'B'. Null to clear selection.") String side,
            @ToolArg(description = "Selected text. Null to clear selection.") String selectedText) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        if (registry.find(channelId).isEmpty()) {
            return "error: no active session for sessionId: " + sessionId;
        }

        DocumentSide docSide;
        if (side == null) {
            docSide = null;
        } else {
            try {
                docSide = DocumentSide.valueOf(side);
            } catch (IllegalArgumentException e) {
                return "error: invalid side value '" + side + "' — must be 'A' or 'B'";
            }
        }

        registry.updateSelection(channelId, docSide, selectedText);
        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ok\"}";
    }

    @Tool(name = "query_review",
          description = "Send a question or review request to the document reviewer. The reviewer responds asynchronously via the Qhorus channel.")
    public String queryReview(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "The question or review request") String question) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        if (registry.find(channelId).isEmpty()) {
            return "error: no active session for sessionId: " + sessionId;
        }

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content(question)
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        return "{\"sessionId\":\"" + sessionId + "\",\"correlationId\":\"" + correlationId
                + "\",\"status\":\"dispatched\"}";
    }

    @Tool(name = "end_review",
          description = "End a review session. Pass deleteChannel=true to fully remove the Qhorus channel.")
    public String endReview(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "Whether to delete the Qhorus channel (default: false)") boolean deleteChannel) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        Optional<ReviewSession> sessionOpt = registry.find(channelId);
        if (sessionOpt.isEmpty()) {
            return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"not-found\"}";
        }

        ReviewSession session = sessionOpt.get();
        registry.remove(channelId);

        if (deleteChannel) {
            try {
                channelService.delete(session.channelName(), true);
            } catch (Exception e) {
                LOG.warning("end_review: channel delete failed for " + session.channelName()
                        + ": " + e.getMessage());
            }
        }

        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ended\",\"channelDeleted\":"
                + deleteChannel + "}";
    }

    private String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.warning("Could not read file " + path + ": " + e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4.4: Run tests to confirm they pass**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests -q && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DraftHouseMcpToolsTest 2>&1 | tail -20
```

Expected: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 4.5: Run full non-E2E runtime test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dexcludes="**/e2e/**" 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4.6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat: DraftHouseMcpTools — start_review, update_selection, query_review, end_review  Closes #24"
```

---

## Task 5: Full build verification + code review

- [ ] **Step 5.1: Full build including E2E tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime 2>&1 | tail -20
```

Expected: All tests pass including E2E.

- [ ] **Step 5.2: Invoke superpowers:requesting-code-review**

Review the entire diff on this branch before declaring done.

- [ ] **Step 5.3: Invoke implementation-doc-sync**

Sync any doc changes implied by the implementation.

- [ ] **Step 5.4: Push branch**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse push origin issue-5-quality-cleanup
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ `start_review` — files read, size checked, channel created, instance registered, session stored, initChannel after put, rollback on failure
- ✅ `update_selection` — UUID parse guard, session found guard, side parse guard, null clears
- ✅ `query_review` — UUID parse guard, session found guard, QUERY dispatched with HUMAN_INSTANCE_ID
- ✅ `end_review` — UUID parse guard, idempotent not-found, registry.remove, optional channel delete
- ✅ ReviewerChannelBackend stale-snapshot bug — reads live session from registry on each post()
- ✅ ReviewSessionResource deleted
- ✅ DataService removed from ReviewerChannelBackend and ReviewerChannelBackendFactory
- ✅ channelName stored on ReviewSession for channel deletion
- ✅ Personality from config only — no client parameter
- ✅ File path risk documented in class javadoc
- ✅ @PostConstruct registers "drafthouse-human" instance

**Placeholder scan:** No TBDs or incomplete steps.

**Type consistency:**
- `ReviewSession` constructor args: (channelId UUID, sessionId String, channelName String, instanceId String, docAContent String, docBContent String, selectionSide, selectionText, personality) — consistent across Task 1, 2, 4 tests and Task 4 implementation
- `ReviewerChannelBackend` constructor: (registry, channelId UUID, messageService, llm, maxDocChars int) — consistent between Task 2 impl and Task 2 test
- `DraftHouseMcpTools.HUMAN_INSTANCE_ID` — used in both impl (Task 4.3) and test (Task 4.1 `assertThat(d.sender()).isEqualTo(DraftHouseMcpTools.HUMAN_INSTANCE_ID)`)
- `Channel.id` (UUID), `Channel.name` (String) — consistent with how ReviewSessionLifecycleIT uses them
