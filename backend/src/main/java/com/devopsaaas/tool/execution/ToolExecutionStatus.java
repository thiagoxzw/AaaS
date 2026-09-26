package com.devopsaaas.tool.execution;

/** Life cycle of one tool call (docs/03-arquitetura.md, section 6.2). */
public enum ToolExecutionStatus {
    PROPOSED,
    DENIED,
    WAITING_APPROVAL,
    REJECTED,
    EXPIRED,
    CANCELLED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** A side-effecting call may or may not have happened; the system does not pretend to know. */
    OUTCOME_UNKNOWN
}
