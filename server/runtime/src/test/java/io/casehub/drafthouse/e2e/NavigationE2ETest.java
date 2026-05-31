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
class NavigationE2ETest {

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
    void navButtonsEnabledAfterLoad() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-next")).isEnabled();
        assertThat(page.locator("#btn-prev")).isEnabled();
    }

    @Test
    void nextButtonUpdatesCounter() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-next").click();
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "counter should show '1 / N' after first next click, got: " + counter);
    }

    @Test
    void nKeyNavigatesToNextDiff() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.keyboard().press("n");
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "n key should navigate to first diff, counter was: " + counter);
    }

    @Test
    void pKeyNavigatesToPreviousDiff() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // Navigate forward twice, then back once → should be at chunk 1.
        // Assumes n+n scrolls far enough that p finds the previous chunk by index
        // (not the viewport-centre fallback in prevDiff). Fixtures are long enough
        // that both n presses scroll past chunk 0, making p reliably land on chunk 1.
        page.keyboard().press("n");
        page.keyboard().press("n");
        page.keyboard().press("p");
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "p key after two n presses should go back to 1, counter was: " + counter);
    }
}
