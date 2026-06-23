package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.drafthouse.e2e.DebateE2EFixtures;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class DebateEventResourceTest {

    private static final Pattern DEBATE_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;
    @Inject MessageService messageService;
    @Inject DebateSessionRegistry registry;

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
    void invalidSessionId_returns404() {
        RestAssured.given()
                .accept("text/event-stream")
                .when()
                .get("/api/debate/00000000-0000-0000-0000-000000000000/events")
                .then()
                .statusCode(404);
    }

    @Test
    void catchUp_deliversHistoricalEvents() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        tools.raisePoint(sessionId, "REV", 1,
                "The API is ambiguous.", "P1", "ISOLATED", "§3.2");

        String raiseResult2 = tools.raisePoint(sessionId, "REV", 1,
                "Missing validation.", "P2", "SYSTEMIC", null);
        String pointId2 = extractPointId(raiseResult2);
        tools.respondTo(sessionId, "IMP", 2, pointId2, "agree", "Will add.");

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> received =
                new java.util.concurrent.atomic.AtomicReference<>();

        String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    StringBuilder accumulated = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        accumulated.append(line);
                        if (accumulated.toString().contains("\"entryType\":\"RAISE\"")) {
                            received.set(accumulated.toString());
                            latch.countDown();
                            return;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // expected when connection closes
            }
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("SSE catch-up events were not received within 5 seconds")
                .isTrue();

        String body = received.get();
        assertThat(body).contains("\"entryType\":\"RAISE\"");
        assertThat(body).contains("\"agentRole\":\"REV\"");
        assertThat(body).contains("The API is ambiguous.");

        sseThread.interrupt();
    }

    @Test
    void activeSessions_returnsCurrentDebates() {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains(sessionId);
        assertThat(body).contains("test-spec.md");
    }

    @Test
    void selectionPost_storesSelection() {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        RestAssured.given()
                .contentType("application/json")
                .body("{\"side\":\"A\",\"startLine\":5,\"endLine\":12,\"selectedText\":\"The passage\"}")
                .when()
                .post("/api/debate/" + sessionId + "/selection")
                .then()
                .statusCode(200);

        DebateSession session = registry.find(java.util.UUID.fromString(sessionId)).orElseThrow();
        assertThat(session.currentSelection()).isNotNull();
        assertThat(session.currentSelection().side()).isEqualTo(DocumentSide.A);
        assertThat(session.currentSelection().startLine()).isEqualTo(5);
        assertThat(session.currentSelection().endLine()).isEqualTo(12);
        assertThat(session.currentSelection().selectedText()).isEqualTo("The passage");
    }

    @Test
    void selectionDelete_clearsSelection() {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        RestAssured.given()
                .contentType("application/json")
                .body("{\"side\":\"B\",\"startLine\":1,\"endLine\":3,\"selectedText\":\"Some text\"}")
                .post("/api/debate/" + sessionId + "/selection")
                .then().statusCode(200);

        RestAssured.given()
                .when()
                .delete("/api/debate/" + sessionId + "/selection")
                .then()
                .statusCode(200);

        DebateSession session = registry.find(java.util.UUID.fromString(sessionId)).orElseThrow();
        assertThat(session.currentSelection()).isNull();
    }

    @Test
    void selectionPost_invalidSession_returns404() {
        RestAssured.given()
                .contentType("application/json")
                .body("{\"side\":\"A\",\"startLine\":0,\"endLine\":0,\"selectedText\":\"text\"}")
                .when()
                .post("/api/debate/00000000-0000-0000-0000-000000000000/selection")
                .then()
                .statusCode(404);
    }

    @Test
    void activeSessions_emptyWhenNoDebates() {
        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).isEqualTo("[]");
    }

    @Test
    void initialContextSnapshot_emittedOnConnect() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> received =
                new java.util.concurrent.atomic.AtomicReference<>();

        String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Skip empty lines and event: comments
                        if (line.trim().isEmpty() || line.startsWith("event:")) {
                            continue;
                        }
                        // Look for data: lines
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim(); // Remove "data:" prefix
                            // First non-heartbeat event should be context-usage
                            if (!data.contains("\"type\":\"heartbeat\"")
                                    && data.contains("\"type\":\"context-usage\"")) {
                                received.set(data);
                                latch.countDown();
                                return;
                            }
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // expected when connection closes
            }
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("Initial context-usage event was not received within 5 seconds")
                .isTrue();

        String body = received.get();
        assertThat(body).contains("\"type\":\"context-usage\"");
        assertThat(body).contains("\"windowSizeChars\"");
        assertThat(body).contains("\"serverContributionChars\"");
        assertThat(body).contains("\"messageCount\"");
        assertThat(body).contains("\"effectivePercent\"");
        assertThat(body).contains("\"thresholdExceeded\"");

        sseThread.interrupt();
    }

    @Test
    void pushedContextSnapshot_deliveredViaSse() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        // Raise a point to generate some context
        DebateE2EFixtures.dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Test point content", "P1", "ISOLATED", null);

        java.util.concurrent.CountDownLatch initialLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch pushedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> pushedContext =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<String> allContextUsageEvents =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty() || line.startsWith("event:")) {
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (data.contains("\"type\":\"context-usage\"")) {
                                allContextUsageEvents.add(data);
                                // Initial snapshot has windowSizeChars; pushed does not
                                if (data.contains("\"windowSizeChars\"")) {
                                    initialLatch.countDown();
                                } else if (data.contains("\"agentReportedPercent\":42.0")) {
                                    // This is the pushed snapshot we're waiting for
                                    pushedContext.set(data);
                                    pushedLatch.countDown();
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // expected when connection closes
            }
        });

        // Wait for initial context-usage
        assertThat(initialLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("Initial context-usage was not received within 5 seconds")
                .isTrue();

        // Now push a new context snapshot
        tools.reportContext(sessionId, 42.0);

        // Wait for the pushed context-usage event
        boolean receivedPushed = pushedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // If we didn't receive the pushed snapshot, print all events for debugging
        if (!receivedPushed) {
            System.err.println("All context-usage events received:");
            for (String event : allContextUsageEvents) {
                System.err.println("  " + event);
            }
        }

        assertThat(receivedPushed)
                .as("Pushed context-usage event with agentReportedPercent:42.0 was not received within 5 seconds")
                .isTrue();

        String body = pushedContext.get();
        assertThat(body).contains("\"type\":\"context-usage\"");
        assertThat(body).contains("\"agentReportedPercent\":42.0");
        assertThat(body).contains("\"effectivePercent\"");
        // The pushed snapshot should NOT include windowSizeChars
        assertThat(body).doesNotContain("\"windowSizeChars\"");

        sseThread.interrupt();
    }

    @Test
    void selectionScope_deliveredViaSse() throws Exception {
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        java.util.concurrent.CountDownLatch initialCtxLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch selectionLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> selectionEvent =
                new java.util.concurrent.atomic.AtomicReference<>();

        String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.contains("\"type\":\"heartbeat\"")) continue;
                        if (data.contains("\"type\":\"context-usage\"")) {
                            initialCtxLatch.countDown();
                            continue;
                        }
                        if (data.contains("\"type\":\"selection-scope\"")) {
                            selectionEvent.set(data);
                            selectionLatch.countDown();
                            return;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // expected when connection closes
            }
        });

        assertThat(initialCtxLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("Initial context-usage was not received").isTrue();

        RestAssured.given()
                .contentType("application/json")
                .body("{\"side\":\"A\",\"startLine\":5,\"endLine\":12,\"selectedText\":\"Hello world\"}")
                .post("/api/debate/" + sessionId + "/selection")
                .then().statusCode(200);

        assertThat(selectionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("selection-scope event was not received via SSE").isTrue();

        String body = selectionEvent.get();
        assertThat(body).contains("\"type\":\"selection-scope\"");
        assertThat(body).contains("\"side\":\"A\"");
        assertThat(body).contains("\"startLine\":5");
        assertThat(body).contains("\"endLine\":12");
        assertThat(body).contains("Hello world");

        sseThread.interrupt();
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private String extractPointId(String raiseResult) {
        Matcher m = Pattern.compile("\"pointId\":\"([^\"]+)\"").matcher(raiseResult);
        return m.find() ? m.group(1) : "";
    }
}
