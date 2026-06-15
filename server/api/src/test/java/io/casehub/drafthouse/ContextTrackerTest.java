package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ContextTrackerTest {

    @Test
    void newTracker_startsAtZero() {
        var tracker = new ContextTracker();
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isZero();
        assertThat(snap.messageCount()).isZero();
        assertThat(snap.agentReportedPercent()).isNull();
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
        assertThat(snap.thresholdExceeded()).isFalse();
    }

    @Test
    void addContribution_incrementsCharsAndMessageCount() {
        var tracker = new ContextTracker();
        tracker.addContribution(1000);
        tracker.addContribution(2000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(3000);
        assertThat(snap.messageCount()).isEqualTo(2);
    }

    @Test
    void addInitialContribution_incrementsCharsButNotMessageCount() {
        var tracker = new ContextTracker();
        tracker.addInitialContribution(50_000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(50_000);
        assertThat(snap.messageCount()).isZero();
    }

    @Test
    void effectivePercent_usesServerContributionWhenNoAgentReport() {
        var tracker = new ContextTracker();
        tracker.addContribution(400_000);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.effectivePercent()).isCloseTo(50.0, within(0.01));
        assertThat(snap.agentReportedPercent()).isNull();
    }

    @Test
    void effectivePercent_usesAgentReportWhenPresent() {
        var tracker = new ContextTracker();
        tracker.addContribution(400_000);
        tracker.reportAgentUsage(75.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.effectivePercent()).isEqualTo(75.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(75.0);
    }

    @Test
    void thresholdExceeded_trueWhenEffectivePercentExceedsThreshold() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(85.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.thresholdExceeded()).isTrue();
    }

    @Test
    void thresholdExceeded_trueAtExactThreshold_falseBelow() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(80.0);
        assertThat(tracker.snapshot(800_000, 80.0).thresholdExceeded()).isTrue();

        var tracker2 = new ContextTracker();
        tracker2.reportAgentUsage(79.99);
        assertThat(tracker2.snapshot(800_000, 80.0).thresholdExceeded()).isFalse();
    }

    @Test
    void reportAgentUsage_negativeClampedToZero() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(-5.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(0.0);
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
    }

    @Test
    void reportAgentUsage_over100Accepted() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(120.0);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isEqualTo(120.0);
        assertThat(snap.effectivePercent()).isEqualTo(120.0);
        assertThat(snap.thresholdExceeded()).isTrue();
    }

    @Test
    void snapshot_withZeroWindowSize_effectivePercentIsZero() {
        var tracker = new ContextTracker();
        tracker.addContribution(1000);
        var snap = tracker.snapshot(0, 80.0);
        assertThat(snap.effectivePercent()).isEqualTo(0.0);
    }

    @Test
    void reportAgentUsage_nanIgnored() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(Double.NaN);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isNull();
    }

    @Test
    void reportAgentUsage_infinityIgnored() {
        var tracker = new ContextTracker();
        tracker.reportAgentUsage(Double.POSITIVE_INFINITY);
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.agentReportedPercent()).isNull();
    }

    @Test
    void snapshot_includesWindowSizeChars() {
        var tracker = new ContextTracker();
        var snap = tracker.snapshot(800_000, 80.0);
        assertThat(snap.windowSizeChars()).isEqualTo(800_000);
    }
}
