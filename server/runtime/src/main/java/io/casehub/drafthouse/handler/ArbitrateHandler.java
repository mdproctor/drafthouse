package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.drafthouse.debate.EntryType;
import io.casehub.drafthouse.debate.ReviewPoint;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskType;
import io.casehub.drafthouse.debate.ThreadEntry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class ArbitrateHandler extends AbstractDebateSubAgentHandler {

    @Override
    SubTaskType taskType() { return SubTaskType.ARBITRATE; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        ReviewState state = currentState(request.channelId());
        ReviewPoint point = requirePoint(state, pointId);   // validates pointId; returns point
        String raiseContent = point.thread().get(0).content();
        String lastResponse = point.thread().stream()
                .filter(e -> e.type() == EntryType.DISPUTE
                          || e.type() == EntryType.QUALIFY
                          || e.type() == EntryType.COUNTER)
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
