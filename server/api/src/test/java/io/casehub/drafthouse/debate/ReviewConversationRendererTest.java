package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReviewConversationRendererTest {

    private final ReviewConversationRenderer renderer = new ReviewConversationRenderer();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewState emptyState() {
        return new ReviewState(Map.of(), List.of());
    }

    /** Builds a ReviewPoint with one RAISE thread entry and optionally one response entry. */
    private static ReviewPoint point(String id, ReviewStatus status, String question, String answer) {
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(id, AgentType.REV, 0, EntryType.RAISE, question));
        if (answer != null) {
            EntryType respType = status == ReviewStatus.DECLINED ? EntryType.DECLINED : EntryType.AGREE;
            thread.add(new ThreadEntry(null, AgentType.IMP, 0, respType, answer));
        }
        return new ReviewPoint(id, new PointClassification(Priority.P3, Scope.ISOLATED, null), thread, status);
    }

    private static ReviewState stateWith(ReviewPoint... points) {
        var map = new LinkedHashMap<String, ReviewPoint>();
        for (ReviewPoint p : points) map.put(p.id(), p);
        return new ReviewState(map, List.of());
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void emptyState_returnsSentinel() {
        assertThat(renderer.render(emptyState())).contains("No prior review activity");
    }

    @Test
    void onlyOpenPoints_returnsSentinel() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.OPEN, "Q?", null))))
                .contains("No prior review activity");
    }

    @Test
    void activePoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.ACTIVE, "Q?", "Partial."))))
                .contains("No prior review activity");
    }

    @Test
    void pendingHumanPoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", ReviewStatus.PENDING_HUMAN, "Q?", "Needs human."))))
                .contains("No prior review activity");
    }

    @Test
    void agreedPoint_renderedAsQA() {
        ReviewState s = stateWith(point("R1", ReviewStatus.AGREED, "What changed?", "It changed X."));
        String output = renderer.render(s);
        assertThat(output).contains("Q: What changed?");
        assertThat(output).contains("A: It changed X.");
    }

    @Test
    void declinedPoint_renderedWithParenthetical_noDoublePeriod() {
        ReviewState s = stateWith(point("R1", ReviewStatus.DECLINED, "Off topic?", "Out of scope."));
        String output = renderer.render(s);
        assertThat(output).contains("Q: Off topic?");
        assertThat(output).contains("(Declined");
        assertThat(output).contains("Out of scope");
        // trailing period from content must be stripped before appending closing paren
        assertThat(output).doesNotContain("scope..)");
        assertThat(output).contains("A: (Declined — Out of scope)");
    }

    @Test
    void openPoint_excludedWhenMixedWithCompleted() {
        ReviewState s = stateWith(
                point("R1", ReviewStatus.AGREED, "Q1?", "A1."),
                point("R2", ReviewStatus.OPEN, "Q2?", null));
        String output = renderer.render(s);
        assertThat(output).contains("Q1?");
        assertThat(output).doesNotContain("Q2?");
    }

    @Test
    void agreedPoint_withMultipleThreadEntries_returnsLastResponseContent() {
        // Build a point that has RAISE → QUALIFY → AGREE thread (3 entries)
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry("R1", AgentType.REV, 0, EntryType.RAISE, "What changed?"));
        thread.add(new ThreadEntry(null, AgentType.IMP, 0, EntryType.QUALIFY, "Partly addressed."));
        thread.add(new ThreadEntry(null, AgentType.REV, 0, EntryType.AGREE, "Agreed after clarification."));
        var point = new ReviewPoint("R1",
                new PointClassification(Priority.P3, Scope.ISOLATED, null),
                thread, ReviewStatus.AGREED);
        ReviewState s = new ReviewState(Map.of("R1", point), List.of());

        String output = renderer.render(s);
        assertThat(output).contains("A: Agreed after clarification."); // last entry wins
        assertThat(output).doesNotContain("Partly addressed.");         // intermediate entry excluded
    }

    @Test
    void multipleCompletedExchanges_renderedInInsertionOrder() {
        ReviewState s = stateWith(
                point("R1", ReviewStatus.AGREED, "First Q?", "First A."),
                point("R2", ReviewStatus.DECLINED, "Second Q?", "Out of scope."));
        String output = renderer.render(s);
        assertThat(output).contains("First Q?");
        assertThat(output).contains("Second Q?");
        assertThat(output.indexOf("First Q?")).isLessThan(output.indexOf("Second Q?"));
    }
}
