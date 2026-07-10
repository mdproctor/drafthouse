package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.casehub.drafthouse.DocumentSide;
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
import java.util.UUID;

import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests for cross-panel coordination: {@code point-selected} custom events
 * fired by debate/tracker panels route through the workspace shell to
 * {@code <document-diff>.scrollToLocation()}.
 *
 * <p>Tests verify that clicking a debate entry or review tracker point with a
 * section reference scrolls the diff panel to the corresponding heading, and
 * that entries without a location leave the scroll position unchanged.
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
class CrossPanelE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject DebateMcpTools tools;
    @Inject MessageService messageService;
    @Inject DebateSessionRegistry debateRegistry;

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

    // ── cross-panel: debate entry → diff scroll ────────────────────────

    @Test
    void debateEntry_scrollsDiffToSectionRef() {
        sessionId = startDebateSession(tools);
        // §3 → diff-a: Features (3rd H1/H2), diff-b: Scroll Sync (3rd H1/H2)
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Section three concern.", "MEDIUM", "ISOLATED", "§3");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);

        // Verify both panels start at top
        assertEquals(0.0, scrollTopA(), "Panel A should start at top");
        assertEquals(0.0, scrollTopB(), "Panel B should start at top");

        // Click the debate entry — shell routes point-selected → scrollToLocation
        page.locator("channel-feed .entry-raise").click();
        page.waitForTimeout(500);

        assertTrue(scrollTopA() > 0, "Panel A should have scrolled after clicking debate entry with §3");
        assertTrue(scrollTopB() > 0, "Panel B should have scrolled after clicking debate entry with §3");
    }

    // ── cross-panel: tracker point → diff scroll ───────────────────────

    @Test
    void trackerPoint_scrollsDiffToSectionRef() {
        sessionId = startDebateSession(tools);
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Section three concern.", "MEDIUM", "ISOLATED", "§3");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);

        assertEquals(0.0, scrollTopA(), "Panel A should start at top");
        assertEquals(0.0, scrollTopB(), "Panel B should start at top");

        // Click the review tracker point instead of the debate entry
        page.locator("review-tracker .point-item").first().click();
        page.waitForTimeout(500);

        assertTrue(scrollTopA() > 0, "Panel A should have scrolled after clicking tracker point with §3");
        assertTrue(scrollTopB() > 0, "Panel B should have scrolled after clicking tracker point with §3");
    }

    // ── cross-panel: no location → no scroll ──────────────────────────

    @Test
    void pointWithoutLocation_noScroll() {
        sessionId = startDebateSession(tools);
        // Raise without location
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "General concern without location.", "MEDIUM", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);

        double beforeA = scrollTopA();
        double beforeB = scrollTopB();

        page.locator("channel-feed .entry-raise").click();
        page.waitForTimeout(500);

        assertEquals(beforeA, scrollTopA(), "Panel A scrollTop should be unchanged when no location");
        assertEquals(beforeB, scrollTopB(), "Panel B scrollTop should be unchanged when no location");
    }

    // ── cross-panel: text reference → scroll ──────────────────────────

    @Test
    void textReference_scrollsToMatchingHeading() {
        sessionId = startDebateSession(tools);
        // "Scroll Sync" heading exists in both diff-a.md and diff-b.md
        dispatchRaise(tools, messageService, sessionId, "REV", 1, "Scroll sync concern.", "MEDIUM", "ISOLATED", "Scroll Sync");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);

        assertEquals(0.0, scrollTopA(), "Panel A should start at top");
        assertEquals(0.0, scrollTopB(), "Panel B should start at top");

        page.locator("channel-feed .entry-raise").click();
        page.waitForTimeout(500);

        assertTrue(scrollTopA() > 0, "Panel A should have scrolled to 'Scroll Sync' heading");
        assertTrue(scrollTopB() > 0, "Panel B should have scrolled to 'Scroll Sync' heading");
    }

    // ── cross-panel: browser selection → server ────────────────────────

    @Test
    void selectionInDiff_postsToDebateSession() {
        sessionId = startDebateSession(tools);
        loadWithDebate(page, index, sessionId);

        // Dispatch selection-changed custom event from the diff panel.
        // This exercises the shell listener → REST POST → DebateSession pipeline
        // without depending on the Shadow DOM Selection API (which varies by browser).
        page.evaluate("() => {"
                + "const diff = document.querySelector('document-diff');"
                + "diff.dispatchEvent(new CustomEvent('selection-changed', {"
                + "  bubbles: true,"
                + "  detail: { side: 'A', startLine: 0, endLine: 0, selectedText: 'test selection' }"
                + "}));"
                + "}");

        // Wait for the async POST to reach the server
        page.waitForTimeout(1500);

        DebateSession session = debateRegistry.find(UUID.fromString(sessionId)).orElseThrow();
        assertThat(session.currentSelection()).isNotNull();
        assertThat(session.currentSelection().side()).isEqualTo(DocumentSide.A);
        assertThat(session.currentSelection().selectedText()).isEqualTo("test selection");
        assertThat(session.currentSelection().startLine()).isEqualTo(0);
        assertThat(session.currentSelection().endLine()).isEqualTo(0);
    }

    // ── shadow DOM scroll helpers ──────────────────────────────────────

    private double scrollTopA() {
        Object val = page.evaluate("() => document.querySelector('document-diff').shadowRoot.getElementById('body-a').scrollTop");
        return ((Number) val).doubleValue();
    }

    private double scrollTopB() {
        Object val = page.evaluate("() => document.querySelector('document-diff').shadowRoot.getElementById('body-b').scrollTop");
        return ((Number) val).doubleValue();
    }

}
