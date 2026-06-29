package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.conversation.ConversationState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.stream.Collectors;

@ApplicationScoped
class NeutralSummaryHandler extends AbstractDebateSubAgentHandler {

    @Override String taskType() { return "NEUTRAL_SUMMARY"; }

    @Override
    public AgentTask prepareTask(ChannelAgentRequest request) {
        ConversationState state = currentState(request.channelId());
        String entries = state.points().values().stream()
                .map(p -> "[" + p.id() + "] " + p.thread().stream()
                        .map(e -> e.role() + "/" + e.entryType() + ": " + e.content())
                        .collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
        if (entries.isBlank()) entries = "(no debate entries)";
        return new AgentTask(
                "Summarise this debate neutrally. You have not participated in it.",
                "Debate entries:\n" + entries
        );
    }
}
