package com.devopsaaas.tool.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.shared.security.PermissionLookup;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.TargetResolver;
import com.devopsaaas.tool.registry.FlatRecordSchemaGenerator;
import com.devopsaaas.tool.registry.ToolRegistry;
import com.devopsaaas.tool.testing.TestTools;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Order of the validation chain and fail-closed behaviour, without Spring or a database. */
class PolicyEngineTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final EnvironmentDirectory environments = mock(EnvironmentDirectory.class);
    private final TargetResolver targets = mock(TargetResolver.class);
    private final PermissionLookup permissions = mock(PermissionLookup.class);
    private final PolicyEngine engine = new PolicyEngine(environments,
            new ToolRegistry(List.of(new TestTools.StatusTool(new FakeContainerRuntime())), new FlatRecordSchemaGenerator()),
            new ArgumentBinder(Validation.buildDefaultValidatorFactory().getValidator()), targets, permissions);

    @Test
    void missingEnvironment_winsOverEverythingElse() {
        when(environments.findActive(ORG, ENV)).thenReturn(Optional.empty());

        assertThat(evaluate("noSuchTool", "garbage").denialReason()).isEqualTo(DenialReason.ENVIRONMENT_UNAVAILABLE);
    }

    @Test
    void invalidArguments_areReportedBeforeTargetAndPermission() {
        activeEnvironment(AutonomyLevel.ASSISTED);
        when(permissions.currentPermissions(ORG, USER)).thenReturn(Set.of());

        assertThat(evaluate("testStatus", "{\"service\":\"NOT VALID\"}").denialReason())
                .isEqualTo(DenialReason.INVALID_ARGUMENTS);
    }

    @Test
    void target_isCheckedBeforePermission() {
        activeEnvironment(AutonomyLevel.ASSISTED);
        when(targets.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(permissions.currentPermissions(ORG, USER)).thenReturn(Set.of());

        assertThat(evaluate("testStatus", "{\"service\":\"postgres\"}").denialReason())
                .isEqualTo(DenialReason.RESOURCE_NOT_ALLOWED);
    }

    @Test
    void anUnexpectedError_failsClosed() {
        activeEnvironment(AutonomyLevel.ASSISTED);
        when(targets.resolve(any(), any(), any()))
                .thenReturn(Optional.of(FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", "c")));
        when(permissions.currentPermissions(ORG, USER)).thenThrow(new IllegalStateException("database down"));

        PolicyDecision decision = evaluate("testStatus", "{\"service\":\"demo-api\"}");

        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
        assertThat(decision.denialReason()).isEqualTo(DenialReason.POLICY_ERROR);
    }

    @Test
    void allowedProposal_carriesTheValidatedInputTargetAndArgumentHash() {
        activeEnvironment(AutonomyLevel.ASSISTED);
        when(targets.resolve(any(), any(), any()))
                .thenReturn(Optional.of(FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", "c")));
        when(permissions.currentPermissions(ORG, USER)).thenReturn(Set.of(Permission.AGENT_INTERACT));

        PolicyDecision decision = evaluate("testStatus", "{ \"service\" : \"demo-api\" }");

        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.ALLOW);
        assertThat(decision.canonicalArguments()).isEqualTo("{\"service\":\"demo-api\"}");
        assertThat(decision.argumentsHash()).hasSize(64);
        assertThat(decision.resolvedTarget()).isPresent();
    }

    private void activeEnvironment(AutonomyLevel autonomy) {
        when(environments.findActive(ORG, ENV))
                .thenReturn(Optional.of(new EnvironmentDirectory.ActiveEnvironment(ENV, ORG, autonomy)));
    }

    private PolicyDecision evaluate(String tool, String arguments) {
        return engine.evaluate(new PolicyContext(ORG, ENV, USER, 10), new ToolProposal(tool, arguments, "c", null));
    }
}
