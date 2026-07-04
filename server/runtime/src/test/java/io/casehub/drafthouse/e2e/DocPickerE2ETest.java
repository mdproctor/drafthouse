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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.DebateE2EFixtures.startDebateSession;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;

@QuarkusTest
@WithPlaywright
class DocPickerE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;

    private Page page;
    private String sessionId;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    @Test
    void dropdown_shows_documents_and_reassigns_comparison() {
        // Setup: create debate with 3 documents and initial comparison
        sessionId = startDebateSession(tools);
        String pathA = fixturePath("diff-a.md");
        String pathB = fixturePath("diff-b.md");
        String pathC = fixturePath("diff-c.md");

        tools.addDocument(sessionId, pathA, "doc-a");
        tools.addDocument(sessionId, pathB, "doc-b");
        tools.addDocument(sessionId, pathC, "doc-c");
        tools.setComparison(sessionId, pathA, pathB);

        // Navigate with debate session
        String encodedA = URLEncoder.encode(pathA, StandardCharsets.UTF_8);
        String encodedB = URLEncoder.encode(pathB, StandardCharsets.UTF_8);
        page.navigate(index + "?a=" + encodedA + "&b=" + encodedB + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);

        // Verify badge is visible with count 4 (spec + 3 added documents)
        Locator picker = page.locator("drafthouse-doc-picker");
        Locator badge = picker.locator(".badge");
        assertThat(badge).containsText("4");

        // Click badge — dropdown appears
        badge.click();
        Locator dropdown = picker.locator(".dropdown");
        assertThat(dropdown).isVisible();

        // Verify 4 document rows
        Locator rows = picker.locator(".doc-row");
        assertThat(rows).hasCount(4);

        // Verify current A assignment is highlighted
        Locator activeA = picker.locator(".slot-btn.active");
        assertThat(activeA.first()).hasText("A");

        // Click A on doc-c to reassign — wait for diff viewer to update
        Locator docCRow = rows.filter(new Locator.FilterOptions().setHasText("doc-c"));
        docCRow.locator(".slot-btn", new Locator.LocatorOptions().setHasText("A")).click();

        // Wait for comparison-changed event to propagate — the diff viewer
        // re-renders with the new file, producing new [data-diff-chunk] elements
        page.locator("[data-diff-chunk]").first().waitFor();

        // Verify the A button on doc-c is now active
        Locator docCActiveA = docCRow.locator(".slot-btn.active");
        assertThat(docCActiveA).hasText("A");

        // Click outside dropdown — should close
        page.locator("#topbar").click(new Locator.ClickOptions().setPosition(1, 1));
        assertThat(dropdown).isHidden();
    }
}
