package io.casehub.drafthouse;

import io.casehub.blocks.channel.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@DefaultBean
@ApplicationScoped
public class PlatformDebateAgentProvider implements DebateAgentProvider {

    @Inject AgentProvider agentProvider;

    @Override
    public String analyse(AgentTask task) {
        AgentSessionConfig config = AgentSessionConfig.of(task.systemPrompt(), task.assembledInput());
        StringBuilder sb = new StringBuilder();
        agentProvider.invoke(config)
                .subscribe().asStream()
                .forEach(event -> {
                    if (event instanceof AgentEvent.TextDelta td) {
                        sb.append(td.text());
                    }
                });
        return sb.toString();
    }
}
