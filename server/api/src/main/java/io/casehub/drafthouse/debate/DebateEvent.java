package io.casehub.drafthouse.debate;

public sealed interface DebateEvent
        permits DebateEvent.RaiseEvent, DebateEvent.ResponseEvent,
                DebateEvent.FlagHumanEvent, DebateEvent.AgentMemo {

    /**
     * A new point raised. entryId is null when produced by an agent (assigned later
     * by DebateEntryFormatter); non-null when parsed from existing debate.md by DebateParser.
     */
    record RaiseEvent(
            String entryId,
            int round,
            AgentType agent,
            Priority priority,
            Scope scope,
            String location,
            String content) implements DebateEvent {}

    record ResponseEvent(
            int round,
            AgentType agent,
            String targetId,
            EntryType type,
            String content,
            ReviewStatus statusDirective) implements DebateEvent {}

    record FlagHumanEvent(
            int round,
            AgentType agent,
            String content,
            String targetId,
            ReviewStatus statusDirective) implements DebateEvent {}

    record AgentMemo(int round, AgentType agent, String content) implements DebateEvent {}
}
