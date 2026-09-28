package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.policy.PolicyContext;
import com.devopsaaas.tool.policy.PolicyDecision;
import com.devopsaaas.tool.policy.PolicyEngine;
import com.devopsaaas.tool.policy.PolicyOutcome;
import com.devopsaaas.tool.policy.ToolProposal;
import com.devopsaaas.tool.registry.RegisteredTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Slice 9a, the one tool-call transition no other test reached: PROPOSED → FAILED (CAPACITY_EXCEEDED). The
 * policy allows the call, but no execution slot frees up within the tool's timeout: the call is recorded as
 * failed, and neither RUNNING is recorded nor the tool invoked.
 */
class ToolExecutorCapacityTest {

    @Test
    @SuppressWarnings("unchecked")
    void withoutAFreeSlot_theCallFailsAsCapacityExceeded_andTheToolNeverRuns() {
        PolicyEngine policy = mock(PolicyEngine.class);
        ToolExecutionJournal journal = mock(ToolExecutionJournal.class);
        Tool<ToolInput> tool = mock(Tool.class);
        ToolDefinition definition = ToolDefinition.builder("busyTool")
                .description("A tool that never gets a slot.")
                .category(ToolCategory.CONTAINER)
                .riskLevel(RiskLevel.READ_ONLY)
                .requiredPermission(Permission.AGENT_INTERACT)
                .timeout(Duration.ofMillis(50))
                .build();
        PolicyDecision allowed = new PolicyDecision(PolicyOutcome.ALLOW, null, null,
                new RegisteredTool(tool, definition, Map.of()), null, null, "{}", "hash");
        ToolExecutionRequest request = new ToolExecutionRequest(
                new PolicyContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10), UUID.randomUUID(),
                UUID.randomUUID(), 1, new ToolProposal("busyTool", "{}", "call-1", null));
        ToolExecution failed = mock(ToolExecution.class);
        when(failed.getStatus()).thenReturn(ToolExecutionStatus.FAILED);
        when(failed.getErrorCode()).thenReturn(ToolErrorCode.CAPACITY_EXCEEDED);
        when(policy.evaluate(any(), any())).thenReturn(allowed);
        when(journal.rejectedForCapacity(request, allowed)).thenReturn(failed);
        // No slot at all: every acquisition waits the tool's timeout and gives up.
        ToolExecutor executor = new ToolExecutor(policy, journal, mock(OutputProcessor.class),
                new ToolExecutionProperties(0, 0, Duration.ZERO), new SimpleMeterRegistry());

        ToolExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.CAPACITY_EXCEEDED);
        verify(journal).rejectedForCapacity(request, allowed);
        verify(journal, never()).started(any(), any());
        verifyNoInteractions(tool);
    }
}
