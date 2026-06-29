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
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the {@code <drafthouse-review-tracker>} panel.
 *
 * Covers status derivation from debate entry sequences, progress bar, filter
 * toggle, sort order, agent trail, location display, and point-selected events.
 *
 * <h3>Ledger frontier exception</h3>
 * MessageService.dispatch() commits the Qhorus message first, then the ledger
 * extension updates the Merkle frontier. When casehub-ledger SNAPSHOT adds new
 * columns that Flyway hasn't migrated (e.g. TENANCY_ID), the frontier query
 * fails <em>after</em> the message is already persisted. The message is visible
 * via SSE, so E2E assertions work. The dispatch helpers in {@link DebateE2EFixtures} catch the
 * propagated exception to keep the test focused on UI rendering. Once the ledger
 * migration is updated, the catch blocks become no-ops.
 */
@QuarkusTest
@WithPlaywright
class ReviewTrackerE2ETest {

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

    // ── placeholder / empty states ──────────────────────────────────────────

    @Test
    void placeholder_whenNoDebateSession() {
        // No ?debate= param → shell calls configure({}) with no sessionId.
        // The bus has no SSE connection so no entries arrive, but configure()
        // triggers #initialize() → #render() which shows "No review points yet".
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("No review points yet");
    }

    @Test
    void emptyState_showsNoPoints() {
        sessionId = startDebateSession(tools);
        loadWithDebate(page, index, sessionId);
        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("No review points yet");
    }

    // ── status derivation ───────────────────────────────────────────────────

