package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.stream.Collectors;

@ApplicationScoped
class NeutralSummaryHandler extends AbstractDebateSubAgentHandler {

    @Override SubTaskType taskType() { return SubTaskType.NEUTRAL_SUMMARY; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        ReviewState state = currentState(request.channelId());
        String entries = state.points().values().stream()
                .map(p -> "[" + p.id() + "] " + p.thread().stream()
                        .map(e -> e.agent() + "/" + e.type().name() + ": " + e.content())
                        .collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
        if (entries.isBlank()) entries = "(no debate entries)";
        return new AgentTask(
                "Summarise this debate neutrally. You have not participated in it.",
                "Debate entries:\n" + entries
        );
    }
}
