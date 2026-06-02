package io.casehub.drafthouse.debate;

public record ThreadEntry(String entryId, AgentType agent, int round,
                          EntryType type, String content) {}
