package com.devopsaaas.tool.execution;

import com.devopsaaas.shared.observability.RequestIdFilter;
import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.api.ToolResult;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.policy.PolicyDecision;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.devopsaaas.tool.policy.PolicyEngine;
import com.devopsaaas.tool.registry.RegisteredTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Runs one proposal through the policy and, when allowed, calls the tool with its limits: bounded
 * concurrency, a timeout, retries only for retryable (read-only) tools on transient failures, and output
 * cleaning before anything is stored. Outcomes follow docs/05-contratos-das-ferramentas.md, section 6.
 */
@Service
@EnableConfigurationProperties(ToolExecutionProperties.class)
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final String UNKNOWN_TOOL_TAG = "unknown";

    private final PolicyEngine policy;
    private final ToolExecutionJournal journal;
    private final OutputProcessor processor;
    private final ToolExecutionProperties properties;
    private final MeterRegistry meters;
    private final Semaphore slots;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    ToolExecutor(PolicyEngine policy, ToolExecutionJournal journal, OutputProcessor processor,
            ToolExecutionProperties properties, MeterRegistry meters) {
        this.policy = policy;
        this.journal = journal;
        this.processor = processor;
        this.properties = properties;
        this.meters = meters;
        this.slots = new Semaphore(properties.maxConcurrentExecutions(), true);
    }

    public ToolExecutionOutcome execute(ToolExecutionRequest request) {
        PolicyDecision decision = policy.evaluate(request.context(), request.proposal());
        return switch (decision.outcome()) {
            case DENY -> {
                ToolExecution execution = journal.denied(request, decision);
                meters.counter("devops.tool.denials", "reason", decision.denialReason().name()).increment();
                yield record(execution, tag(decision));
            }
            case REQUIRE_APPROVAL -> record(journal.awaitingApproval(request, decision), tag(decision));
            case ALLOW -> run(request, decision);
        };
    }

    /**
     * Slice 7: runs a call a human approved, exactly as it was recorded, after comparing the hashes and running
     * the policy again with the state of now. Empty when the call was no longer waiting (already taken by
     * another resumption, cancelled, rejected or expired): a second resumption never runs it twice.
     */
    public Optional<ToolExecutionOutcome> executeApproved(ApprovedCall call, UUID environmentId) {
        ToolExecutionJournal.Claim claim = journal.claimApproved(call, environmentId, policy::evaluateApproved);
        return switch (claim) {
            case ToolExecutionJournal.NotWaiting notWaiting -> Optional.empty();
            case ToolExecutionJournal.DeniedNow denied -> {
                meters.counter("devops.tool.denials", "reason", denied.execution().getDenialReason().name())
                        .increment();
                yield Optional.of(record(denied.execution(), denied.execution().getToolName()));
            }
            case ToolExecutionJournal.Claimed claimed -> Optional.of(runClaimed(claimed));
        };
    }

    private ToolExecutionOutcome runClaimed(ToolExecutionJournal.Claimed claimed) {
        ToolDefinition definition = claimed.decision().tool().definition();
        if (!acquireSlot(definition.timeout())) {
            return record(journal.finished(claimed.request(), claimed.execution().getId(), ToolExecutionStatus.FAILED,
                    0, null, ToolErrorCode.CAPACITY_EXCEEDED,
                    "No execution slot became available before the tool's timeout."), definition.name());
        }
        try {
            return invoke(claimed.request(), claimed.decision(), claimed.execution());
        } finally {
            slots.release();
        }
    }

    private ToolExecutionOutcome run(ToolExecutionRequest request, PolicyDecision decision) {
        RegisteredTool tool = decision.tool();
        ToolDefinition definition = tool.definition();
        if (!acquireSlot(definition.timeout())) {
            return record(journal.rejectedForCapacity(request, decision), tool.name());
        }
        try {
            return invoke(request, decision, journal.started(request, decision));
        } finally {
            slots.release();
        }
    }

    /** Calls the tool for a call already recorded as RUNNING, and records how it ended. */
    private ToolExecutionOutcome invoke(ToolExecutionRequest request, PolicyDecision decision, ToolExecution started) {
        RegisteredTool tool = decision.tool();
        ToolDefinition definition = tool.definition();
        Instant deadline = Instant.now().plus(definition.timeout());
        ToolExecutionContext context = new ToolExecutionContext(
                request.context().organizationId(),
                request.context().environmentId(),
                request.agentExecutionId(),
                started.getId(),
                request.context().requestedBy(),
                decision.resolvedTarget(),
                deadline,
                MDC.get(RequestIdFilter.MDC_KEY));

        Attempt attempt = invokeWithRetries(tool.tool(), definition, context, decision.input(), deadline);
        ToolExecution finished = finish(request, started, definition, attempt);
        meters.timer("devops.tool.duration", "tool", tool.name())
                .record(Duration.ofMillis(finished.getDurationMs() == null ? 0 : finished.getDurationMs()));
        return record(finished, tool.name());
    }

    // ---- invocation ------------------------------------------------------------------------------------

    private sealed interface Attempt {
        int attempts();
    }

    private record Completed(ToolResult result, int attempts) implements Attempt {
    }

    private record TimedOut(int attempts) implements Attempt {
    }

    private record Crashed(Throwable cause, int attempts) implements Attempt {
    }

    private Attempt invokeWithRetries(Tool<?> tool, ToolDefinition definition, ToolExecutionContext context,
            ToolInput input, Instant deadline) {
        long backoffMillis = properties.initialBackoff().toMillis();
        int attempts = 0;
        while (true) {
            attempts++;
            Attempt attempt = invokeOnce(tool, context, input, deadline, attempts);
            boolean transientFailure = switch (attempt) {
                case Completed completed -> completed.result() instanceof ToolResult.Failure failure
                        && failure.transientError();
                case Crashed crashed -> crashed.cause() instanceof ContainerRuntimeException runtime
                        && runtime.category() == ContainerRuntimeException.Category.UNAVAILABLE;
                case TimedOut timedOut -> false;
            };
            boolean retry = transientFailure
                    && definition.retryable()
                    && attempts <= properties.maxRetries()
                    && Instant.now().plusMillis(backoffMillis).isBefore(deadline);
            if (!retry) {
                return attempt;
            }
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return attempt;
            }
            backoffMillis *= 2;
        }
    }

    @SuppressWarnings("unchecked")
    private Attempt invokeOnce(Tool<?> tool, ToolExecutionContext context, ToolInput input, Instant deadline,
            int attempts) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            return new TimedOut(attempts);
        }
        Future<ToolResult> future = threads.submit(() -> ((Tool<ToolInput>) tool).execute(context, input));
        try {
            ToolResult result = future.get(remaining, TimeUnit.MILLISECONDS);
            return result == null
                    ? new Crashed(new IllegalStateException("Tool returned no result"), attempts)
                    : new Completed(result, attempts);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return new TimedOut(attempts);
        } catch (ExecutionException exception) {
            return new Crashed(exception.getCause(), attempts);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new Crashed(exception, attempts);
        }
    }

    // ---- outcome classification -------------------------------------------------------------------------

    private ToolExecution finish(ToolExecutionRequest request, ToolExecution started, ToolDefinition definition,
            Attempt attempt) {
        boolean sideEffects = definition.riskLevel() != RiskLevel.READ_ONLY;
        return switch (attempt) {
            case Completed(ToolResult.Success success, int attempts) -> {
                OutputProcessor.Processed output =
                        processor.processOutput(success.data(), success.findings(), definition.maxOutputBytes());
                countOutput(definition.name(), output);
                countFindings(definition.name(), success.findings());
                yield journal.finished(request, started.getId(), ToolExecutionStatus.SUCCEEDED, attempts, output,
                        null, null);
            }
            case Completed(ToolResult.Failure failure, int attempts) -> journal.finished(request, started.getId(),
                    ToolExecutionStatus.FAILED, attempts, null, failure.code(), failure.message());
            // A read-only call that ran out of time simply timed out. A side-effecting one may or may not have
            // happened: the executor does not pretend to know, and never retries it.
            case TimedOut timedOut -> journal.finished(request, started.getId(),
                    sideEffects ? ToolExecutionStatus.OUTCOME_UNKNOWN : ToolExecutionStatus.TIMED_OUT,
                    timedOut.attempts(), null, null, "The tool did not finish within its timeout.");
            case Crashed crashed -> crashed(request, started, definition, crashed, sideEffects);
            case Completed completed -> throw new IllegalStateException("Unexpected result " + completed);
        };
    }

    @SuppressFBWarnings(value = "CRLF_INJECTION_LOGS", justification = "Logs tool names, which the registry "
            + "validates against ^[a-z][a-zA-Z0-9]{2,63}$ at startup; logs are JSON-encoded as well")
    private ToolExecution crashed(ToolExecutionRequest request, ToolExecution started, ToolDefinition definition,
            Crashed crashed, boolean sideEffects) {
        if (crashed.cause() instanceof ContainerRuntimeException runtime) {
            switch (runtime.category()) {
                case NOT_FOUND:
                    return journal.finished(request, started.getId(), ToolExecutionStatus.FAILED, crashed.attempts(),
                            null, ToolErrorCode.TARGET_NOT_FOUND, "The container does not exist in the runtime.");
                case FORBIDDEN:
                    // The proxy refused an operation we expected to be allowed: a configuration error.
                    log.error("Container runtime refused tool {}: proxy configuration does not allow it",
                            definition.name());
                    meters.counter("devops.tool.runtime.forbidden", "tool", definition.name()).increment();
                    return journal.finished(request, started.getId(), ToolExecutionStatus.FAILED, crashed.attempts(),
                            null, ToolErrorCode.RUNTIME_FORBIDDEN, "The container runtime refused the operation.");
                default:
                    break;
            }
        }
        if (sideEffects) {
            log.warn("Side-effecting tool {} failed after the call may have been sent; outcome unknown",
                    definition.name(), crashed.cause());
            return journal.finished(request, started.getId(), ToolExecutionStatus.OUTCOME_UNKNOWN,
                    crashed.attempts(), null, null, "The call failed after it may have reached the runtime.");
        }
        boolean unavailable = crashed.cause() instanceof ContainerRuntimeException runtime
                && runtime.category() == ContainerRuntimeException.Category.UNAVAILABLE;
        if (!unavailable) {
            log.error("Tool {} failed unexpectedly", definition.name(), crashed.cause());
        }
        return journal.finished(request, started.getId(), ToolExecutionStatus.FAILED, crashed.attempts(), null,
                unavailable ? ToolErrorCode.RUNTIME_UNAVAILABLE : ToolErrorCode.INTERNAL_ERROR,
                unavailable ? "The container runtime is unavailable." : "The tool failed unexpectedly.");
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private boolean acquireSlot(Duration timeout) {
        try {
            return slots.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ToolExecutionOutcome record(ToolExecution execution, String toolTag) {
        meters.counter("devops.tool.executions",
                "tool", toolTag,
                "status", execution.getStatus().name(),
                "risk", Optional.ofNullable(execution.getRiskLevel()).map(Enum::name).orElse("NONE"))
                .increment();
        return new ToolExecutionOutcome(execution.getId(), execution.getStatus(), execution.getDenialReason(),
                execution.getErrorMessage(), execution.getOutput(), execution.isOutputTruncated(),
                execution.getErrorCode(), execution.getAttemptCount());
    }

    /** Codes come from the code base, never from runtime data, so the label cardinality stays bounded. */
    private void countFindings(String toolName, List<Finding> findings) {
        for (Finding finding : findings) {
            meters.counter("devops.tool.findings", "tool", toolName, "code", finding.code(),
                    "severity", finding.severity().name()).increment();
        }
    }

    private void countOutput(String toolName, OutputProcessor.Processed output) {
        if (output.truncated()) {
            meters.counter("devops.tool.output.truncated", "tool", toolName).increment();
        }
        if (output.redactions() > 0) {
            meters.counter("devops.tool.output.redactions", "tool", toolName).increment(output.redactions());
        }
    }

    /** Metric tag: only registered names, so names invented by an LLM cannot explode label cardinality. */
    private static String tag(PolicyDecision decision) {
        return decision.registeredTool().map(RegisteredTool::name).orElse(UNKNOWN_TOOL_TAG);
    }

    @PreDestroy
    void shutdown() {
        threads.shutdownNow();
    }
}
