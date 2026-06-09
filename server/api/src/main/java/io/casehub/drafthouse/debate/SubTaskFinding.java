package io.casehub.drafthouse.debate;

public record SubTaskFinding(
        String subTaskId,
        SubTaskType taskType,
        String requestingAgent,   // "REV" or "IMP"
        String pointId,           // null for NEUTRAL_SUMMARY and CUSTOM
        String finding,           // null while PENDING or on ERROR
        String errorReason,       // fixed sanitized string — never e.getMessage()
        SubTaskStatus status
) {}
