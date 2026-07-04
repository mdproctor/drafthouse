package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.websocket.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DebateWebSocketTest {

    private static final Pattern DEBATE_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");

    @TestHTTPResource("/api/ws")
    URI wsUri;

    @Inject DebateMcpTools tools;
    @Inject ObjectMapper mapper;
    @Inject WebSocketEventBus eventBus;

    private String activeDebateSessionId;
    private Session wsSession;

    @BeforeEach
    void setUp() {
        activeDebateSessionId = null;
        wsSession = null;
    }

    @AfterEach
    void tearDown() {
        if (wsSession != null && wsSession.isOpen()) {
            try { wsSession.close(); } catch (Exception ignored) {}
        }
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
        }
    }

    @Test
    void connect_receives_reconnected_and_sessions() throws Exception {
        TestClient client = new TestClient(2);
        wsSession = connectWebSocket(client);

        assertThat(client.awaitMessages(5)).isTrue();
        List<JsonNode> messages = client.parsedMessages(mapper);
        assertThat(messages).anyMatch(m -> "reconnected".equals(m.get("topic").asText()));
        assertThat(messages).anyMatch(m -> "sessions".equals(m.get("topic").asText()));
    }

    @Test
    void subscribe_to_debate_triggers_catch_up() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractDebateId(startResult);
        tools.raisePoint(activeDebateSessionId, "REV", 1,
                "Test point", "HIGH", null, null);

        TestClient client = new TestClient(5);
        wsSession = connectWebSocket(client);
        client.awaitMessages(5);
        client.resetLatch(3);  // Expect 3 catch-up events (no comparison set)

        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");

        assertThat(client.awaitMessages(5)).isTrue();
        List<JsonNode> messages = client.parsedMessages(mapper);
        assertThat(messages).anyMatch(m -> "debate-entries".equals(m.get("topic").asText()));
        assertThat(messages).anyMatch(m -> "context-usage".equals(m.get("topic").asText()));
        assertThat(messages).anyMatch(m -> "documents-changed".equals(m.get("topic").asText()));
    }

    @Test
    void subscribe_to_nonexistent_session_silently_ignored() throws Exception {
        TestClient client = new TestClient(2);
        wsSession = connectWebSocket(client);
        client.awaitMessages(5);
        client.resetLatch(1);

        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:00000000-0000-0000-0000-000000000000\"}");

        // Should NOT receive any catch-up events — just silence
        assertThat(client.awaitMessages(2)).isFalse();
    }

    @Test
    void unrecognized_dataset_silently_ignored() throws Exception {
        TestClient client = new TestClient(2);
        wsSession = connectWebSocket(client);
        client.awaitMessages(5);
        client.resetLatch(1);

        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"_events\"}");

        assertThat(client.awaitMessages(2)).isFalse();
    }

    @Test
    void malformed_json_does_not_crash() throws Exception {
        TestClient client = new TestClient(2);
        wsSession = connectWebSocket(client);
        client.awaitMessages(5);

        wsSession.getBasicRemote().sendText("not json at all");
        wsSession.getBasicRemote().sendText("{\"no_op_field\": true}");

        // Connection should still be alive
        assertThat(wsSession.isOpen()).isTrue();
    }

    @Test
    void reconnect_receives_full_catch_up_matching_pre_disconnect_state() throws Exception {
        // Phase 1: Create debate, raise a point
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractDebateId(startResult);
        tools.raisePoint(activeDebateSessionId, "REV", 1,
                "First point", "HIGH", null, null);

        // Phase 2: Connect client-1, subscribe, verify catch-up
        TestClient client1 = new TestClient(5);
        Session session1 = connectWebSocket(client1);
        client1.awaitMessages(5); // reconnected + sessions
        client1.resetLatch(3);    // debate-entries, context-usage, documents-changed
        session1.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
        assertThat(client1.awaitMessages(5)).isTrue();

        // Phase 3: Raise a second point (live push)
        client1.resetLatch(1);
        tools.raisePoint(activeDebateSessionId, "REV", 1,
                "Second point", "MEDIUM", null, null);
        assertThat(client1.awaitMessages(5)).isTrue();

        // Phase 4: Close client-1 (simulates disconnect)
        session1.close();

        // Phase 5: Open client-2 (fresh connection = reconnection)
        TestClient client2 = new TestClient(2);
        wsSession = connectWebSocket(client2);
        assertThat(client2.awaitMessages(5)).isTrue();

        // Positional assertion: first message MUST be reconnected
        JsonNode firstMsg = mapper.readTree(client2.received.get(0));
        assertThat(firstMsg.get("topic").asText()).isEqualTo("reconnected");

        // Phase 6: Re-subscribe — catch-up must contain BOTH points
        client2.resetLatch(3); // debate-entries, context-usage, documents-changed
        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
        assertThat(client2.awaitMessages(5)).isTrue();

        List<JsonNode> catchUp = client2.parsedMessages(mapper);
        JsonNode entries = catchUp.stream()
                .filter(m -> "debate-entries".equals(m.get("topic").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("No debate-entries in catch-up"));
        assertThat(entries.get("payload").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void stale_subscription_after_session_end_silently_ignored_on_reconnect() throws Exception {
        // Phase 1: Create debate, connect, subscribe
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractDebateId(startResult);

        TestClient client1 = new TestClient(5);
        Session session1 = connectWebSocket(client1);
        client1.awaitMessages(5);
        client1.resetLatch(2);  // context-usage + documents-changed (no entries yet)
        session1.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
        assertThat(client1.awaitMessages(5)).isTrue();

        // Phase 2: End debate while still connected
        tools.endDebate(activeDebateSessionId, false);

        // Phase 3: Close connection
        session1.close();

        // Phase 4: Reconnect
        TestClient client2 = new TestClient(2);
        wsSession = connectWebSocket(client2);
        assertThat(client2.awaitMessages(5)).isTrue();

        // Phase 5: Re-subscribe to the ended session
        client2.resetLatch(1);
        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");

        // No catch-up — session is ended. Latch should NOT count down.
        assertThat(client2.awaitMessages(2)).isFalse();
        assertThat(wsSession.isOpen()).isTrue();

        activeDebateSessionId = null; // already ended — skip teardown
    }

    @Test
    void concurrent_push_from_multiple_producers() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractDebateId(startResult);

        TestClient client = new TestClient(5);
        wsSession = connectWebSocket(client);
        client.awaitMessages(5);
        client.resetLatch(2);  // context-usage + documents-changed (no entries yet)
        wsSession.getBasicRemote().sendText(
                "{\"op\":\"subscribe\",\"dataset\":\"debate:" + activeDebateSessionId + "\"}");
        assertThat(client.awaitMessages(5)).isTrue();

        // Fire two pushes from different threads, synchronized via CyclicBarrier
        client.resetLatch(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        CompletableFuture<Void> push1 = CompletableFuture.runAsync(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            tools.raisePoint(activeDebateSessionId, "REV", 1, "Concurrent point", "HIGH", null, null);
        });
        CompletableFuture<Void> push2 = CompletableFuture.runAsync(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            eventBus.pushMetadata(
                    UUID.fromString(activeDebateSessionId),
                    "context-usage",
                    java.util.Map.of("effectivePercent", 42.0, "serverContributionChars", 1000,
                            "messageCount", 5, "thresholdExceeded", false));
        });

        push1.join();
        push2.join();
        assertThat(client.awaitMessages(5)).isTrue();

        // Verify all messages are valid JSON (not garbled from interleaving)
        for (String raw : client.received) {
            assertThat(mapper.readTree(raw)).isNotNull();
        }
        List<JsonNode> msgs = client.parsedMessages(mapper);
        assertThat(msgs).anyMatch(m -> "debate-entries".equals(m.get("topic").asText()));
        assertThat(msgs).anyMatch(m -> "context-usage".equals(m.get("topic").asText()));
    }

    private Session connectWebSocket(TestClient client) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI wsUriConverted = URI.create(wsUri.toString().replace("http://", "ws://"));
        return container.connectToServer(client, wsUriConverted);
    }

    private String extractDebateId(String result) {
        Matcher m = DEBATE_ID_PATTERN.matcher(result);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @ClientEndpoint
    public static class TestClient {
        final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch;

        TestClient(int expectedMessages) {
            this.latch = new CountDownLatch(expectedMessages);
        }

        @OnMessage
        public void onMessage(String msg) {
            received.add(msg);
            latch.countDown();
        }

        boolean awaitMessages(int timeoutSeconds) throws InterruptedException {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void resetLatch(int expectedMessages) {
            received.clear();
            latch = new CountDownLatch(expectedMessages);
        }

        List<JsonNode> parsedMessages(ObjectMapper mapper) {
            return received.stream().map(s -> {
                try { return mapper.readTree(s); } catch (Exception e) { return null; }
            }).filter(n -> n != null).toList();
        }
    }
}
