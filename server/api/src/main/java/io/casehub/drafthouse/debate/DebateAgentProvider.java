package io.casehub.drafthouse.debate;

public interface DebateAgentProvider {
    /**
     * Invoke an LLM and return the complete text response.
     * Blocking — callers must be on a non-event-loop thread.
     */
    String analyse(AgentTask task);
}
