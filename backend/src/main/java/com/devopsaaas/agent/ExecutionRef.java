package com.devopsaaas.agent;

import java.util.UUID;

/** What the dispatcher hands to a worker: an execution, always with its organization. */
record ExecutionRef(UUID executionId, UUID organizationId) {
}
