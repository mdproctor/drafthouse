package io.casehub.drafthouse.debate;

public record DebateEntry(
        EntryType type,
        String targetId,
        String content,
        ReviewStatus statusDirective,
        Priority priority,
        Scope scope,
        String location) {}
