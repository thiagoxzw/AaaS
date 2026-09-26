package com.devopsaaas.tool.container;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory container runtime for tests. Every call is recorded, so a test can prove that a denied or
 * pending tool call never reached the runtime.
 */
public class FakeContainerRuntime implements ContainerRuntime {

    private final Map<String, ContainerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    public void setState(String containerName, ContainerState state, HealthStatus health) {
        snapshots.put(containerName, new ContainerSnapshot(containerName, state, health, null, false, 0,
                Instant.now(), null, "demo:latest"));
    }

    /** Calls in the form {@code "inspect:<container>"}, oldest first. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public long callsFor(String containerName) {
        return calls.stream().filter(call -> call.endsWith(":" + containerName)).count();
    }

    @Override
    public List<ContainerSnapshot> list(Collection<ContainerRef> refs) {
        refs.forEach(ref -> calls.add("list:" + ref.containerName()));
        return refs.stream().map(this::snapshot).toList();
    }

    @Override
    public ContainerSnapshot inspect(ContainerRef ref) {
        calls.add("inspect:" + ref.containerName());
        return snapshot(ref);
    }

    @Override
    public ContainerLogs logs(ContainerRef ref, LogQuery query) {
        calls.add("logs:" + ref.containerName());
        return new ContainerLogs(ref.serviceName(), List.of(), false);
    }

    @Override
    public void restart(ContainerRef ref, Duration gracefulStopTimeout) {
        calls.add("restart:" + ref.containerName());
    }

    private ContainerSnapshot snapshot(ContainerRef ref) {
        ContainerSnapshot known = snapshots.get(ref.containerName());
        ContainerSnapshot base = known != null ? known : new ContainerSnapshot(ref.containerName(),
                ContainerState.RUNNING, HealthStatus.HEALTHY, null, false, 0, Instant.now(), null, "demo:latest");
        // The LLM-facing name is the logical one, never the real container name.
        return new ContainerSnapshot(ref.serviceName(), base.state(), base.health(), base.exitCode(),
                base.oomKilled(), base.restartCount(), base.startedAt(), base.finishedAt(), base.image());
    }

    /** Test-only factory: production code can only obtain references through {@link TargetResolver}. */
    public static ContainerRef ref(java.util.UUID serviceId, String serviceName, String containerName) {
        return new ContainerRef(serviceId, serviceName, containerName);
    }
}
