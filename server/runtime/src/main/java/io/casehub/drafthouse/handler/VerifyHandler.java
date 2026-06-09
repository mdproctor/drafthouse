package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class VerifyHandler extends AbstractDebateSubAgentHandler {

    @Override
    SubTaskType taskType() { return SubTaskType.VERIFY; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        String pointId = meta.get("pointId");
        var session = requireSession(request.channelId());
        String specPath = requireSpecPath(session);
        ReviewState state = currentState(request.channelId());
        String claim = requirePointRaiseContent(state, pointId);
        String spec = readSpec(specPath);
        return new AgentTask(
                "You are a spec verifier. You have no knowledge of this debate's prior rounds. "
                + "Determine only whether this claim is supported by the spec. Be precise.",
                "Claim to verify:\n" + claim + "\n\nSpec:\n" + spec
        );
    }
}
