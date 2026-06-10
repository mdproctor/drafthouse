package io.casehub.drafthouse.debate;

public record RoundMemo(
        String agentRole,   // AgentType.name() of the role that wrote this memo, or "UNKNOWN" if absent
        int round,          // debate round number this memo was written after (1-based)
        String content      // the agent's working notes for this round
) {}
