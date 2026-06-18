package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DebateSessionSnapshot(
        UUID channelId,
        String debateSessionId,
        String channelName,
        List<DocumentEntry> documents,
        ComparisonPair comparison,
        Map<AgentType, String> participants) {}
