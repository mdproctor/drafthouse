package io.casehub.drafthouse.debate;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
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

    private ReviewSession stubSession(int round) {
        return new ReviewSession(
                "drafthouse-20260602-abc123",
                "/tmp/test-session",
                "/path/to/spec.md",
                round,
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()),
                0
        );
    }

    @Test
    void startSessionReturns200WithSessionId() {
        Mockito.when(sessionService.startSession(anyString())).thenReturn(stubSession(0));

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
    void nextRoundReturns200WithRoundNumber() {
        Mockito.when(sessionService.executeNextRound("drafthouse-20260602-abc123"))
               .thenReturn(stubSession(1));

        given()
        .when()
            .post("/api/review/sessions/drafthouse-20260602-abc123/next-round")
        .then()
            .statusCode(200)
            .body("roundNumber", equalTo(1));
    }
}
