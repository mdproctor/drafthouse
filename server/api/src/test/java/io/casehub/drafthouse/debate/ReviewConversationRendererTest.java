package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReviewConversationRendererTest {

    private final ReviewConversationRenderer renderer = new ReviewConversationRenderer();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ConversationState emptyState() {
        return new ConversationState(Map.of(), List.of(), List.of(), Map.of());
    }

    /** Builds a ConversationPoint with one RAISE thread entry and optionally one response entry. */
    private static ConversationPoint point(String id, String status, String question, String answer) {
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(id, null, null, "REV", 0, "RAISE", question));
        if (answer != null) {
            String respType = "DECLINED".equals(status) ? "DECLINED" : "AGREE";
            thread.add(new ThreadEntry(null, null, null, "IMP", 0, respType, answer));
        }
        return new ConversationPoint(id, null, new PointClassification(Priority.LOW, "ISOLATED", null), thread, status);
    }

    private static ConversationState stateWith(ConversationPoint... points) {
        var map = new LinkedHashMap<String, ConversationPoint>();
        for (ConversationPoint p : points) map.put(p.id(), p);
        return new ConversationState(map, List.of(), List.of(), Map.of());
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void emptyState_returnsSentinel() {
        assertThat(renderer.render(emptyState())).contains("No prior review activity");
    }

    @Test
    void onlyOpenPoints_returnsSentinel() {
        assertThat(renderer.render(stateWith(point("R1", "OPEN", "Q?", null))))
                .contains("No prior review activity");
    }

    @Test
    void activePoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", "ACTIVE", "Q?", "Partial."))))
                .contains("No prior review activity");
    }

    @Test
    void pendingHumanPoint_excluded() {
        assertThat(renderer.render(stateWith(point("R1", "ESCALATED", "Q?", "Needs human."))))
                .contains("No prior review activity");
    }

    @Test
    void agreedPoint_renderedAsQA() {
        ConversationState s = stateWith(point("R1", "AGREED", "What changed?", "It changed X."));
        String output = renderer.render(s);
        assertThat(output).contains("Q: What changed?");
        assertThat(output).contains("A: It changed X.");
    }

    @Test
    void declinedPoint_renderedWithParenthetical_noDoublePeriod() {
        ConversationState s = stateWith(point("R1", "DECLINED", "Off topic?", "Out of scope."));
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
        ConversationState s = stateWith(
                point("R1", "AGREED", "Q1?", "A1."),
                point("R2", "OPEN", "Q2?", null));
        String output = renderer.render(s);
        assertThat(output).contains("Q1?");
        assertThat(output).doesNotContain("Q2?");
    }

    @Test
    void agreedPoint_withMultipleThreadEntries_returnsLastResponseContent() {
        // Build a point that has RAISE → QUALIFY → AGREE thread (3 entries)
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry("R1", null, null, "REV", 0, "RAISE", "What changed?"));
        thread.add(new ThreadEntry(null, null, null, "IMP", 0, "QUALIFY", "Partly addressed."));
        thread.add(new ThreadEntry(null, null, null, "REV", 0, "AGREE", "Agreed after clarification."));
        var point = new ConversationPoint("R1", null,
                new PointClassification(Priority.LOW, "ISOLATED", null),
                thread, "AGREED");
        ConversationState s = new ConversationState(Map.of("R1", point), List.of(), List.of(), Map.of());

        String output = renderer.render(s);
        assertThat(output).contains("A: Agreed after clarification."); // last entry wins
        assertThat(output).doesNotContain("Partly addressed.");         // intermediate entry excluded
    }

    @Test
    void multipleCompletedExchanges_renderedInInsertionOrder() {
        ConversationState s = stateWith(
                point("R1", "AGREED", "First Q?", "First A."),
                point("R2", "DECLINED", "Second Q?", "Out of scope."));
        String output = renderer.render(s);
        assertThat(output).contains("First Q?");
        assertThat(output).contains("Second Q?");
        assertThat(output.indexOf("First Q?")).isLessThan(output.indexOf("Second Q?"));
    }
}
