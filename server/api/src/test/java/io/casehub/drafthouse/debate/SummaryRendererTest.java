package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Test
    void rendersEmptyStateAsHeader() {
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).contains("# Review Summary");
        assertThat(output).doesNotContain("##");
    }

    @Test
    void rendersOpenPoint() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, "§3.2"),
                List.of(new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Both variants appear.")),
                ReviewStatus.OPEN)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("🔴");
        assertThat(output).contains("[R1-REV-001]");
        assertThat(output).contains("P1");
        assertThat(output).contains("Both variants appear.");
    }

    @Test
    void rendersAgreedPointWithStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.IMP, 0, EntryType.AGREE, "Fixed.")),
                ReviewStatus.AGREED)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("✅");
        assertThat(output).contains("~~");
    }

    @Test
    void rendersFlagSectionAtBottom() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.FLAG_HUMAN, "Human needed.")),
                ReviewStatus.PENDING_HUMAN)),
            List.of(new FlagEntry(null, 0, AgentType.REV, "Human needed.")));
        String output = renderer.render(state);
        assertThat(output).contains("⚑");
        assertThat(output).contains("Human needed.");
        assertThat(output.indexOf("⚑")).isGreaterThan(output.indexOf("R1-REV-001"));
    }

    @Test
    void memoDoesNotAppearInSummary() {
        // A memo produces no review points — state stays empty.
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).doesNotContain("Private thought.");
    }

    @Test
    void renderTimestampIsControlledByClock() {
        Instant fixed = Instant.parse("2026-01-15T10:30:00Z");
        renderer.setClockForTest(() -> fixed);
        String output = renderer.render(new ReviewState(Map.of(), List.of()));
        assertThat(output).contains("2026-01-15T10:30:00Z");
    }

    @Test
    void rendersDeclinedPointWithStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P3, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Off topic?"),
                    new ThreadEntry(null, AgentType.IMP, 0, EntryType.DECLINED, "Out of scope.")),
                ReviewStatus.DECLINED)),
            List.of());
        String output = renderer.render(state);
        assertThat(output).contains("🚫");
        assertThat(output).contains("~~");
        assertThat(output).contains("declined");
    }
}
