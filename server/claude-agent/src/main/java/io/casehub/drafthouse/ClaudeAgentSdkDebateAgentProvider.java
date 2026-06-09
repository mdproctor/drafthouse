package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Claude CLI-backed DebateAgentProvider. Named per ARC42STORIES.MD.
 *
 * CDI displacement: this bean is plain @ApplicationScoped (no @Alternative, no @DefaultBean).
 * When this module is on the classpath, CDI's @DefaultBean displacement rule activates it:
 * a non-default bean displaces any @DefaultBean of the same type automatically, without
 * requiring quarkus.arc.selected-alternatives. Do NOT add @Alternative here — that would
 * require explicit configuration and break the classpath-presence activation contract.
 * See: ai-agent-provider-cdi-priority.md
 *
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
