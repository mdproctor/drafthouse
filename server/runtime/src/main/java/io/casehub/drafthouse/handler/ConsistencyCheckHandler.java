package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.stream.Collectors;

@ApplicationScoped
class ConsistencyCheckHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.CONSISTENCY_CHECK; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        String body = DebateProtocol.bodyContent(request.message().content());
        if (body == null || body.isBlank())
            throw new IllegalArgumentException(
                    "CONSISTENCY_CHECK requires customInput (proposed resolution text) in the message body");
        ReviewState state = currentState(request.channelId());
        int[] idx = {1};
        String agreedList = state.points().values().stream()
                .filter(p -> p.currentStatus() == ReviewStatus.AGREED)
                .map(p -> idx[0]++ + ". [" + p.id() + "] " + p.thread().get(0).content())
                .collect(Collectors.joining("\n"));
        if (agreedList.isBlank()) agreedList = "(no agreed points yet)";
        return new AgentTask(
                "You have no memory of this debate. Determine only whether the proposed "
                + "resolution contradicts any of these prior agreements.",
                "Prior agreed points:\n" + agreedList + "\n\nProposed resolution:\n" + body
        );
    }
}
