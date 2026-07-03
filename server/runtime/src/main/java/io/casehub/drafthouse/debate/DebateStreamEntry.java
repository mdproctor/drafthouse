package io.casehub.drafthouse.debate;

import java.time.Instant;
import java.util.Map;

import io.casehub.blocks.conversation.ConversationProtocol;
import io.casehub.blocks.conversation.Priority;
import io.casehub.qhorus.api.message.Message;

public record DebateStreamEntry(
        EntryType entryType,
        AgentType agentRole,
        int round,
        String content,
        String pointId,
        String subTaskId,
        Priority priority,
        String scope,
        String location,
        String sender,
        Instant timestamp) {

    public static DebateStreamEntry from(Message msg) {
        Map<String, String> meta = DebateProtocol.parseMeta(msg.content());
        String entryTypeStr = meta.get("entryType");
        if (entryTypeStr == null) return null;

        EntryType entryType;
        try {
            entryType = EntryType.valueOf(entryTypeStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Wire key changed from "agent" to "role" — try new key first, fall back for transition
        String agentStr = meta.get(ConversationProtocol.ROLE);
        if (agentStr == null) agentStr = meta.get("agent");
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
        String body = DebateProtocol.bodyContent(msg.content());

        boolean isSubTask = entryType == EntryType.SUB_TASK_REQUEST
                || entryType == EntryType.SUB_TASK_FINDING
                || entryType == EntryType.SUB_TASK_ERROR;

        String pointId = isSubTask ? meta.get("pointId") : msg.correlationId();
        String subTaskId = isSubTask ? msg.correlationId() : null;

        Priority priority = parsePriority(meta.get("priority"));
        String scope = meta.get("scope");
        String location = meta.get("location");

        return new DebateStreamEntry(
                entryType, agentRole, round, body,
                pointId, subTaskId,
                priority, scope,
                location != null && !location.isBlank() ? location : null,
                msg.sender(),
                msg.createdAt() != null ? msg.createdAt() : Instant.now());
    }

    private static Priority parsePriority(String s) {
        if (s == null) return null;
        // Handle both legacy ("P1"/"P2"/"P3") and current ("HIGH"/"MEDIUM"/"LOW") values
        return switch (s.toUpperCase()) {
            case "P1", "HIGH" -> Priority.HIGH;
            case "P2", "MEDIUM" -> Priority.MEDIUM;
            case "P3", "LOW" -> Priority.LOW;
            default -> null;
        };
    }
}
