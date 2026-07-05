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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for section highlight: clicking a review tracker point should
 * toggle a persistent highlight bar in the diff panel spanning the referenced
 * section, and toggle selection styling on the tracker point itself.
 *
 * <p>Tests exercise location formats against both the standard diff fixtures
 * (diff-a.md/diff-b.md) and the rule-engine sample documents (sample-a.md/
 * sample-b.md) to cover real MCP caller patterns.
 */
@QuarkusTest
@WithPlaywright
class SectionHighlightE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;
    @Inject MessageService messageService;

    private Page page;
    private String sessionId;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    // ── location format coverage (standard diff fixtures) ─────────────

    @ParameterizedTest(name = "location \"{0}\" → highlight bar appears")
    @CsvSource({
            "§3,          true",   // pure numeric — 3rd top-level heading (Features)
            "§3.1,        true",   // sub-section — Word Changes under Features
            "Scroll Sync, true",   // exact text match
            "scroll sync, true",   // case-insensitive text match
            "Features,    true",   // single-word heading match
            "§3 Features, true",   // mixed: numeric prefix + text
            "Introduction,true",   // text match on 2nd heading
    })
    void locationFormat_highlightBarAppears(String location, boolean expectBar) {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Concern about this section.", "MEDIUM", "ISOLATED", location);
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);

        int barCount = countHighlightBars();
        if (expectBar) {
            assertThat(barCount).as("Highlight bars for location '%s'", location)
                    .isGreaterThan(0);
        } else {
            assertThat(barCount).as("No highlight bars expected for location '%s'", location)
                    .isEqualTo(0);
        }
    }

    // ── location formats against sample-a.md / sample-b.md ───────────
    // These match the documents the user loaded in the live session.
    // Headings: # What Is a Rule Engine? (H1)
    //           ## Core Concepts (H2), ### Rules (H3), ### Working Memory (H3),
    //           ### The Inference Engine (H3)
    //           ## Why Use One? (H2)
    //           ## Limitations (A) / ## When It's Not Worth It (B) (H2)
    //           ## Further Reading (H2)

    @ParameterizedTest(name = "sample docs: location \"{0}\" → highlight bar appears")
    @CsvSource({
            "§1,                     true",  // H1 title
            "§2,                     true",  // Core Concepts (H2)
            "§2.1,                   true",  // Rules (H3 under Core Concepts)
            "§2.2,                   true",  // Working Memory (H3)
            "§2.3,                   true",  // The Inference Engine (H3)
            "Core Concepts,          true",  // text match
            "Rules,                  true",  // H3 text match
            "Working Memory,         true",  // H3 text match
            "The Inference Engine,   true",  // H3 text match with article
            "Inference Engine,       true",  // partial H3 text match (no 'The')
            "Why Use One?,           true",  // text match with punctuation
            "Limitations,            true",  // matches A only (B has different heading)
            "Further Reading,        true",  // matches both
            "§3 Why Use One?,        true",  // mixed numeric + text
            "§2.1 Rules,             true",  // mixed numeric + text for sub-section
            "Limitations / When It's Not Worth It, true", // slash-separated A/B heading names
            // ── LLM compound/descriptive location formats ──────────────
            "'Core Concepts',            true",  // single-quoted
            "\"Core Concepts\",          true",  // double-quoted
            "“Core Concepts”,          true",  // smart-quoted
            "Section: Core Concepts,     true",  // prefix: Section:
            "Heading: Working Memory,    true",  // prefix: Heading:
            "Under Core Concepts,        true",  // prefix: Under
            "In the Core Concepts,       true",  // prefix: In the
            "The Core Concepts section,  true",  // prefix: The + suffix: section
            "Further Reading section,    true",  // suffix: section
            "Core Concepts heading,      true",  // suffix: heading
            "Limitations - When It's Not Worth It, true", // dash-separated
            "Limitations — When It's Not Worth It, true", // em-dash-separated
            "Limitations and When It's Not Worth It, true", // and-separated
            "Core Concepts or Why Use One?, true", // or-separated (matches first)
            "Concepts and Memory,        true",  // word-overlap: 'concepts' matches Core Concepts
    })
    void sampleDocs_locationFormats(String location, boolean expectBar) {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Concern about this section.", "MEDIUM", "ISOLATED", location);
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);

        int barCount = countHighlightBars();
        if (expectBar) {
            assertThat(barCount).as("Highlight bars for location '%s'", location)
                    .isGreaterThan(0);
        } else {
            assertThat(barCount).as("No highlight bars expected for location '%s'", location)
                    .isEqualTo(0);
        }
    }

    // ── tracker selection always toggles (even without location) ─────

    @Test
    void pointWithNullLocation_trackerStillToggles() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "General concern.", "MEDIUM", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        var point = page.locator("drafthouse-review-tracker .point-item").first();

        point.click();
        page.waitForTimeout(500);
        assertThat(isPointSelected()).as("Point with null location should still be selectable").isTrue();
        assertThat(countHighlightBars()).as("No highlight bar for null location").isEqualTo(0);

        point.click();
        page.waitForTimeout(500);
        assertThat(isPointSelected()).as("Point should deselect on second click").isFalse();
    }

    // ── toggle on/off ────────────────────────────────────────────────

    @Test
    void clickToggle_selectsThenDeselects() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Section concern.", "MEDIUM", "ISOLATED", "§3");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        var point = page.locator("drafthouse-review-tracker .point-item").first();

        // Click once — selected
        point.click();
        page.waitForTimeout(500);
        assertThat(isPointSelected()).as("Point should be selected after first click").isTrue();
        assertThat(countHighlightBars()).as("Highlight bars should appear").isGreaterThan(0);

        // Click again — deselected
        point.click();
        page.waitForTimeout(500);
        assertThat(isPointSelected()).as("Point should be deselected after second click").isFalse();
        assertThat(countHighlightBars()).as("Highlight bars should be cleared").isEqualTo(0);
    }

    // ── selecting different point clears previous ────────────────────

    @Test
    void selectingDifferentPoint_clearsPrevious() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "First concern.", "MEDIUM", "ISOLATED", "§2");
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Second concern.", "MEDIUM", "ISOLATED", "§4");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 2);

        var points = page.locator("drafthouse-review-tracker .point-item");

        // Select first point
        points.first().click();
        page.waitForTimeout(500);
        assertThat(countSelectedPoints()).as("Exactly one point selected").isEqualTo(1);

        // Select second point — first should deselect
        points.nth(1).click();
        page.waitForTimeout(500);
        assertThat(countSelectedPoints()).as("Still exactly one point selected").isEqualTo(1);
        assertThat(countHighlightBars()).as("Highlight bars present for new selection").isGreaterThan(0);
    }

    // ── sequential point clicks — each must scroll + highlight ─────

    @Test
    void clickingEachPointInSequence_allHighlightAndSelect() {
        sessionId = startDebateSession(tools);
        // 5 points with different locations in the sample docs
        String[] locations = {"Core Concepts", "Rules", "Working Memory", "Why Use One?", "Further Reading"};
        for (String loc : locations) {
            dispatchRaise(tools, messageService, sessionId, "REV", 1,
                    "Concern about " + loc + ".", "MEDIUM", "ISOLATED", loc);
        }
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, locations.length);

        var points = page.locator("drafthouse-review-tracker .point-item");

        for (int i = 0; i < locations.length; i++) {
            points.nth(i).click();
            page.waitForTimeout(500);

            assertThat(countSelectedPoints())
                    .as("Click %d (%s): exactly one point selected", i, locations[i])
                    .isEqualTo(1);
            assertThat(isPointNthSelected(i))
                    .as("Click %d (%s): the clicked point is the selected one", i, locations[i])
                    .isTrue();
            assertThat(countHighlightBars())
                    .as("Click %d (%s): highlight bars appear", i, locations[i])
                    .isGreaterThan(0);
            assertThat(hasScrollFlash())
                    .as("Click %d (%s): scroll flash visible on heading", i, locations[i])
                    .isTrue();
        }
    }

    // ── click forward then backward — every click must produce bar ──

    @Test
    void clickForwardThenBackward_allHighlight() {
        sessionId = startDebateSession(tools);
        String[] locations = {"Core Concepts", "Rules", "Working Memory", "Why Use One?", "Further Reading"};
        for (String loc : locations) {
            dispatchRaise(tools, messageService, sessionId, "REV", 1,
                    "Concern about " + loc + ".", "MEDIUM", "ISOLATED", loc);
        }
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, locations.length);

        var points = page.locator("drafthouse-review-tracker .point-item");

        // Click forward: 0, 1, 2, 3, 4
        for (int i = 0; i < locations.length; i++) {
            points.nth(i).click();
            page.waitForTimeout(400);
            assertThat(countHighlightBars())
                    .as("Forward click %d (%s): highlight bars", i, locations[i])
                    .isGreaterThan(0);
            assertThat(isPointNthSelected(i))
                    .as("Forward click %d (%s): selected", i, locations[i])
                    .isTrue();
        }

        // Click backward: 3, 2, 1, 0
        for (int i = locations.length - 2; i >= 0; i--) {
            points.nth(i).click();
            page.waitForTimeout(400);
            assertThat(countHighlightBars())
                    .as("Backward click %d (%s): highlight bars", i, locations[i])
                    .isGreaterThan(0);
            assertThat(isPointNthSelected(i))
                    .as("Backward click %d (%s): selected", i, locations[i])
                    .isTrue();
            assertThat(hasScrollFlash())
                    .as("Backward click %d (%s): scroll flash", i, locations[i])
                    .isTrue();
        }
    }

    // ── re-render mid-selection preserves state ────────────────────

    @Test
    void reRenderDuringSelection_selectionSurvives() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "First concern.", "MEDIUM", "ISOLATED", "Core Concepts");
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        // Select the point
        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);
        assertThat(isPointSelected()).as("Point selected before re-render").isTrue();
        assertThat(countHighlightBars()).as("Bars appear before re-render").isGreaterThan(0);

        // Simulate a new debate entry arriving (triggers #render())
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Second concern.", "MEDIUM", "ISOLATED", "Rules");
        waitForTrackerPoints(page, 2);

        // After re-render: first point should STILL be selected
        assertThat(isPointNthSelected(0))
                .as("First point stays selected after re-render").isTrue();
        assertThat(countHighlightBars())
                .as("Highlight bars survive re-render").isGreaterThan(0);

        // Clicking the first point again should DESELECT (toggle off)
        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);
        assertThat(isPointSelected())
                .as("First point deselects on second click after re-render").isFalse();

        // Clicking it a third time should SELECT again
        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);
        assertThat(isPointSelected())
                .as("First point re-selects on third click").isTrue();
        assertThat(countHighlightBars())
                .as("Bars reappear on third click").isGreaterThan(0);
    }

    // ── heading match: only in one panel still produces a bar ────────

    @Test
    void locationMatchesOnlyOneSide_barAppearsOnThatSide() {
        sessionId = startDebateSession(tools);
        // "Limitations" exists in sample-a but sample-b has "When It's Not Worth It"
        dispatchRaise(tools, messageService, sessionId, "REV", 1,
                "Limitation wording.", "MEDIUM", "ISOLATED", "Limitations");
        loadWithSampleDocs(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        page.locator("drafthouse-review-tracker .point-item").first().click();
        page.waitForTimeout(500);

        // Should have at least one bar (A side only)
        assertThat(countHighlightBars()).as("Bar appears on side where heading matches")
                .isGreaterThanOrEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void loadWithSampleDocs(Page page, URL base, String sessionId) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("sample-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("sample-b.md"), StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + a + "&b=" + b + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);
    }

    private int countHighlightBars() {
        Object val = page.evaluate(
                "() => document.querySelector('drafthouse-diff').shadowRoot.querySelectorAll('.section-highlight-bar').length");
        return ((Number) val).intValue();
    }

    private boolean isPointSelected() {
        Object val = page.evaluate(
                "() => document.querySelector('drafthouse-review-tracker').shadowRoot.querySelectorAll('.point-item.selected').length > 0");
        return (Boolean) val;
    }

    private int countSelectedPoints() {
        Object val = page.evaluate(
                "() => document.querySelector('drafthouse-review-tracker').shadowRoot.querySelectorAll('.point-item.selected').length");
        return ((Number) val).intValue();
    }

    private boolean isPointNthSelected(int n) {
        Object val = page.evaluate(
                "n => { const items = document.querySelector('drafthouse-review-tracker').shadowRoot.querySelectorAll('.point-item');"
                        + "return items[n] && items[n].classList.contains('selected'); }", n);
        return (Boolean) val;
    }

    private double scrollTopA() {
        Object val = page.evaluate("() => document.querySelector('drafthouse-diff').shadowRoot.getElementById('body-a').scrollTop");
        return ((Number) val).doubleValue();
    }

    private boolean hasScrollFlash() {
        Object val = page.evaluate(
                "() => document.querySelector('drafthouse-diff').shadowRoot.querySelectorAll('.scroll-target').length > 0");
        return (Boolean) val;
    }
}
