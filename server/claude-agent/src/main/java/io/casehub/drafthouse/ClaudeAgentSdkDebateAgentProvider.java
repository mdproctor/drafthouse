package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Claude CLI-backed DebateAgentProvider. Named per ARC42STORIES.MD.
 * Activates by classpath presence; displaces LangChain4jDebateAgentProvider @DefaultBean.
 * Pending casehubio/platform#55 — stub until that ships.
 */
@ApplicationScoped
public class ClaudeAgentSdkDebateAgentProvider implements DebateAgentProvider {

    @Override
    public String analyse(AgentTask task) {
        throw new UnsupportedOperationException(
                "ClaudeAgentSdkDebateAgentProvider is a stub pending casehubio/platform#55.");
    }
}
