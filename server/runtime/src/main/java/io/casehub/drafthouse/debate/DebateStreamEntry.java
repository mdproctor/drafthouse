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
        Instant timestamp,
        String commitHash,
        String documentPath) {

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
        } else if (entryType != EntryType.RESTART_CONTEXT
                && entryType != EntryType.ROUND_SNAPSHOT) {
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

        String commitHash = null;
        String documentPath = null;
        String displayContent = body;
        Instant ts = msg.createdAt() != null ? msg.createdAt() : Instant.now();
        if (entryType == EntryType.ROUND_SNAPSHOT) {
            commitHash = meta.get("commitHash");
            documentPath = meta.get("documentPath");
            String label = meta.get("label");
            if (label != null && !label.isBlank()) displayContent = label;
            String tsStr = meta.get("timestamp");
            if (tsStr != null) {
                try { ts = Instant.parse(tsStr); } catch (Exception ignored) {}
            }
        }

        return new DebateStreamEntry(
                entryType, agentRole, round, displayContent,
                pointId, subTaskId,
                priority, scope,
                location != null && !location.isBlank() ? location : null,
                msg.sender(), ts,
                commitHash, documentPath);
    }

    public static DebateStreamEntry from(io.casehub.qhorus.api.gateway.OutboundMessage msg) {
        if (msg.content() == null) return null;
        Map<String, String> meta = DebateProtocol.parseMeta(msg.content());
        String entryTypeStr = meta.get("entryType");
        if (entryTypeStr == null) return null;

        EntryType entryType;
        try {
            entryType = EntryType.valueOf(entryTypeStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String agentStr = meta.get(ConversationProtocol.ROLE);
        if (agentStr == null) agentStr = meta.get("agent");
        AgentType agentRole = null;
        if (agentStr != null) {
            try {
                agentRole = AgentType.valueOf(agentStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else if (entryType != EntryType.RESTART_CONTEXT
                && entryType != EntryType.ROUND_SNAPSHOT) {
            return null;
        }

        int round = DebateProtocol.parseRound(meta);
        String body = DebateProtocol.bodyContent(msg.content());

        boolean isSubTask = entryType == EntryType.SUB_TASK_REQUEST
                || entryType == EntryType.SUB_TASK_FINDING
                || entryType == EntryType.SUB_TASK_ERROR;

        String correlationId = msg.correlationId() != null ? msg.correlationId() : null;
        String pointId = isSubTask ? meta.get("pointId") : correlationId;
        String subTaskId = isSubTask ? correlationId : null;

        Priority priority = parsePriority(meta.get("priority"));
        String scope = meta.get("scope");
        String location = meta.get("location");

        String commitHash = null;
        String documentPath = null;
        String displayContent = body;
        Instant ts = Instant.now();
        if (entryType == EntryType.ROUND_SNAPSHOT) {
            commitHash = meta.get("commitHash");
            documentPath = meta.get("documentPath");
            String label = meta.get("label");
            if (label != null && !label.isBlank()) displayContent = label;
            String tsStr = meta.get("timestamp");
            if (tsStr != null) {
                try { ts = Instant.parse(tsStr); } catch (Exception ignored) {}
            }
        }

        return new DebateStreamEntry(
                entryType, agentRole, round, displayContent,
                pointId, subTaskId,
                priority, scope,
                location != null && !location.isBlank() ? location : null,
                msg.sender(), ts,
                commitHash, documentPath);
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
