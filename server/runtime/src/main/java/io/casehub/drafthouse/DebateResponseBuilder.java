package io.casehub.drafthouse;

import io.casehub.agency.core.AgentResult;
import io.casehub.agency.communication.dispatch.ChannelMessageMeta;
import io.casehub.agency.deliberation.conversation.ConversationProtocol;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.ThreadEntry;
import io.casehub.agency.deliberation.conversation.orchestration.AgentParticipant;
import io.casehub.agency.deliberation.conversation.orchestration.ResponseMessageBuilder;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DebateResponseBuilder implements ResponseMessageBuilder {

    @Override
    public MessageView build(AgentParticipant agent, AgentResult result,
                             ConversationState currentState) {
        String body = result.output() != null ? result.output().toString() : "";
        String entryType = inferEntryType(body);
        int round = currentRound(currentState);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, entryType);
        meta.put(ConversationProtocol.ROLE, agent.role());
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));

        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, body);
        String correlationId = UUID.randomUUID().toString();

        return new MessageView(
                null, null, agent.agentId(),
                MessageType.RESPONSE, encoded, null, correlationId,
                null, null, null,
                List.of(), ActorType.AGENT, Instant.now(), null, 0);
    }

    private String inferEntryType(String body) {
        if (body == null || body.isEmpty()) return "RAISE";
        String lower = body.toLowerCase();
        if (lower.startsWith("agree") || lower.contains("i agree")) return "AGREE";
        if (lower.startsWith("dispute") || lower.contains("i dispute")) return "DISPUTE";
        if (lower.startsWith("counter") || lower.contains("i counter")) return "COUNTER";
        if (lower.startsWith("qualif") || lower.contains("i qualify")) return "QUALIFY";
        return "RAISE";
    }

    private int currentRound(ConversationState state) {
        return state.points().values().stream()
                .flatMap(p -> p.thread().stream())
                .mapToInt(ThreadEntry::round)
                .max()
                .orElse(0) + 1;
    }
}
