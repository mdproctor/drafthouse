package io.casehub.drafthouse.debate;

public record RoundMemo(
        String agentRole,   // "REV" or "IMP" — which agent wrote this memo
        int round,          // debate round number this memo was written after (1-based)
        String content      // the agent's working notes for this round
) {}
