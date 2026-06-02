package io.casehub.drafthouse.debate;

public record ReviewSession(
        String sessionId,
        String sessionPath,
        String specPath,
        int roundNumber,
        ReviewState currentState,
        int foldedEventCount) {}
