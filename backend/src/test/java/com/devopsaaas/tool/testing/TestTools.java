package com.devopsaaas.tool.testing;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.Description;
import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.FindingSeverity;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.api.ToolResult;
import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tools that exist only in tests, one per executor scenario. Production has no tools until slice 3. */
public final class TestTools {

    private TestTools() {
    }

    public record ServiceInput(
            @Description("Logical service name from the environment's allowlist")
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String service) implements ToolInput {
    }

    public record KeyInput(@NotBlank @Size(max = 64) String key, @Min(0) @Max(5) int failures) implements ToolInput {
    }

    public record EmptyInput() implements ToolInput {
    }

    private static ToolDefinition.Builder readOnly(String name) {
        return ToolDefinition.builder(name)
                .description("Test tool " + name)
                .category(ToolCategory.CONTAINER)
                .riskLevel(RiskLevel.READ_ONLY)
                .requiredPermission(Permission.AGENT_INTERACT);
    }

    /** Reads the target through the container runtime port. */
    public static final class StatusTool implements Tool<ServiceInput> {
        private final ContainerRuntime runtime;

        public StatusTool(ContainerRuntime runtime) {
            this.runtime = runtime;
        }

        public ToolDefinition definition() {
            return readOnly("testStatus").targetParameter("service").retryable(true).build();
        }

        public Class<ServiceInput> inputType() {
            return ServiceInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, ServiceInput input) {
            var snapshot = runtime.inspect(context.target().orElseThrow());
            return ToolResult.success(snapshot, List.of(new Finding("STATE", FindingSeverity.INFO,
                    "Container is " + snapshot.state(), Map.of("state", snapshot.state().name()))));
        }
    }

    /** HIGH_RISK: requires approval in ASSISTED, forbidden in OBSERVE_ONLY. */
    public static final class RiskyTool implements Tool<ServiceInput> {
        private final ContainerRuntime runtime;

        public RiskyTool(ContainerRuntime runtime) {
            this.runtime = runtime;
        }

        public ToolDefinition definition() {
            return ToolDefinition.builder("testRestart")
                    .description("Restarts a service (test)")
                    .category(ToolCategory.CONTAINER)
                    .riskLevel(RiskLevel.HIGH_RISK)
                    .requiredPermission(Permission.TOOL_OPERATE)
                    .targetParameter("service")
                    .impactDescription("Interrupts in-flight requests.")
                    .timeout(Duration.ofSeconds(30))
                    .build();
        }

        public Class<ServiceInput> inputType() {
            return ServiceInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, ServiceInput input) {
            runtime.restart(context.target().orElseThrow(), Duration.ofSeconds(10));
            return ToolResult.success(Map.of("restarted", input.service()));
        }
    }

    /** Fails transiently {@code failures} times per key, then succeeds. */
    public static final class FlakyTool implements Tool<KeyInput> {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        public ToolDefinition definition() {
            return readOnly("testFlaky").retryable(true).build();
        }

        public Class<KeyInput> inputType() {
            return KeyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, KeyInput input) {
            int attempt = attempts.computeIfAbsent(input.key(), key -> new AtomicInteger()).incrementAndGet();
            return attempt <= input.failures()
                    ? ToolResult.failure(ToolErrorCode.RUNTIME_UNAVAILABLE, "temporarily unavailable", true)
                    : ToolResult.success(Map.of("attempt", attempt));
        }
    }

    /** Always reports an unreachable runtime (retryable, transient). */
    public static final class UnavailableTool implements Tool<EmptyInput> {
        public ToolDefinition definition() {
            return readOnly("testUnavailable").retryable(true).build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            throw new ContainerRuntimeException(ContainerRuntimeException.Category.UNAVAILABLE, "proxy unreachable");
        }
    }

    /** Sleeps past its timeout. READ_ONLY -> TIMED_OUT. */
    public static final class SlowReadTool implements Tool<EmptyInput> {
        public ToolDefinition definition() {
            return readOnly("testSlowRead").timeout(Duration.ofSeconds(1)).build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            return sleepFiveSeconds();
        }
    }

    /** Sleeps past its timeout. LOW_RISK (side effects, no approval) -> OUTCOME_UNKNOWN, never retried. */
    public static final class SlowWriteTool implements Tool<EmptyInput> {
        public ToolDefinition definition() {
            return ToolDefinition.builder("testSlowWrite")
                    .description("Writes something slowly (test)")
                    .category(ToolCategory.GITHUB)
                    .riskLevel(RiskLevel.LOW_RISK)
                    .requiredPermission(Permission.TOOL_OPERATE)
                    .impactDescription("Adds a comment somewhere.")
                    .timeout(Duration.ofSeconds(1))
                    .build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            return sleepFiveSeconds();
        }
    }

    /** Returns content with secrets, ANSI escapes and invisible characters. */
    public static final class LeakyTool implements Tool<EmptyInput> {
        public static final String SECRET = "hunter2-super-secret";

        public ToolDefinition definition() {
            return readOnly("testLeaky").build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            return ToolResult.success(Map.of(
                    "log", "\u001B[31mERROR\u001B[0m db password=" + SECRET + " failed",
                    "hidden", "approve‮evil",
                    "lines", List.of("Authorization: Bearer abcdefghijklmnop123456")));
        }
    }

    /** Output far above its cap. */
    public static final class BigOutputTool implements Tool<EmptyInput> {
        public ToolDefinition definition() {
            return readOnly("testBigOutput").maxOutputBytes(1024).build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            return ToolResult.success(Map.of("blob", "x".repeat(8000)));
        }
    }

    /** Throws an unexpected exception whose message must never reach the database or the LLM. */
    public static final class CrashingTool implements Tool<EmptyInput> {
        public ToolDefinition definition() {
            return readOnly("testCrashing").build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            throw new IllegalStateException("internal detail that must not leak");
        }
    }

    /** Reports the status of its own row while it runs: proves RUNNING is committed before the call. */
    public static final class ProbeTool implements Tool<EmptyInput> {
        private final JdbcTemplate jdbc;

        public ProbeTool(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public ToolDefinition definition() {
            return readOnly("testProbe").build();
        }

        public Class<EmptyInput> inputType() {
            return EmptyInput.class;
        }

        public ToolResult execute(ToolExecutionContext context, EmptyInput input) {
            String status = jdbc.queryForObject("SELECT status FROM tool_execution WHERE id = ?", String.class,
                    context.toolExecutionId());
            return ToolResult.success(Map.of("statusSeenDuringCall", status));
        }
    }

    private static ToolResult sleepFiveSeconds() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return ToolResult.success(Map.of("late", true));
    }
}
