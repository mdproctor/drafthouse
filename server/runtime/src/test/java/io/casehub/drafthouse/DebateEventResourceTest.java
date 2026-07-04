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

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private String extractPointId(String raiseResult) {
        Matcher m = Pattern.compile("\"pointId\":\"([^\"]+)\"").matcher(raiseResult);
        return m.find() ? m.group(1) : "";
    }
}
