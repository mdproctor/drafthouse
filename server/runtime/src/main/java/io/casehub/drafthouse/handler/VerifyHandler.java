package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.conversation.ConversationState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class VerifyHandler extends AbstractDebateSubAgentHandler {

    @Override
    String taskType() { return "VERIFY"; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        var session = requireSession(request.channelId());
        String specPath = requireSpecPath(session);
        ConversationState state = currentState(request.channelId());
        String claim = requirePointRaiseContent(state, pointId);
        String spec = readSpec(specPath);
        return new AgentTask(
                "You are a spec verifier. You have no knowledge of this debate's prior rounds. "
                + "Determine only whether this claim is supported by the spec. Be precise.",
                "Claim to verify:\n" + claim + "\n\nSpec:\n" + spec
        );
    }
}
