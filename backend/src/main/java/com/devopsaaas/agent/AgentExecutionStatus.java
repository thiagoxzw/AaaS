package com.devopsaaas.agent;

import java.util.EnumSet;
import java.util.Set;

/** docs/03-arquitetura.md, section 6.2. */
public enum AgentExecutionStatus {
    QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, BUDGET_EXCEEDED, INTERRUPTED, CANCELLED;

    /** The statuses covered by the "one active execution per conversation" index. */
    public static final Set<AgentExecutionStatus> ACTIVE = EnumSet.of(QUEUED, RUNNING, WAITING_APPROVAL);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
