package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;

@QuarkusTest
@WithPlaywright
class UnifiedDiffE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    private Page page;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
    }

    @Test
    void toggleButton_rendersInTopbar() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-view-mode")).isVisible();
        assertThat(page.locator("#btn-view-mode")).hasText("⫏ Split");
    }

    @Test
    void clickToggle_switchesToUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Panel B and divider hidden
        assertThat(page.locator("document-diff").locator("#panel-b")).not().isVisible();
        assertThat(page.locator("document-diff").locator("#divider")).not().isVisible();

        // Unified diff blocks visible in panel A
        assertThat(page.locator("document-diff").locator(".diff-unified-del").first()).isVisible();
        assertThat(page.locator("document-diff").locator(".diff-unified-ins").first()).isVisible();

        // Button label updated
        assertThat(page.locator("#btn-view-mode")).hasText("☰ Unified");
    }

    @Test
    void clickToggleTwice_restoresSplitMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Switch to unified
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("document-diff").locator("#panel-b")).not().isVisible();

        // Switch back to split
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("document-diff").locator("#panel-b")).isVisible();
        assertThat(page.locator("document-diff").locator("#divider")).isVisible();

        // Split-mode diff annotations restored
        assertThat(page.locator("document-diff").locator("#render-a [data-diff-chunk]").first()).isVisible();
        assertThat(page.locator("document-diff").locator("#render-b [data-diff-chunk]").first()).isVisible();

        // Button label restored
        assertThat(page.locator("#btn-view-mode")).hasText("⫏ Split");
    }

    @Test
    void wordHighlights_visibleInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Word-level marks inside unified containers
        Locator delMarks = page.locator("document-diff").locator(".diff-unified-del mark.diff-word-a");
        Locator insMarks = page.locator("document-diff").locator(".diff-unified-ins mark.diff-word-b");
        assertThat(delMarks.first()).isVisible();
        assertThat(insMarks.first()).isVisible();
    }

    @Test
    void diffNavigation_worksInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Next diff button enabled
        assertThat(page.locator("#btn-next")).isEnabled();

        // Click next — counter updates
        page.locator("#btn-next").click();
        String counter = page.locator("#diff-counter").textContent();
        org.junit.jupiter.api.Assertions.assertTrue(
                counter.matches("\\d+ / \\d+"),
                "Counter should show position: " + counter);
    }

    @Test
    void modeRoundTrip_splitUnifiedSplit_annotationsCorrect() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Start in split — verify annotations
        assertThat(page.locator("document-diff").locator("#render-a [data-diff-chunk]").first()).isVisible();
        assertThat(page.locator("document-diff").locator("#render-b [data-diff-chunk]").first()).isVisible();

        // Switch to unified
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("document-diff").locator(".diff-unified-del").first()).isVisible();

        // Switch back to split
        page.locator("#btn-view-mode").click();

        // Verify split annotations are correct (not stale unified DOM)
        assertThat(page.locator("document-diff").locator("#render-a .diff-del").first()).isVisible();
        assertThat(page.locator("document-diff").locator("#render-b .diff-ins").first()).isVisible();

        // No unified blocks should remain
        assertThat(page.locator("document-diff").locator(".diff-unified-del")).hasCount(0);
        assertThat(page.locator("document-diff").locator(".diff-unified-ins")).hasCount(0);
    }

    @Test
    void keyboardShortcut_u_togglesMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.keyboard().press("u");

        // Should be in unified mode
        assertThat(page.locator("document-diff").locator("#panel-b")).not().isVisible();
        assertThat(page.locator("#btn-view-mode")).hasText("☰ Unified");
    }

    @Test
    void swapPanels_worksInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Swap should not error — unified view re-renders
        page.locator("#btn-swap").click();

        // Unified diff blocks still visible after swap
        assertThat(page.locator("document-diff").locator(".diff-unified-del").first()).isVisible();
        assertThat(page.locator("document-diff").locator(".diff-unified-ins").first()).isVisible();
    }
}
