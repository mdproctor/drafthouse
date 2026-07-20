package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.DebateMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LoadWorkspaceTest {

    @Inject DebateMcpTools tools;

    @Test
    void load_workspace_returns_summary() {
        String path = Path.of("src/test/resources/fixtures/workspace-replay")
                .toAbsolutePath().toString();
        String result = tools.loadWorkspace(path);

        assertFalse(result.startsWith("error:"), "should not be an error: " + result);
        assertTrue(result.contains("debateSessionId"), "should contain session ID: " + result);
        // Either a fresh load (with entryCount) or already_loaded response is valid
        assertTrue(result.contains("entryCount") || result.contains("already_loaded"),
                "should contain entryCount or already_loaded: " + result);
    }

    @Test
    void load_workspace_idempotent() {
        String path = Path.of("src/test/resources/fixtures/workspace-replay")
                .toAbsolutePath().toString();

        String first = tools.loadWorkspace(path);
        String second = tools.loadWorkspace(path);

        assertFalse(first.startsWith("error:"));
        assertFalse(second.startsWith("error:"));
    }

    @Test
    void load_workspace_invalid_path() {
        String result = tools.loadWorkspace("/nonexistent/workspace");
        assertTrue(result.startsWith("error:"));
    }

    @Test
    void load_workspace_missing_responses_dir() {
        String path = Path.of("src/test/resources/fixtures")
                .toAbsolutePath().toString();
        String result = tools.loadWorkspace(path);
        assertTrue(result.startsWith("error:"));
    }

    @Test
    void load_workspace_detects_in_progress_review() {
        String path = Path.of("src/test/resources/fixtures/workspace-watching")
                          .toAbsolutePath().toString();
        String result = tools.loadWorkspace(path);

        assertFalse(result.startsWith("error:"), "should not be error: " + result);
        assertTrue(result.contains("\"status\":\"watching\"")
                   || result.contains("\"status\":\"already_watching\""),
                   "in-progress workspace should get watching status: " + result);
    }

}
