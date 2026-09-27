package com.devopsaaas.tool.container;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory container runtime for tests. Every call is recorded, so a test can prove that a denied or
 * pending tool call never reached the runtime. Unknown containers are RUNNING and HEALTHY unless a test says
 * otherwise.
 */
public class FakeContainerRuntime implements ContainerRuntime {

    private final Map<String, ContainerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, List<LogLine>> logs = new ConcurrentHashMap<>();
    private final Map<String, ContainerRuntimeException.Category> failures = new ConcurrentHashMap<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final Set<String> unreachableConnections = ConcurrentHashMap.newKeySet();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    public void setState(String containerName, ContainerState state, HealthStatus health) {
        missing.remove(containerName);
        snapshots.put(containerName, new ContainerSnapshot(containerName, state, health, null, false, 0,
                Instant.now(), null, "demo:latest"));
    }

    public void setSnapshot(String containerName, ContainerSnapshot snapshot) {
        missing.remove(containerName);
        snapshots.put(containerName, snapshot);
    }

    /** The container disappears from the runtime (it stays in the allowlist). */
    public void remove(String containerName) {
        missing.add(containerName);
    }

    public void setLogs(String containerName, List<LogLine> lines) {
        logs.put(containerName, List.copyOf(lines));
    }

    /** Every call on this container fails with the given category, as the real adapter would. */
    public void failWith(String containerName, ContainerRuntimeException.Category category) {
        failures.put(containerName, category);
    }

    public void makeUnreachable(String connectionRef) {
        unreachableConnections.add(connectionRef);
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
        refs.forEach(this::failIfScriptedCategory);
        return refs.stream()
                .map(ref -> missing.contains(ref.containerName())
                        ? ContainerSnapshot.notFound(ref.serviceName())
                        : snapshot(ref))
                .toList();
    }

    @Override
    public ContainerSnapshot inspect(ContainerRef ref) {
        calls.add("inspect:" + ref.containerName());
        failIfScripted(ref);
        return snapshot(ref);
    }

    @Override
    public ContainerLogs logs(ContainerRef ref, LogQuery query) {
        calls.add("logs:" + ref.containerName());
        failIfScripted(ref);
        List<LogLine> lines = logs.getOrDefault(ref.containerName(), List.of());
        int from = Math.max(0, lines.size() - query.tail());
        return new ContainerLogs(ref.serviceName(), lines.subList(from, lines.size()), from > 0);
    }

    @Override
    public void restart(ContainerRef ref, Duration gracefulStopTimeout) {
        calls.add("restart:" + ref.containerName());
        failIfScripted(ref);
    }

    @Override
    public RuntimeVersion version(String connectionRef) {
        calls.add("version:" + connectionRef);
        if (unreachableConnections.contains(connectionRef)) {
            throw new ContainerRuntimeException(ContainerRuntimeException.Category.UNAVAILABLE,
                    "Connection " + connectionRef + " is unreachable");
        }
        return new RuntimeVersion("29.3.1", "1.44");
    }

    private void failIfScripted(ContainerRef ref) {
        if (missing.contains(ref.containerName())) {
            throw new ContainerRuntimeException(ContainerRuntimeException.Category.NOT_FOUND, "No such container");
        }
        failIfScriptedCategory(ref);
    }

    /** Like the real adapter, {@code list} reports a missing container as NOT_FOUND instead of failing. */
    private void failIfScriptedCategory(ContainerRef ref) {
        ContainerRuntimeException.Category category = failures.get(ref.containerName());
        if (category != null) {
            throw new ContainerRuntimeException(category, "Scripted failure");
        }
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
    public static ContainerRef ref(UUID serviceId, String serviceName, String containerName) {
        return new ContainerRef(serviceId, serviceName, containerName, "local");
    }

    /** Test-only factory for a reference on a specific connection. */
    public static ContainerRef ref(UUID serviceId, String serviceName, String containerName, String connectionRef) {
        return new ContainerRef(serviceId, serviceName, containerName, connectionRef);
    }
}
