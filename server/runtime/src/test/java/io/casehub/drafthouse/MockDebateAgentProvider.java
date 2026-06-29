package io.casehub.drafthouse;

import io.casehub.blocks.channel.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative @Priority(1) @ApplicationScoped
public class MockDebateAgentProvider implements DebateAgentProvider {
    @Override
    public String analyse(AgentTask task) {
        return "Mock sub-agent finding.";
    }
}
