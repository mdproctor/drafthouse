package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@WithPlaywright
class WorkspaceReplayE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;

    private Page page;
    private static String sharedSessionId;

    @BeforeEach
    void setUp() {
        page = context.newPage();

        // Load workspace once for all tests — idempotent per test class
        if (sharedSessionId == null) {
            String wsPath = Path.of("src/test/resources/fixtures/workspace-replay")
                    .toAbsolutePath().toString();
            String result = tools.loadWorkspace(wsPath);
            assertFalse(result.startsWith("error:"), result);
            sharedSessionId = DebateE2EFixtures.extractSessionId(result);
            assertFalse(sharedSessionId.isBlank(), "session ID should not be blank");
        }
    }

    @AfterEach
    void tearDown() {
        if (page != null) page.close();
    }

    @Test
    void replay_shows_issues_in_tracker() {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + a + "&b=" + b + "&debate=" + sharedSessionId);
        PlaywrightFixtures.waitForRender(page);

        DebateE2EFixtures.waitForTrackerPoints(page, 4);

        Locator points = page.locator("review-tracker .point-item");
        assertTrue(points.count() >= 4, "should show at least 4 review points");
    }

    @Test
    void replay_shows_verified_status_icon() {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + a + "&b=" + b + "&debate=" + sharedSessionId);
        PlaywrightFixtures.waitForRender(page);

        DebateE2EFixtures.waitForTrackerPoints(page, 4);

        Locator verifiedIcons = page.locator("review-tracker .point-icon:text('✓✓')");
        assertTrue(verifiedIcons.count() >= 1, "should show at least one VERIFIED icon");
    }

    @Test
    void replay_shows_debate_entries() {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + a + "&b=" + b + "&debate=" + sharedSessionId);
        PlaywrightFixtures.waitForRender(page);

        DebateE2EFixtures.waitForDebateEntries(page, 1);

        Locator entries = page.locator("channel-feed .entry");
        assertTrue(entries.count() >= 4, "should show debate conversation entries");
    }
}
