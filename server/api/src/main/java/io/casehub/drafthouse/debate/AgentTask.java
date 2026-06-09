package io.casehub.drafthouse.debate;

// systemPrompt before assembledInput — LLM API convention: system before user
public record AgentTask(String systemPrompt, String assembledInput) {}
