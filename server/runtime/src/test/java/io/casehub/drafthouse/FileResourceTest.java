package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class FileResourceTest {

    @Test
    void readsExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("test.md");
        Files.writeString(file, "# Hello\n\nWorld\n");

        given()
            .queryParam("path", file.toString())
        .when()
            .get("/api/file")
        .then()
            .statusCode(200)
            .contentType(containsString("text/plain"))
            .body(equalTo("# Hello\n\nWorld\n"));
    }

    @Test
    void returns404ForMissingFile() {
        given()
            .queryParam("path", "/tmp/drafthouse-does-not-exist-xyz.md")
        .when()
            .get("/api/file")
        .then()
            .statusCode(404);
    }

    @Test
    void returns400WhenPathMissing() {
        given()
        .when()
            .get("/api/file")
        .then()
            .statusCode(400);
    }
}
