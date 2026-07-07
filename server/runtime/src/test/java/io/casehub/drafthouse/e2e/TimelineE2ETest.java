package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the {@code <drafthouse-timeline>} panel.
 *
 * <p>Covers timeline rendering after workspace replay, click navigation between
 * snapshots (verifying diff panel content updates), and cross-panel interaction
 * where a review tracker click highlights the corresponding timeline marker.
 *
 * <h3>Fixture setup</h3>
 * A temporary git repo is created in {@code @BeforeAll} with three commits
 * representing an initial spec and two rounds of fixes. A workspace directory
 * is constructed with {@code .source-dirs}, {@code .spec-path}, {@code tracker.md},
 * and response files referencing those commit hashes. The {@code load_workspace}
 * MCP tool replays this workspace once, producing {@code ROUND_SNAPSHOT} entries
 * that drive the timeline panel.
 *
 * <h3>Session sharing</h3>
 * The workspace is loaded once and the session is shared across all tests that
 * need it. This avoids re-replaying into the same channel (which would duplicate
 * messages). The {@code timeline_hidden_when_no_snapshots} test uses its own
 * independent debate session.
 */
@QuarkusTest
@WithPlaywright
class TimelineE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;
    @Inject DebateSessionRegistry registry;

    private Page page;

    /** Shared workspace session — loaded once, never ended between tests. */
    private static String workspaceSessionId;
    /** Per-test session for non-workspace tests. */
    private String ownSessionId;

    private static Path tempDir;
    private static Path gitRepoDir;
    private static Path workspaceDir;
    private static String specFilePath;
    private static String commit0Hash; // initial
    private static String commit1Hash; // round 1 fix
    private static String commit2Hash; // round 2 fix

    // ── Fixture lifecycle ───────────────────────────────────────────────

    @BeforeAll
    static void createFixtureWorkspace() throws Exception {
        tempDir = Files.createTempDirectory("timeline-e2e-");

        // 1. Create a small git repo with spec file at three commits
        gitRepoDir = tempDir.resolve("project-repo");
        Files.createDirectories(gitRepoDir);

        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");

        Path specFile = gitRepoDir.resolve("spec.md");
        specFilePath = specFile.toAbsolutePath().toString();

        // Commit 0 — original document
        Files.writeString(specFile,
                "# Design Spec\n\n## Section 1\n\nOriginal content for section one.\n\n"
                + "## Section 2\n\nOriginal content for section two.\n");
        git("add", "spec.md");
        git("commit", "-m", "initial spec");
        commit0Hash = gitOutput("rev-parse", "HEAD").trim();

        // Commit 1 — round 1 fix (error handling added)
        Files.writeString(specFile,
                "# Design Spec\n\n## Section 1\n\nUpdated content with error handling for section one.\n\n"
                + "## Section 2\n\nOriginal content for section two.\n\n"
                + "## Section 3\n\nNew section added in round 1.\n");
        git("add", "spec.md");
        git("commit", "-m", "round 1 fix: add error handling");
        commit1Hash = gitOutput("rev-parse", "HEAD").trim();

        // Commit 2 — round 2 fix (test coverage added)
        Files.writeString(specFile,
                "# Design Spec\n\n## Section 1\n\nUpdated content with error handling for section one.\n\n"
                + "## Section 2\n\nUpdated section two with test coverage notes.\n\n"
                + "## Section 3\n\nNew section added in round 1.\n\n"
                + "## Section 4\n\nTest coverage documentation added in round 2.\n");
        git("add", "spec.md");
        git("commit", "-m", "round 2 fix: add test coverage");
        commit2Hash = gitOutput("rev-parse", "HEAD").trim();

        // 2. Create workspace directory with a unique name (channel name derives from dir name)
        workspaceDir = tempDir.resolve("workspace-" + System.nanoTime());
        Files.createDirectories(workspaceDir.resolve("responses"));

        // .spec-path — absolute path to the spec file in the git repo
        Files.writeString(workspaceDir.resolve(".spec-path"), specFilePath);

        // .source-dirs — absolute path to the git repo
        Files.writeString(workspaceDir.resolve(".source-dirs"),
                gitRepoDir.toAbsolutePath().toString());

        // .mode
        Files.writeString(workspaceDir.resolve(".mode"), "spec-review");

        // tracker.md — references commit hashes from the git repo
        Files.writeString(workspaceDir.resolve("tracker.md"),
                "# Design Review Tracker\n\n"
                + "Spec: spec.md | Project: timeline-test\n"
                + "Started: 2026-07-07 | Current round: 2\n\n"
                + "## Issues\n\n"
                + "### R1-01: Missing error handling\n"
                + "- **Raised:** Round 1\n"
                + "- **Status:** VERIFIED\n"
                + "- **Spec commit:** " + commit0Hash.substring(0, 7) + " → " + commit1Hash + "\n\n"
                + "### R2-01: Test coverage below threshold\n"
                + "- **Raised:** Round 2\n"
                + "- **Status:** VERIFIED\n"
                + "- **Spec commit:** " + commit1Hash.substring(0, 7) + " → " + commit2Hash + "\n");

        // responses/reviewer-1.md
        Files.writeString(workspaceDir.resolve("responses/reviewer-1.md"),
                "# Round 1 — Reviewer\n\n## Overview\n\nReview findings.\n\n---\n\n"
                + "### R1-01: Missing error handling\n\n"
                + "The parser does not handle malformed input in §1.\n\n"
                + "SIGNAL: CONTINUE\n");

        // responses/implementor-1.md
        Files.writeString(workspaceDir.resolve("responses/implementor-1.md"),
                "# Round 1 — Implementor\n\n"
                + "### R1-01: FIXED\n\n"
                + "Added error handling.\n\n"
                + "SIGNAL: CONTINUE\n");

        // responses/reviewer-2.md
        Files.writeString(workspaceDir.resolve("responses/reviewer-2.md"),
                "# Round 2 — Reviewer\n\n"
                + "## Addressed Items\n\n"
                + "- R1-01: resolved — error handling added\n\n"
                + "## New Issues\n\n"
                + "### R2-01: Test coverage below threshold\n\n"
                + "Unit test coverage is too low.\n\n"
                + "SIGNAL: APPROVED\n");

        // responses/implementor-2.md
        Files.writeString(workspaceDir.resolve("responses/implementor-2.md"),
                "# Round 2 — Implementor\n\n"
                + "### R2-01: FIXED\n\n"
                + "Added tests. Coverage now at 87%.\n\n"
                + "SIGNAL: APPROVED\n");
    }

    @AfterAll
    static void cleanupFixture() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        // Only end sessions we own — not the shared workspace session
        if (ownSessionId != null) {
            try { tools.endDebate(ownSessionId, false); } catch (Exception ignored) {}
            ownSessionId = null;
        }
        if (page != null) page.close();
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    void timeline_renders_markers_after_workspace_replay() {
        String sid = ensureWorkspaceLoaded();
        navigateWithDebate(sid);

        // Wait for timeline markers inside shadow DOM
        waitForTimelineMarkers(3);

        // Assert correct number of markers (round 0 original + round 1 fix + round 2 fix)
        int markerCount = countTimelineMarkers();
        assertEquals(3, markerCount,
                "Timeline should have 3 markers (original + 2 fix rounds)");

        // Assert connectors exist between markers
        int connectorCount = countTimelineConnectors();
        assertEquals(2, connectorCount,
                "Timeline should have 2 connectors between 3 markers");
    }

    @Test
    void clicking_marker_updates_diff_panel() {
        String sid = ensureWorkspaceLoaded();
        navigateWithDebate(sid);
        waitForTimelineMarkers(3);

        // Timeline auto-selects last two markers. Wait for diff panel content.
        page.waitForTimeout(1500);

        // Click the first marker (round 0) — this should change diff selection
        clickTimelineMarker(0);
        page.waitForTimeout(2000);

        // The diff panel should have loaded content from snapshots
        String contentA = diffPanelRenderedText("a");
        assertNotNull(contentA, "Panel A should have content after clicking timeline marker");
        assertTrue(contentA.contains("Design Spec"),
                "Panel A should show spec content from a timeline snapshot");
    }

    @Test
    void tracker_click_highlights_timeline_marker() {
        String sid = ensureWorkspaceLoaded();
        navigateWithDebate(sid);
        waitForTimelineMarkers(3);

        // Wait for tracker points to appear (workspace has issues with VERIFIED status)
        DebateE2EFixtures.waitForTrackerPoints(page, 1);

        // Click the first tracker point
        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);

        // Verify a timeline marker got trail-fix class
        Boolean hasTrailFix = (Boolean) page.evaluate("() => {"
                + "const timeline = document.querySelector('drafthouse-timeline');"
                + "if (!timeline || !timeline.shadowRoot) return false;"
                + "return timeline.shadowRoot.querySelector('.marker.trail-fix') !== null;"
                + "}");
        assertTrue(hasTrailFix,
                "Clicking a tracker point should highlight the fix round in the timeline");
    }

    @Test
    void snapshot_endpoint_returns_content() {
        String sid = ensureWorkspaceLoaded();
        navigateWithDebate(sid);
        waitForTimelineMarkers(3);

        // Verify the snapshot endpoint directly — fetch snapshot 0 content
        String content = (String) page.evaluate("(sessionId) => {"
                + "return fetch('/api/debate/' + sessionId + '/snapshot/0')"
                + "  .then(r => r.ok ? r.text() : null);"
                + "}", sid);
        assertNotNull(content, "Snapshot 0 should return content");
        assertTrue(content.contains("Design Spec"),
                "Snapshot 0 content should contain spec heading");
        assertTrue(content.contains("Original content"),
                "Snapshot 0 (initial commit) should contain original content");
    }

    @Test
    void timeline_hidden_when_no_snapshots() {
        // Start a regular debate session (no workspace replay → no ROUND_SNAPSHOT entries)
        ownSessionId = DebateE2EFixtures.startDebateSession(tools);
        DebateE2EFixtures.loadWithDebate(page, index, ownSessionId);

        // The timeline should be hidden when no snapshots exist
        Boolean isHidden = (Boolean) page.evaluate("() => {"
                + "const timeline = document.querySelector('drafthouse-timeline');"
                + "if (!timeline) return true;"
                + "return timeline.classList.contains('hidden');"
                + "}");
        assertTrue(isHidden, "Timeline should be hidden when no ROUND_SNAPSHOT entries exist");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Loads the workspace once and caches the session ID. Subsequent calls
     * return the cached ID — the {@code load_workspace} idempotency check
     * recognises the existing channel + session.
     */
    private String ensureWorkspaceLoaded() {
        if (workspaceSessionId != null) {
            // Verify session still exists (might have been cleaned up)
            var existing = registry.find(java.util.UUID.fromString(workspaceSessionId));
            if (existing.isPresent()) return workspaceSessionId;
        }
        String result = tools.loadWorkspace(workspaceDir.toAbsolutePath().toString());
        String sid = DebateE2EFixtures.extractSessionId(result);
        assertFalse(sid.isBlank(), "loadWorkspace should return a sessionId, got: " + result);
        workspaceSessionId = sid;
        return sid;
    }

    private void navigateWithDebate(String sid) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + a + "&b=" + b + "&debate=" + sid);
        PlaywrightFixtures.waitForRender(page);
    }

    private void waitForTimelineMarkers(int expectedCount) {
        page.waitForFunction("(count) => {"
                        + "const timeline = document.querySelector('drafthouse-timeline');"
                        + "if (!timeline || !timeline.shadowRoot) return false;"
                        + "return timeline.shadowRoot.querySelectorAll('.marker').length >= count;"
                        + "}",
                expectedCount,
                new Page.WaitForFunctionOptions().setTimeout(10000));
    }

    private int countTimelineMarkers() {
        return (int) page.evaluate("() => {"
                + "const timeline = document.querySelector('drafthouse-timeline');"
                + "if (!timeline || !timeline.shadowRoot) return 0;"
                + "return timeline.shadowRoot.querySelectorAll('.marker').length;"
                + "}");
    }

    private int countTimelineConnectors() {
        return (int) page.evaluate("() => {"
                + "const timeline = document.querySelector('drafthouse-timeline');"
                + "if (!timeline || !timeline.shadowRoot) return 0;"
                + "return timeline.shadowRoot.querySelectorAll('.connector').length;"
                + "}");
    }

    private void clickTimelineMarker(int index) {
        page.evaluate("(idx) => {"
                + "const timeline = document.querySelector('drafthouse-timeline');"
                + "if (!timeline || !timeline.shadowRoot) return;"
                + "const markers = timeline.shadowRoot.querySelectorAll('.marker');"
                + "if (markers[idx]) markers[idx].click();"
                + "}", index);
    }

    private String diffPanelRenderedText(String panel) {
        return (String) page.evaluate("(p) => {"
                + "const diff = document.querySelector('drafthouse-diff');"
                + "if (!diff || !diff.shadowRoot) return null;"
                + "const el = diff.shadowRoot.getElementById('render-' + p);"
                + "return el ? el.textContent : null;"
                + "}", panel);
    }

    // ── Git command helpers ──────────────────────────────────────────────

    private static void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gitRepoDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed: " + output);
        }
    }

    private static String gitOutput(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gitRepoDir.toFile());
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed (exit " + exit + ")");
        }
        return output;
    }
}
