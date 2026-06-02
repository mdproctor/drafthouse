package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();
    private final SummaryProjector projector = new SummaryProjector();

    @Test
    void rendersEmptyStateAsHeader() {
        String output = renderer.render(projector.identity());
        assertThat(output).contains("# Review Summary");
        assertThat(output).doesNotContain("##");
    }

    @Test
    void rendersOpenPoint() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, "§3.2", "Both variants appear.")));
        String output = renderer.render(state);
        assertThat(output).contains("🔴");
        assertThat(output).contains("[R1-REV-001]");
        assertThat(output).contains("P1");
        assertThat(output).contains("Both variants appear.");
    }

    @Test
    void rendersAgreedPointWithStrikethrough() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Issue."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Fixed.", ReviewStatus.AGREED)));
        String output = renderer.render(state);
        assertThat(output).contains("✅");
        assertThat(output).contains("~~");
    }

    @Test
    void rendersFlagSectionAtBottom() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Issue."),
                new DebateEvent.FlagHumanEvent(1, AgentType.REV, "Human needed.", "R1-REV-001", ReviewStatus.PENDING_HUMAN)));
        String output = renderer.render(state);
        assertThat(output).contains("⚑");
        assertThat(output).contains("Human needed.");
        assertThat(output.indexOf("⚑")).isGreaterThan(output.indexOf("R1-REV-001"));
    }

    @Test
    void memoDoesNotAppearInSummary() {
        ReviewState state = projector.project(List.of(
                new DebateEvent.AgentMemo(1, AgentType.REV, "Private thought.")));
        String output = renderer.render(state);
        assertThat(output).doesNotContain("Private thought.");
    }
}
