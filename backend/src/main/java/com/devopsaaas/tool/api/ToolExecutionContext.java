package com.devopsaaas.tool.api;

import com.devopsaaas.tool.container.ContainerRef;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a tool receives besides its input. No secrets, repositories or database access: tools are thin, and
 * persistence, auditing and limits belong to the executor.
 *
 * @param target the allowlisted container, already resolved by the policy, when the tool has a target
 */
public record ToolExecutionContext(
        UUID organizationId,
        UUID environmentId,
        UUID agentExecutionId,
        UUID toolExecutionId,
        UUID requestedBy,
        Optional<ContainerRef> target,
        Instant deadline,
        String traceId) {
}
