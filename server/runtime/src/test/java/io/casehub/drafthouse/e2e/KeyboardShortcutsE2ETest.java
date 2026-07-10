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
class KeyboardShortcutsE2ETest {

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
    void questionMark_togglesOverlay() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Initially hidden
        assertThat(page.locator("#shortcuts-overlay")).isHidden();
        assertThat(page.locator("#shortcuts-backdrop")).isHidden();

        // Press ? to show
        page.keyboard().press("?");
        assertThat(page.locator("#shortcuts-overlay")).isVisible();
        assertThat(page.locator("#shortcuts-backdrop")).isVisible();
        assertThat(page.locator("#shortcuts-overlay")).containsText("Keyboard Shortcuts");
        assertThat(page.locator("#shortcuts-overlay")).containsText("Next diff");

        // Press Escape to hide
        page.keyboard().press("Escape");
        assertThat(page.locator("#shortcuts-overlay")).isHidden();
        assertThat(page.locator("#shortcuts-backdrop")).isHidden();
    }

    @Test
    void questionMark_dismissedByClickingBackdrop() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Show overlay
        page.keyboard().press("?");
        assertThat(page.locator("#shortcuts-overlay")).isVisible();

        // Click backdrop at a corner — the overlay card covers the centre
        page.locator("#shortcuts-backdrop").click(
                new com.microsoft.playwright.Locator.ClickOptions().setPosition(5, 5));
        assertThat(page.locator("#shortcuts-overlay")).isHidden();
        assertThat(page.locator("#shortcuts-backdrop")).isHidden();
    }

    @Test
    void shortcutKeys_suppressedInInput() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Focus an input inside the diff panel (the file path input)
        // Access shadow DOM via evaluateHandle
        page.evaluate("document.querySelector('document-diff').shadowRoot.querySelector('.panel-label').focus()");

        // Press ? — overlay should NOT appear
        page.keyboard().press("?");
        assertThat(page.locator("#shortcuts-overlay")).isHidden();

        // Blur the input
        page.evaluate("document.querySelector('document-diff').shadowRoot.querySelector('.panel-label').blur()");

        // Now ? should work
        page.keyboard().press("?");
        assertThat(page.locator("#shortcuts-overlay")).isVisible();
    }
}
