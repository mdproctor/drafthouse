package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
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
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.waitForRender;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithPlaywright
class SwapPanelsE2ETest {

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
    void swapButtonEnabledAfterLoad() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-swap")).isEnabled();
    }

    @Test
    void swapRerunsAndProducesDiffChunks() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => document.querySelector('document-diff').swapPanels()");
        waitForRender(page);
        int count = page.locator("[data-diff-chunk]").count();
        assertTrue(count > 0, "diff chunks should be present after swap");
    }

    @Test
    void afterSwapAsideShowsOriginalBContent() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => document.querySelector('document-diff').swapPanels()");
        waitForRender(page);
        String textA = page.locator("#render-a").innerText();
        assertTrue(textA.contains("New Section"),
            "after swap, panel A should contain text from original B (New Section)");
    }

    @Test
    void swapTogglesBackToOriginal() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => document.querySelector('document-diff').swapPanels()");
        waitForRender(page);
        page.evaluate("() => document.querySelector('document-diff').swapPanels()");
        waitForRender(page);
        String restoredTextA = page.locator("#render-a").innerText();
        assertTrue(restoredTextA.contains("Summary"),
            "after double swap, panel A should be restored (contains 'Summary')");
    }
}
