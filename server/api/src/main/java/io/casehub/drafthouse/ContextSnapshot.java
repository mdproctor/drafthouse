package io.casehub.drafthouse;

public record ContextSnapshot(
        long serverContributionChars,
        long windowSizeChars,
        Double agentReportedPercent,
        int messageCount,
        double effectivePercent,
        boolean thresholdExceeded
) {}
