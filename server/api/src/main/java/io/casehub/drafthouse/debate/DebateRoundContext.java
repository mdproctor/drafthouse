package io.casehub.drafthouse.debate;

public record DebateRoundContext(
        String specContent,
        String debateContent,
        ReviewState currentState,
        int roundNumber,
        String sessionId) {}
