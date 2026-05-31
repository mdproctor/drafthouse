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

@QuarkusTest
@WithPlaywright
class DiffLegendE2ETest {

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
    void legendIsVisible() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#diff-legend")).isVisible();
    }

    @Test
    void legendShowsAsideColour() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator(".legend-del")).isVisible();
    }

    @Test
    void legendShowsBsideColour() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator(".legend-ins")).isVisible();
    }
}
