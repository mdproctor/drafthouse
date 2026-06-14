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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithPlaywright
class ScrollSyncE2ETest {

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
    void anchorModeBuildsInteriorAnchors() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // diff-a/b has 5 matching headings → at least 7 anchors (start + 5 headings + end)
        int anchorCount = (int) page.evaluate("() => document.querySelector('drafthouse-diff')._scrollAnchors.length");
        assertTrue(anchorCount >= 7,
            "expected 7+ anchors (start + 5 heading matches + end), got " + anchorCount);
    }

    @Test
    void anchorModeScrollsPanelB() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // Scroll panel A to 300px; sync should move panel B
        page.evaluate("() => { document.querySelector('drafthouse-diff').shadowRoot.getElementById('body-a').scrollTop = 300; }");
        page.waitForTimeout(200); // allow two rAF cycles for syncing flag to reset
        int scrollB = (int) page.evaluate("() => document.querySelector('drafthouse-diff').shadowRoot.getElementById('body-b').scrollTop");
        assertTrue(scrollB > 0, "panel B should have scrolled when panel A was scrolled");
    }

    @Test
    void noHeadingsProducesOnlyEndpointAnchors() {
        loadFilePair(page, index, fixturePath("no-headings-a.md"), fixturePath("no-headings-b.md"));
        // No heading matches → only start {a:0,b:0} and end {a:maxA,b:maxB} anchors
        int anchorCount = (int) page.evaluate("() => document.querySelector('drafthouse-diff')._scrollAnchors.length");
        assertEquals(2, anchorCount,
            "expected exactly 2 anchors (start + end) with no heading matches");
    }

    @Test
    void noHeadingsModeStillScrollsPanelB() {
        loadFilePair(page, index, fixturePath("no-headings-a.md"), fixturePath("no-headings-b.md"));
        page.evaluate("() => { document.querySelector('drafthouse-diff').shadowRoot.getElementById('body-a').scrollTop = 300; }");
        page.waitForTimeout(200);
        int scrollB = (int) page.evaluate("() => document.querySelector('drafthouse-diff').shadowRoot.getElementById('body-b').scrollTop");
        assertTrue(scrollB > 0, "panel B should scroll even when sync uses only endpoint anchors");
    }
}
