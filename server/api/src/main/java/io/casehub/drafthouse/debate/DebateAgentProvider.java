package io.casehub.drafthouse.debate;

import io.casehub.blocks.channel.AgentTask;

public interface DebateAgentProvider {
    /**
     * Invoke an LLM and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread.
     */
    String analyse(AgentTask task);
}
