package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration test proving the QUERY→(RESPONSE|DECLINE)→Commitment lifecycle end-to-end.
 *
 * ChannelGateway.fanOut() delivers to backends on virtual threads — Awaitility is required.
 * No @TestTransaction: committed channel entities must be visible to the virtual thread's
 * response dispatch (different transaction context). UUID correlation IDs isolate test data.
 */
@QuarkusTest
class ReviewSessionLifecycleTest {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("\"sessionId\":\"([^\"]+)\"");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir Path tempDir;

    @Inject DraftHouseMcpTools tools;
    @Inject ChannelService channelService;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject CommitmentStore commitmentStore;
    @InjectMock DocumentReviewer documentReviewer;

    private Path docA, docB;
    private String activeSessionId;
    private Channel orphanedChannel;

    @BeforeEach
    void setUp() throws IOException {
        docA = Files.writeString(tempDir.resolve("a.md"), "Original text");
        docB = Files.writeString(tempDir.resolve("b.md"), "Revised text");
        activeSessionId = null;
        orphanedChannel = null;
        when(documentReviewer.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("Good revision."));
    }

    @AfterEach
    void tearDown() {
        if (activeSessionId != null) {
            tools.endReview(activeSessionId, false);
        }
        if (orphanedChannel != null) {
            try {
                channelService.delete(orphanedChannel.id(), true);
            } catch (Exception ignored) {}
        }
    }

    // ── Test 1 — Happy path ───────────────────────────────────────────────────