    @Test
    void raisedPoint_showsOpenStatus() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Unresolved API concern.", "MEDIUM", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open .point-icon"))
                .containsText("○");
    }

    @Test
    void agreedPoint_showsStrikethrough() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Agreed concern.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-agreed")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-agreed .point-icon"))
                .containsText("✓");

        // Verify text-decoration: line-through via computed style inside shadow DOM
        String textDecoration = (String) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.point-item.status-agreed .point-summary');"
                + "return getComputedStyle(el).textDecorationLine || getComputedStyle(el).textDecoration;"
                + "}");
        assertTrue(textDecoration.contains("line-through"),
                "Agreed point summary should have line-through, got: " + textDecoration);
    }

    @Test
    void declinedPoint_showsStrikethrough() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Declined concern.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "declined", "Declined to address.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-declined")).hasCount(1);

        String textDecoration = (String) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.point-item.status-declined .point-summary');"
                + "return getComputedStyle(el).textDecorationLine || getComputedStyle(el).textDecoration;"
                + "}");
        assertTrue(textDecoration.contains("line-through"),
                "Declined point summary should have line-through, got: " + textDecoration);
    }

    @Test
    void counteredPoint_showsActiveStatus() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Point for counter.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "counter", "Counter-proposal.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-active")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-active .point-icon"))
                .containsText("⟳");
    }

    @Test
    void disputedPoint_showsDisputedStatus() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Point for dispute.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "dispute", "Disputed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-disputed")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-disputed .point-icon"))
                .containsText("✕");
    }

    @Test
    void qualifiedPoint_showsActiveWithAccentBorder() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Point for qualify.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "qualify", "Qualified.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        // QUALIFY maps to ACTIVE status but also adds qualify-active class
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-active")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.qualify-active")).hasCount(1);
    }

    @Test
    void flagHuman_showsPendingHumanStatus() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Ambiguous requirement.", "HIGH", "SYSTEMIC", null);
        dispatchFlag(tools, sessionId, "REV", 1, pointId, "Cannot resolve without human input.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-item.status-pending_human")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-pending_human .point-icon"))
                .containsText("⚑");
    }

    // ── progress bar ────────────────────────────────────────────────────────

    @Test
    void progressBar_reflectsResolutionRatio() {
        sessionId = startDebateSession(tools);
        String p1 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "First point.", "MEDIUM", "ISOLATED", null);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Second point.", "MEDIUM", "ISOLATED", null);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Third point.", "MEDIUM", "ISOLATED", null);

        // Agree to first point only
        dispatchResponse(tools, sessionId, "IMP", 1, p1, "agree", "Agreed to first.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 3);

        assertThat(page.locator("drafthouse-review-tracker .progress-label"))
                .containsText("1 of 3 resolved");

        Double fillWidth = (Double) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.progress-fill');"
                + "return parseFloat(el.style.width);"
                + "}");
        assertTrue(fillWidth >= 33 && fillWidth <= 34,
                "Progress fill width should be ~33.3%, got: " + fillWidth);
    }

    // ── filter toggle ───────────────────────────────────────────────────────

    @Test
    void hideResolvedFilter_hidesAgreedAndDeclined() {
        sessionId = startDebateSession(tools);
        String p1 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "First point.", "MEDIUM", "ISOLATED", null);
        String p2 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Second point.", "MEDIUM", "ISOLATED", null);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Third point.", "MEDIUM", "ISOLATED", null);

        dispatchResponse(tools, sessionId, "IMP", 1, p1, "agree", "Agreed.");
        dispatchResponse(tools, sessionId, "IMP", 1, p2, "declined", "Declined.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 3);

        // Toggle filter: check the checkbox
        page.locator("drafthouse-review-tracker .filter-toggle input[type='checkbox']").check();

        // After filter, only the unresolved point should remain
        assertThat(page.locator("drafthouse-review-tracker .point-item")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open")).hasCount(1);
    }

    @Test
    void hideResolvedFilter_allResolved_showsMessage() {
        sessionId = startDebateSession(tools);
        String p1 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "First point.", "MEDIUM", "ISOLATED", null);
        String p2 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Second point.", "MEDIUM", "ISOLATED", null);

        dispatchResponse(tools, sessionId, "IMP", 1, p1, "agree", "Agreed.");
        dispatchResponse(tools, sessionId, "IMP", 1, p2, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 2);

        // Toggle filter
        page.locator("drafthouse-review-tracker .filter-toggle input[type='checkbox']").check();

        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("All points resolved");
    }

    // ── sort order ──────────────────────────────────────────────────────────

    @Test
    void sortOrder_openBeforeAgreed() {
        sessionId = startDebateSession(tools);
        String p1 = dispatchRaise(tools, messageService, sessionId, "REV", 1, "First raised point.", "MEDIUM", "ISOLATED", null);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Second raised point.", "MEDIUM", "ISOLATED", null);

        // Agree to the first one — it should sort after the still-open second one
        dispatchResponse(tools, sessionId, "IMP", 1, p1, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 2);

        // First item should be OPEN, second should be AGREED
        assertThat(page.locator("drafthouse-review-tracker .point-item").nth(0))
                .hasClass(java.util.regex.Pattern.compile(".*status-open.*"));
        assertThat(page.locator("drafthouse-review-tracker .point-item").nth(1))
                .hasClass(java.util.regex.Pattern.compile(".*status-agreed.*"));
    }

    // ── agent trail ─────────────────────────────────────────────────────────

    @Test
    void agentTrail_showsActionSequence() {
        sessionId = startDebateSession(tools);
        String pointId = dispatchRaise(tools, messageService, sessionId, "REV", 1, "Point for trail.", "MEDIUM", "ISOLATED", null);
        dispatchResponse(tools, sessionId, "IMP", 1, pointId, "counter", "Counter-proposal.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-trail"))
                .containsText("REV raised");
        assertThat(page.locator("drafthouse-review-tracker .point-trail"))
                .containsText("IMP countered");
    }

    // ── location reference ──────────────────────────────────────────────────

    @Test
    void locationReference_displayedOnPoint() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Point at section.", "MEDIUM", "ISOLATED", "§3.2");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertThat(page.locator("drafthouse-review-tracker .point-location"))
                .containsText("§3.2");
    }

    // ── point-selected custom event ─────────────────────────────────────────

    @Test
    void pointSelected_firesCustomEvent() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Clickable tracker point.", "HIGH", "ISOLATED", "§3.2");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        listenForPointSelected(page);
        page.locator("drafthouse-review-tracker .point-item").first().click();

        Object detail = getPointSelectedDetail(page);
        assertNotNull(detail, "point-selected event should have fired");

        @SuppressWarnings("unchecked")
        Map<String, Object> detailMap = (Map<String, Object>) detail;
        assertNotNull(detailMap.get("pointId"), "event detail should include pointId");
        assertEquals("§3.2", detailMap.get("location"), "event detail should include location");
    }

}
