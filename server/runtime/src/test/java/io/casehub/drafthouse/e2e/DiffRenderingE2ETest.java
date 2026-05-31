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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithPlaywright
class DiffRenderingE2ETest {

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
    void diffChunksAnnotated() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("[data-diff-chunk]").count();
        assertTrue(count > 0, "expected at least one diff-chunk annotation");
    }

    @Test
    void deletedBlockOnAside() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-a .diff-del").count();
        assertTrue(count > 0, "expected at least one .diff-del block in panel A");
    }

    @Test
    void insertedBlockOnBside() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-b .diff-ins").count();
        assertTrue(count > 0, "expected at least one .diff-ins block in panel B");
    }

    @Test
    void minimapCanvasHasNonZeroDimensions() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#diff-map")).isVisible();
        int width  = (int) page.locator("#diff-map").evaluate("el => el.width");
        int height = (int) page.locator("#diff-map").evaluate("el => el.height");
        assertTrue(width  > 0, "minimap canvas width should be > 0");
        assertTrue(height > 0, "minimap canvas height should be > 0");
    }
}
