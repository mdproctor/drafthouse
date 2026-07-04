package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
