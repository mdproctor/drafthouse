package io.casehub.drafthouse.debate;

public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED,
    VERIFIED, DEFERRED,
    MEMO,               // per-round reasoning memo
    SUB_TASK_REQUEST,   // request for focused sub-agent analysis
    SUB_TASK_FINDING,   // sub-agent result (provenance: fresh context)
    SUB_TASK_ERROR,     // sub-agent execution failure
    RESTART_CONTEXT     // session branch marker (infrastructure provenance)
}
