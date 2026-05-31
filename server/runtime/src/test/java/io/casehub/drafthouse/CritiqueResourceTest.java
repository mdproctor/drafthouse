package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class CritiqueResourceTest {
    @Test
    void critiqueReturns501() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/critique")
        .then()
            .statusCode(501);
    }
}
