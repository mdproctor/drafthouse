package io.casehub.drafthouse;

import io.casehub.agency.agentic.AgentRef;
import io.casehub.agency.agentic.AgentResult;
import io.casehub.agency.agentic.model.AgentInvoker;
import io.casehub.agency.channel.AgentTask;
import io.casehub.agency.conversation.orchestration.AgentParticipant;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DebateAgentInvoker implements AgentInvoker<String> {

    private final DebateAgentProvider provider;
    private final Map<String, String> systemPrompts;

    public DebateAgentInvoker(DebateAgentProvider provider, List<AgentParticipant> participants) {
        this.provider = provider;
        this.systemPrompts = participants.stream()
                .collect(Collectors.toMap(AgentParticipant::agentId, AgentParticipant::systemPrompt));
    }

    @Override
    public Uni<AgentResult> invoke(AgentRef agent, String assembledInput) {
        return Uni.createFrom().item(() -> {
            var start = Instant.now();
            try {
                String systemPrompt = systemPrompts.getOrDefault(agent.name(), "");
                String response = provider.analyse(new AgentTask(systemPrompt, assembledInput));
                return new AgentResult(agent, response, Duration.between(start, Instant.now()),
                        AgentResult.AgentResultStatus.SUCCESS);
            } catch (Exception e) {
                return new AgentResult(agent, e.getMessage(), Duration.between(start, Instant.now()),
                        AgentResult.AgentResultStatus.FAILURE);
            }
        });
    }
}