    @Test
    void query_dispatchesDone_andFulfillsCommitment_whenReviewerAgrees() {
        final String result = tools.startReview(docA.toString(), docB.toString(), null);
        final String sessionId = extractSessionId(result);
        activeSessionId = sessionId;
        final UUID channelId = UUID.fromString(sessionId);
        final String correlationId = UUID.randomUUID().toString();

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content("Is this revision clear?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        await().atMost(TIMEOUT).until(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                return messageService.findDoneByCorrelationId(channelId, correlationId).isPresent();
            } finally {
                rc.deactivate();
            }
        });

        final var done = messageService.findDoneByCorrelationId(channelId, correlationId);
        assertThat(done).isPresent();
        assertThat(done.get().messageType()).isEqualTo(MessageType.DONE);
        assertThat(done.get().content()).isEqualTo("Good revision.");
        assertThat(done.get().sender()).isEqualTo("drafthouse-reviewer-" + sessionId);
    }

    // ── Test 2 — Orphaned channel drops query ─────────────────────────────────

    @Test
    void orphanedChannel_dropsQuery() {
        orphanedChannel = channelService.create(ChannelCreateRequest.builder(
                "drafthouse/orphan-" + UUID.randomUUID())
                .description("Orphan session")
                .semantic(ChannelSemantic.APPEND).build());
        gateway.initChannel(orphanedChannel.id(), new ChannelRef(orphanedChannel.id(), orphanedChannel.name()));

        final String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(orphanedChannel.id())
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Hello?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        // No backend registered — verify response stays empty for a stable window.
        // during() asserts the condition holds continuously, preventing false-pass from
        // a transient empty-then-filled state (GE-20260515-ed10ee).
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> messageService.findResponseByCorrelationId(
                        orphanedChannel.id(), correlationId).isEmpty());
    }

    // ── Test 3 — Reviewer declines ────────────────────────────────────────────

    @Test
    void query_dispatchesDecline_andDeclinesCommitment_whenReviewerDeclines() {
        when(documentReviewer.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.decline("Out of scope."));

        final String result = tools.startReview(docA.toString(), docB.toString(), null);
        final String sessionId = extractSessionId(result);
        activeSessionId = sessionId;
        final UUID channelId = UUID.fromString(sessionId);
        final String correlationId = UUID.randomUUID().toString();

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content("Off-topic question")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        await().atMost(TIMEOUT).until(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                return messageService.findAllByCorrelationId(correlationId).stream()
                        .anyMatch(m -> m.messageType() == MessageType.DECLINE);
            } finally {
                rc.deactivate();
            }
        });

        final var decline = messageService.findAllByCorrelationId(correlationId).stream()
                .filter(m -> m.messageType() == MessageType.DECLINE)
                .findFirst();
        assertThat(decline).isPresent();
        assertThat(decline.get().content()).isEqualTo("Out of scope.");
        assertThat(commitmentStore.findByCorrelationId(correlationId))
                .hasValueSatisfying(c -> assertThat(c.state()).isEqualTo(CommitmentState.DECLINED));
    }

    // ── Test 4 — Reviewer throws (exception sanitization) ────────────────────

    @Test
    void query_dispatchesSanitizedDecline_andDeclinesCommitment_onReviewerException() {
        when(documentReviewer.review(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("sk-ant-api03-SECRET-KEY"));

        final String result = tools.startReview(docA.toString(), docB.toString(), null);
        final String sessionId = extractSessionId(result);
        activeSessionId = sessionId;
        final UUID channelId = UUID.fromString(sessionId);
        final String correlationId = UUID.randomUUID().toString();

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content("Anything")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        await().atMost(TIMEOUT).until(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                return messageService.findAllByCorrelationId(correlationId).stream()
                        .anyMatch(m -> m.messageType() == MessageType.DECLINE);
            } finally {
                rc.deactivate();
            }
        });

        final var decline = messageService.findAllByCorrelationId(correlationId).stream()
                .filter(m -> m.messageType() == MessageType.DECLINE)
                .findFirst();
        assertThat(decline).isPresent();
        assertThat(decline.get().content()).isEqualTo("Reviewer encountered an error.");
        assertThat(decline.get().content()).doesNotContain("sk-ant-api03-SECRET-KEY");
        assertThat(commitmentStore.findByCorrelationId(correlationId))
                .hasValueSatisfying(c -> assertThat(c.state()).isEqualTo(CommitmentState.DECLINED));
    }

    // ── Test 5 — Multi-turn history ───────────────────────────────────────────

    @Test
    void secondQuery_receivesPriorExchangeInHistory() {
        String result = tools.startReview(docA.toString(), docB.toString(), null);
        String sessionId = extractSessionId(result);
        activeSessionId = sessionId;
        UUID channelId = UUID.fromString(sessionId);

        // First query — @BeforeEach stub returns "Good revision." for any 6-arg call
        String corrId1 = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder().channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY).content("First question.")
                .correlationId(corrId1).actorType(ActorType.HUMAN).build());
        await().atMost(TIMEOUT).until(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                return messageService.findDoneByCorrelationId(channelId, corrId1).isPresent();
            } finally {
                rc.deactivate();
            }
        });

        // Second query
        String corrId2 = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder().channelId(channelId)
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY).content("Second question.")
                .correlationId(corrId2).actorType(ActorType.HUMAN).build());
        await().atMost(TIMEOUT).until(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                return messageService.findDoneByCorrelationId(channelId, corrId2).isPresent();
            } finally {
                rc.deactivate();
            }
        });

        // Capture reviewHistory from both LLM calls — assert on the second invocation
        ArgumentCaptor<String> historyCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentReviewer, times(2))
                .review(any(), any(), any(), any(), historyCaptor.capture(), any());
        String secondHistory = historyCaptor.getAllValues().get(1);

        assertThat(secondHistory).contains("First question.");
        assertThat(secondHistory).contains("Good revision.");         // first answer from @BeforeEach stub
        assertThat(secondHistory).doesNotContain("Second question."); // OPEN → excluded
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String extractSessionId(final String json) {
        final Matcher m = SESSION_ID_PATTERN.matcher(json);
        if (!m.find()) throw new AssertionError("No sessionId in startReview result: " + json);
        return m.group(1);
    }
}
