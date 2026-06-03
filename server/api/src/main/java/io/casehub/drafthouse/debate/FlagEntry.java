package io.casehub.drafthouse.debate;

/**
 * A human-escalation flag raised during a review debate.
 *
 * {@code entryId} and {@code round} are null/0 in the v1 file-based path — both are populated
 * only via the v2 DebateChannel (qhorus#230 / drafthouse#27), where the MessageView carries
 * a stable correlationId and round context.
 */
public record FlagEntry(String entryId, int round, AgentType agent, String content) {}
