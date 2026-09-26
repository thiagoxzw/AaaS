package com.devopsaaas.tool.policy;

import java.util.UUID;

/**
 * Who asks, where, and how much budget is left. {@code requestedBy} is the user the agent acts for; the
 * agent never has more power than that user (docs/03-arquitetura.md, 2.2).
 */
public record PolicyContext(UUID organizationId, UUID environmentId, UUID requestedBy, int remainingToolCalls) {
}
