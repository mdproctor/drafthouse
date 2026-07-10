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

import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.waitForRender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithPlaywright
class WordDiffE2ETest {

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
    void changedWordsHighlightedInPanelA() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-a mark.diff-word-a").count();
        assertTrue(count > 0, "expected at least one word highlight in panel A");
    }

    @Test
    void changedWordsHighlightedInPanelB() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-b mark.diff-word-b").count();
        assertTrue(count > 0, "expected at least one word highlight in panel B");
    }

    @Test
    void preBlocksHaveNoWordHighlights() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-a pre mark, #render-b pre mark").count();
        assertEquals(0, count, "pre blocks must not contain word highlights");
    }

    @Test
    void wordHighlightsPersistAfterSwap() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => document.querySelector('document-diff').swapPanels()");
        waitForRender(page);
        int countA = page.locator("#render-a mark.diff-word-a").count();
        int countB = page.locator("#render-b mark.diff-word-b").count();
        assertTrue(countA + countB > 0, "word highlights should reappear after swap");
        page.evaluate("() => document.querySelector('document-diff').swapPanels()"); // restore
    }
}
