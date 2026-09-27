package com.devopsaaas.agent;

import java.util.UUID;

/** The immutable facts a worker needs, read once when the execution starts. */
record ExecutionState(UUID id, UUID organizationId, UUID conversationId, UUID environmentId, UUID requestedBy,
        int triggerSeq, String contextSnapshot) {

    ExecutionRef ref() {
        return new ExecutionRef(id, organizationId);
    }
}
