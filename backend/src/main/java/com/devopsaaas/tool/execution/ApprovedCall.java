package com.devopsaaas.tool.execution;

import java.util.UUID;

/**
 * A call a human approved, to be run exactly as it was recorded (slice 7). {@code approvedArgumentsHash} is
 * the copy kept by the approval: the executor compares it with the call's own hash and with the hash of the
 * stored arguments before running anything.
 */
public record ApprovedCall(UUID organizationId, UUID environmentId, UUID requestedBy, UUID toolExecutionId,
        String approvedArgumentsHash) {
}
