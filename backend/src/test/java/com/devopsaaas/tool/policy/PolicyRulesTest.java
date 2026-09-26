package com.devopsaaas.tool.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.ApprovalRequirement;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The full risk x autonomy matrix of docs/03-arquitetura.md, section 5.3. */
class PolicyRulesTest {

    @ParameterizedTest(name = "{0} in {1}: allowed={2}, approval={3}")
    @CsvSource({
            "READ_ONLY,   OBSERVE_ONLY, true,  false",
            "LOW_RISK,    OBSERVE_ONLY, false, false",
            "HIGH_RISK,   OBSERVE_ONLY, false, true",
            "DESTRUCTIVE, OBSERVE_ONLY, false, true",
            "READ_ONLY,   ASSISTED,     true,  false",
            "LOW_RISK,    ASSISTED,     true,  false",
            "HIGH_RISK,   ASSISTED,     true,  true",
            "DESTRUCTIVE, ASSISTED,     true,  true",
            "HIGH_RISK,   AUTOMATED,    true,  true",
            "DESTRUCTIVE, AUTOMATED,    true,  true"
    })
    void matrix(RiskLevel risk, AutonomyLevel autonomy, boolean allowed, boolean approval) {
        ToolDefinition definition = ToolDefinition.builder("anyTool").description("d")
                .category(ToolCategory.CONTAINER).riskLevel(risk).requiredPermission(Permission.AGENT_INTERACT)
                .build();

        assertThat(PolicyRules.permitsAutonomy(risk, autonomy)).isEqualTo(allowed);
        assertThat(PolicyRules.requiresApproval(definition, autonomy)).isEqualTo(approval);
    }

    @ParameterizedTest
    @CsvSource({"READ_ONLY", "LOW_RISK"})
    void approvalAlways_isRequiredWhateverTheRisk(RiskLevel risk) {
        ToolDefinition definition = ToolDefinition.builder("anyTool").description("d")
                .category(ToolCategory.CONTAINER).riskLevel(risk).requiredPermission(Permission.AGENT_INTERACT)
                .approvalRequirement(ApprovalRequirement.ALWAYS).build();

        assertThat(PolicyRules.requiresApproval(definition, AutonomyLevel.ASSISTED)).isTrue();
    }
}
