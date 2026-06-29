package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.drafthouse.debate.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
class ConsistencyCheckHandler extends AbstractDebateSubAgentHandler {

    @Override String taskType() { return "CONSISTENCY_CHECK"; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        String body = DebateProtocol.bodyContent(request.message().content());
        if (body == null || body.isBlank())
            throw new IllegalArgumentException(
                    "CONSISTENCY_CHECK requires proposed resolution text in the message body");
        ConversationState state = currentState(request.channelId());
        var counter = new AtomicInteger(1);
        String agreedList = state.points().values().stream()
                .filter(p -> "AGREED".equals(p.status()))
                .map(p -> counter.getAndIncrement() + ". [" + p.id() + "] " + p.thread().get(0).content())
                .collect(Collectors.joining("\n"));
        if (agreedList.isBlank()) agreedList = "(no agreed points yet)";
        return new AgentTask(
                "You have no memory of this debate. Determine only whether the proposed "
                + "resolution contradicts any of these prior agreements.",
                "Prior agreed points:\n" + agreedList + "\n\nProposed resolution:\n" + body
        );
    }
}
