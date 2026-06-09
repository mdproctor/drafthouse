package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.ReviewPoint;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
class DeepAnalysisHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.DEEP_ANALYSIS; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        Map<String, String> meta = metaFrom(request);
        var session = requireSession(request.channelId());
        String specPath = requireSpecPath(session);
        String spec = readSpec(specPath);
        // pointId is optional for DEEP_ANALYSIS — used for location hint only
        String focusHint = "(no section indicated)";
        String pointId = meta.get("pointId");
        if (pointId != null) {
            ReviewState state = currentState(request.channelId());
            ReviewPoint p = state.points().get(pointId);
            if (p != null && p.classification().location() != null) {
                focusHint = p.classification().location();
            }
        }
        return new AgentTask(
                "You are a spec analyst reading this spec with fresh eyes. "
                + "Focus on the indicated section. Identify issues.",
                "Focus section: " + focusHint + "\n\nFull spec:\n" + spec
        );
    }
}
