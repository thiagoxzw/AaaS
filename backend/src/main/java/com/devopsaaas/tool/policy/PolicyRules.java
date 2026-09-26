package com.devopsaaas.tool.policy;

import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.tool.api.ApprovalRequirement;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolDefinition;

/**
 * The risk x autonomy matrix (docs/03-arquitetura.md, 5.3), shared by the policy engine and the catalog
 * filter so what a user sees and what they may run can never disagree.
 */
public final class PolicyRules {

    private PolicyRules() {
    }

    /** OBSERVE_ONLY allows read-only tools only. */
    public static boolean permitsAutonomy(RiskLevel risk, AutonomyLevel autonomy) {
        return autonomy != AutonomyLevel.OBSERVE_ONLY || risk == RiskLevel.READ_ONLY;
    }

    /**
     * HIGH_RISK and DESTRUCTIVE always need a human decision until pre-authorization rules exist (RF-72,
     * post-MVP); a tool may also demand approval ALWAYS regardless of risk.
     */
    public static boolean requiresApproval(ToolDefinition definition, AutonomyLevel autonomy) {
        if (definition.approvalRequirement() == ApprovalRequirement.ALWAYS) {
            return true;
        }
        return switch (definition.riskLevel()) {
            case READ_ONLY, LOW_RISK -> false;
            case HIGH_RISK, DESTRUCTIVE -> true;
        };
    }
}
