package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;

import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@WithPlaywright
class SectionHighlightVisualTest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;
    @Inject MessageService messageService;

    private Page page;
    private String sessionId;

    private static final String SCREENSHOT_DIR = "target/visual-debug";

    @BeforeEach
    void openPage() {
        page = context.newPage();
        page.setViewportSize(1400, 900);
        new java.io.File(SCREENSHOT_DIR).mkdirs();
    }

    @AfterEach
    void closePage() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    @Test
    void visualInspection_eachClickHighlightsCorrectly() {
        sessionId = startDebateSession(tools);
        String[] locations = {"Core Concepts", "Rules", "Working Memory", "Why Use One?", "Further Reading"};
        for (String loc : locations) {
            dispatchRaise(tools, messageService, sessionId, "REV", 1,
                    "Concern about " + loc + ".", "MEDIUM", "ISOLATED", loc);
        }
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, locations.length);

        screenshot("00-initial");

        var points = page.locator("drafthouse-review-tracker .point-item");

        for (int i = 0; i < locations.length; i++) {
            // Dump state BEFORE click
            String preState = dumpState(i, "pre");

            points.nth(i).click();
            page.waitForTimeout(600);

            // Dump state AFTER click
            String postState = dumpState(i, "post");
            screenshot(String.format("click-%d-%s", i, locations[i].replace(" ", "_")));

            System.out.println("=== Click " + i + " (" + locations[i] + ") ===");
            System.out.println(preState);
            System.out.println(postState);

            // Assert
            assertThat(isPointNthSelected(i))
                    .as("Click %d (%s): point selected in tracker", i, locations[i])
                    .isTrue();
            assertThat(countHighlightBars())
                    .as("Click %d (%s): highlight bars in diff", i, locations[i])
                    .isGreaterThan(0);
        }
    }

    private void loadWithSampleDocs(Page page, URL base, String sessionId) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("sample-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("sample-b.md"), StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + a + "&b=" + b + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);
    }

    @SuppressWarnings("unchecked")
    private String dumpState(int clickIndex, String phase) {
        Object result = page.evaluate("(args) => {"
                + "const tracker = document.querySelector('drafthouse-review-tracker');"
                + "const diff = document.querySelector('drafthouse-diff');"
                + "const trackerShadow = tracker.shadowRoot;"
                + "const diffShadow = diff.shadowRoot;"
                + ""
                + "const items = [...trackerShadow.querySelectorAll('.point-item')];"
                + "const points = items.map((el, i) => ({"
                + "  index: i,"
                + "  selected: el.classList.contains('selected'),"
                + "  location: el.querySelector('.point-location')?.textContent || null,"
                + "  summary: el.querySelector('.point-summary')?.textContent?.substring(0, 50) || null"
                + "}));"
                + ""
                + "const bars = diffShadow.querySelectorAll('.section-highlight-bar').length;"
                + "const flashes = diffShadow.querySelectorAll('.scroll-target').length;"
                + ""
                + "const headingsA = [...diffShadow.querySelectorAll('#render-a h1, #render-a h2, #render-a h3, #render-a h4')]"
                + "  .map(h => ({tag: h.tagName, text: h.textContent}));"
                + ""
                + "return {"
                + "  phase: args.phase,"
                + "  clickIndex: args.clickIndex,"
                + "  bars: bars,"
                + "  flashes: flashes,"
                + "  points: points,"
                + "  headingsA: headingsA"
                + "};"
                + "}", Map.of("phase", phase, "clickIndex", clickIndex));
        return result.toString();
    }

    private void screenshot(String name) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get(SCREENSHOT_DIR, name + ".png"))
                .setFullPage(false));
    }

    @Test
    void revisitingSameHeading_flashAndBarStillWork() {
        sessionId = startDebateSession(tools);
        String[] locations = {"Core Concepts", "Rules", "Working Memory", "Why Use One?", "Further Reading"};
        for (String loc : locations) {
            dispatchRaise(tools, messageService, sessionId, "REV", 1,
                    "Concern about " + loc + ".", "MEDIUM", "ISOLATED", loc);
        }
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, locations.length);

        var points = page.locator("drafthouse-review-tracker .point-item");

        // Click each point forward: 0, 1, 2, 3, 4
        for (int i = 0; i < locations.length; i++) {
            points.nth(i).click();
            page.waitForTimeout(400);
        }
        screenshot("revisit-after-forward");

        // Now click backward, revisiting headings already flashed: 0, 3, 1
        int[] revisit = {0, 3, 1};
        for (int idx : revisit) {
            points.nth(idx).click();
            page.waitForTimeout(600);

            screenshot(String.format("revisit-%d-%s", idx, locations[idx].replace(" ", "_")));

            // Dump stale scroll-target classes
            Object staleCount = page.evaluate(
                    "() => document.querySelector('drafthouse-diff').shadowRoot.querySelectorAll('.scroll-target').length");
            System.out.println("Revisit click " + idx + " (" + locations[idx] + "): stale .scroll-target count = " + staleCount);

            assertThat(isPointNthSelected(idx))
                    .as("Revisit click %d (%s): selected", idx, locations[idx])
                    .isTrue();
            assertThat(countHighlightBars())
                    .as("Revisit click %d (%s): bars", idx, locations[idx])
                    .isGreaterThan(0);
        }
    }

    private boolean isPointNthSelected(int n) {
        Object val = page.evaluate(
                "n => { const items = document.querySelector('drafthouse-review-tracker').shadowRoot.querySelectorAll('.point-item');"
                        + "return items[n] && items[n].classList.contains('selected'); }", n);
        return (Boolean) val;
    }

    private int countHighlightBars() {
        Object val = page.evaluate(
                "() => document.querySelector('drafthouse-diff').shadowRoot.querySelectorAll('.section-highlight-bar').length");
        return ((Number) val).intValue();
    }
}
