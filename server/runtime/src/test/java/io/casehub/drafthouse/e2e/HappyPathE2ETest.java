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
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@WithPlaywright
class HappyPathE2ETest {

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
    void panelARendersContent() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#render-a")).not().isEmpty();
    }

    @Test
    void panelBRendersContent() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#render-b")).not().isEmpty();
    }

    @Test
    void topbarIsVisible() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#topbar")).isVisible();
        assertThat(page.locator("#btn-sync")).isVisible();
        assertThat(page.locator("#btn-swap")).isVisible();
    }

    @Test
    void pageTitleIsDraftHouse() {
        page.navigate(index.toString());
        assertEquals("DraftHouse", page.title(), "page title should be DraftHouse");
    }
}
