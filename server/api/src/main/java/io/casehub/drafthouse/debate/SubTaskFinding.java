package io.casehub.drafthouse.debate;

public record SubTaskFinding(
        String subTaskId,
        SubTaskType taskType,
        String requestingAgent,   // "REV" or "IMP" — String not AgentType: sub-tasks may originate from future agent roles beyond the current two
        String pointId,           // null for NEUTRAL_SUMMARY and CUSTOM
        String finding,           // null while PENDING or on ERROR
        String errorReason,       // fixed sanitized string — never e.getMessage()
        SubTaskStatus status
) {}
