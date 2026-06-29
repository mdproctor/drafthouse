package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.ConversationPoint;
import io.casehub.blocks.conversation.ThreadEntry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class ArbitrateHandler extends AbstractDebateSubAgentHandler {

    @Override
    String taskType() { return "ARBITRATE"; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        ConversationState state = currentState(request.channelId());
        ConversationPoint point = requirePoint(state, pointId);   // validates pointId; returns point
        String raiseContent = point.thread().get(0).content();
        String lastResponse = point.thread().stream()
                .filter(e -> "DISPUTE".equals(e.entryType())
                          || "QUALIFY".equals(e.entryType())
                          || "COUNTER".equals(e.entryType()))
                .reduce((a, b) -> b)
                .map(ThreadEntry::content)
                .orElse("(no response yet)");
        return new AgentTask(
                "You are a neutral arbitrator. You have not seen this debate before. "
                + "Assess these two positions on their merits only. Do not favour either side.",
                "Original claim:\n" + raiseContent + "\n\nMost recent response:\n" + lastResponse
        );
    }
}
