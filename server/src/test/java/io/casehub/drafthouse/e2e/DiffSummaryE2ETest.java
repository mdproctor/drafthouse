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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithPlaywright
class DiffSummaryE2ETest {

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
    void summaryIsNonEmptyAfterLoad() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        assertFalse(text.isBlank(), "diff summary should show counts after loading files with diffs");
    }

    @Test
    void summaryShowsModifiedCount() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        assertTrue(text.contains("~"),
            "summary should contain ~ for modified chunks, got: " + text);
    }

    @Test
    void summaryShowsDeletedCount() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        // The minus sign is U+2212 MINUS SIGN, not a hyphen
        assertTrue(text.contains("−"),
            "summary should contain − for A-only chunks, got: " + text);
    }

    @Test
    void summaryShowsInsertedCount() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        assertTrue(text.contains("+"),
            "summary should contain + for B-only chunks, got: " + text);
    }
}
