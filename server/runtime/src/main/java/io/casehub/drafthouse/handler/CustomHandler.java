package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.SubTaskType;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class CustomHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.CUSTOM; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        String body = DebateProtocol.bodyContent(request.message().content());
        if (body == null || body.isBlank())
            throw new IllegalArgumentException(
                    "CUSTOM requires customInput — message body must not be empty");
        return new AgentTask(
                "You are a focused analyst. Answer only the question posed. "
                + "You have no knowledge of the broader debate.",
                body
        );
    }
}
