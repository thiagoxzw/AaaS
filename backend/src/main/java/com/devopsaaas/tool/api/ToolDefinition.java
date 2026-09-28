package com.devopsaaas.tool.api;

import com.devopsaaas.shared.security.Permission;
import java.time.Duration;
import java.util.Objects;

/**
 * Declarative metadata of a tool (docs/05-contratos-das-ferramentas.md, section 3). Validated when the
 * application starts: an invalid definition prevents startup.
 *
 * @param targetParameter name of the input component that references an allowlisted service, or null
 * @param impactDescription trusted, deterministic text shown on approvals; required unless READ_ONLY
 * @param justificationParameter name of the input component that carries the model's justification, shown
 *        on approvals as untrusted; being an argument, it is bound by the arguments hash (slice 8), or null
 */
public record ToolDefinition(
        String name,
        int version,
        String description,
        ToolCategory category,
        RiskLevel riskLevel,
        Permission requiredPermission,
        ApprovalRequirement approvalRequirement,
        String targetParameter,
        Duration timeout,
        boolean retryable,
        String impactDescription,
        int maxOutputBytes,
        String justificationParameter) {

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public boolean hasTarget() {
        return targetParameter != null;
    }

    public static final class Builder {

        private final String name;
        private int version = 1;
        private String description;
        private ToolCategory category;
        private RiskLevel riskLevel;
        private Permission requiredPermission;
        private ApprovalRequirement approvalRequirement = ApprovalRequirement.BY_POLICY;
        private String targetParameter;
        private Duration timeout = Duration.ofSeconds(10);
        private boolean retryable;
        private String impactDescription;
        private int maxOutputBytes = 16 * 1024;
        private String justificationParameter;

        private Builder(String name) {
            this.name = name;
        }

        public Builder version(int value) {
            this.version = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder category(ToolCategory value) {
            this.category = value;
            return this;
        }

        public Builder riskLevel(RiskLevel value) {
            this.riskLevel = value;
            return this;
        }

        public Builder requiredPermission(Permission value) {
            this.requiredPermission = value;
            return this;
        }

        public Builder approvalRequirement(ApprovalRequirement value) {
            this.approvalRequirement = value;
            return this;
        }

        public Builder targetParameter(String value) {
            this.targetParameter = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder retryable(boolean value) {
            this.retryable = value;
            return this;
        }

        public Builder impactDescription(String value) {
            this.impactDescription = value;
            return this;
        }

        public Builder maxOutputBytes(int value) {
            this.maxOutputBytes = value;
            return this;
        }

        public Builder justificationParameter(String value) {
            this.justificationParameter = value;
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(Objects.requireNonNull(name), version, description, category, riskLevel,
                    requiredPermission, approvalRequirement, targetParameter, timeout, retryable,
                    impactDescription, maxOutputBytes, justificationParameter);
        }
    }
}
