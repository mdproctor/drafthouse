package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class PingResourceTest {
    @Test
    void pingReturnsOk() {
        given().when().get("/api/ping")
               .then().statusCode(200).body("ok", is(true));
    }
}
