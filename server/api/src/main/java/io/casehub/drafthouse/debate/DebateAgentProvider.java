package io.casehub.drafthouse.debate;

import java.util.List;

public interface DebateAgentProvider {
    List<DebateEntry> executeReviewerRound(DebateRoundContext context);
    List<DebateEntry> executeImplementerRound(DebateRoundContext context);
}
