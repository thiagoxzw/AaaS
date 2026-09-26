package com.devopsaaas.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.ApprovalRequirement;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.api.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** docs/05-contratos-das-ferramentas.md, section 3.1: an invalid definition prevents startup. */
class ToolRegistryTest {

    record ServiceInput(String service) implements ToolInput {
    }

    record MapInput(Map<String, String> anything) implements ToolInput {
    }

    private static Tool<ServiceInput> tool(UnaryOperator<ToolDefinition.Builder> customize) {
        ToolDefinition definition = customize.apply(ToolDefinition.builder("sampleTool")
                .description("A sample tool")
                .category(ToolCategory.CONTAINER)
                .riskLevel(RiskLevel.READ_ONLY)
                .requiredPermission(Permission.AGENT_INTERACT)
                .targetParameter("service")).build();
        return new Tool<>() {
            public ToolDefinition definition() {
                return definition;
            }

            public Class<ServiceInput> inputType() {
                return ServiceInput.class;
            }

            public ToolResult execute(ToolExecutionContext context, ServiceInput input) {
                return ToolResult.success(Map.of());
            }
        };
    }

    private static ToolRegistry registry(Tool<?>... tools) {
        return new ToolRegistry(List.of(tools), new FlatRecordSchemaGenerator());
    }

    @Test
    void validTool_isRegisteredWithItsSchema() {
        ToolRegistry registry = registry(tool(builder -> builder));

        assertThat(registry.find("sampleTool")).isPresent();
        assertThat(registry.find("sampleTool").orElseThrow().inputSchema()).containsKey("properties");
    }

    @Test
    void emptyCatalog_isValid() {
        assertThat(registry().all()).isEmpty();
    }

    @Test
    void retryableSideEffectTool_preventsStartup() {
        assertInvalid(tool(builder -> builder.riskLevel(RiskLevel.HIGH_RISK).impactDescription("x").retryable(true)),
                "only READ_ONLY tools may be retryable");
    }

    @Test
    void destructiveToolWithoutAlwaysApproval_preventsStartup() {
        assertInvalid(tool(builder -> builder.riskLevel(RiskLevel.DESTRUCTIVE).impactDescription("x")
                        .approvalRequirement(ApprovalRequirement.BY_POLICY)),
                "DESTRUCTIVE tools must require approval ALWAYS");
    }

    @Test
    void riskyToolWithoutImpactDescription_preventsStartup() {
        assertInvalid(tool(builder -> builder.riskLevel(RiskLevel.HIGH_RISK)), "impactDescription is required");
    }

    @Test
    void targetParameterThatIsNotAStringComponent_preventsStartup() {
        assertInvalid(tool(builder -> builder.targetParameter("container")), "targetParameter 'container'");
    }

    @Test
    void timeoutOutsideLimits_preventsStartup() {
        assertInvalid(tool(builder -> builder.timeout(Duration.ofMinutes(10))), "timeout must be between");
        assertInvalid(tool(builder -> builder.timeout(Duration.ofMillis(100))), "timeout must be between");
    }

    @Test
    void invalidNameOrDescription_preventsStartup() {
        assertInvalid(tool(builder -> ToolDefinition.builder("Bad-Name").description("d")
                .category(ToolCategory.CONTAINER).riskLevel(RiskLevel.READ_ONLY)
                .requiredPermission(Permission.AGENT_INTERACT)), "name must match");
        assertInvalid(tool(builder -> builder.description(" ")), "description must be non-blank");
    }

    @Test
    void nonFlatInput_preventsStartup() {
        Tool<MapInput> nested = new Tool<>() {
            public ToolDefinition definition() {
                return ToolDefinition.builder("nestedTool").description("d").category(ToolCategory.CONTAINER)
                        .riskLevel(RiskLevel.READ_ONLY).requiredPermission(Permission.AGENT_INTERACT).build();
            }

            public Class<MapInput> inputType() {
                return MapInput.class;
            }

            public ToolResult execute(ToolExecutionContext context, MapInput input) {
                return ToolResult.success(Map.of());
            }
        };
        assertInvalid(nested, "unsupported type");
    }

    @Test
    void duplicateNames_preventStartup() {
        assertThatThrownBy(() -> registry(tool(builder -> builder), tool(builder -> builder)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate tool name");
    }

    private static void assertInvalid(Tool<?> tool, String expectedProblem) {
        assertThatThrownBy(() -> registry(tool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid tool definitions")
                .hasMessageContaining(expectedProblem);
    }
}
