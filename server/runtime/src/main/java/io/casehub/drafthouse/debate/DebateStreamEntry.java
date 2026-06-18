package io.casehub.drafthouse.debate;

import java.time.Instant;
import java.util.Map;

import io.casehub.qhorus.runtime.message.Message;

public record DebateStreamEntry(
        EntryType entryType,
        AgentType agentRole,
        int round,
        String content,
        String pointId,
        String subTaskId,
        Priority priority,
        Scope scope,
        String location,
        String sender,
        Instant timestamp) {

    public static DebateStreamEntry from(Message msg) {
        Map<String, String> meta = DebateProtocol.parseMeta(msg.content);
        String entryTypeStr = meta.get("entryType");
        if (entryTypeStr == null) return null;

        EntryType entryType;
        try {
            entryType = EntryType.valueOf(entryTypeStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String agentStr = meta.get("agent");
        AgentType agentRole = null;
        if (agentStr != null) {
            try {
                agentRole = AgentType.valueOf(agentStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else if (entryType != EntryType.RESTART_CONTEXT) {
            return null;
        }

        int round = DebateProtocol.parseRound(meta);
        String body = DebateProtocol.bodyContent(msg.content);

        boolean isSubTask = entryType == EntryType.SUB_TASK_REQUEST
                || entryType == EntryType.SUB_TASK_FINDING
                || entryType == EntryType.SUB_TASK_ERROR;

        String pointId = isSubTask ? meta.get("pointId") : msg.correlationId;
        String subTaskId = isSubTask ? msg.correlationId : null;

        Priority priority = parsePriority(meta.get("priority"));
        Scope scope = parseScope(meta.get("scope"));
        String location = meta.get("location");

        return new DebateStreamEntry(
                entryType, agentRole, round, body,
                pointId, subTaskId,
                priority, scope,
                location != null && !location.isBlank() ? location : null,
                msg.sender,
                msg.createdAt != null ? msg.createdAt : Instant.now());
    }

    private static Priority parsePriority(String s) {
        if (s == null) return null;
        try { return Priority.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    private static Scope parseScope(String s) {
        if (s == null) return null;
        try { return Scope.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
