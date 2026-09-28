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
        List<LogLine> lines = logs.getOrDefault(ref.containerName(), List.of()).stream()
                .filter(line -> query.since() == null || !line.timestamp().isBefore(query.since()))
                .toList();
        int from = Math.max(0, lines.size() - query.tail());
        return new ContainerLogs(ref.serviceName(), lines.subList(from, lines.size()), from > 0);
    }

    /** What a container looks like after a restart (slice 8). The default is RUNNING and HEALTHY. */
    public enum AfterRestart {
        HEALTHY, NO_HEALTHCHECK, UNHEALTHY, EXITS, STAYS_STARTING,
        /** The runtime answers, but the container never starts again: its StartedAt does not move. */
        NO_NEW_START
    }

    private final Map<String, AfterRestart> afterRestart = new ConcurrentHashMap<>();
    private final Map<String, ContainerRuntimeException.Category> restartFailures = new ConcurrentHashMap<>();

    public void afterRestart(String containerName, AfterRestart behaviour) {
        afterRestart.put(containerName, behaviour);
    }

    /** The restart call itself fails (for example the connection drops after the request was sent). */
    public void failRestartWith(String containerName, ContainerRuntimeException.Category category) {
        restartFailures.put(containerName, category);
    }

    @Override
    public void restart(ContainerRef ref, Duration gracefulStopTimeout) {
        calls.add("restart:" + ref.containerName());
        failIfScripted(ref);
        ContainerRuntimeException.Category failure = restartFailures.get(ref.containerName());
        if (failure != null) {
            throw new ContainerRuntimeException(failure, "Scripted restart failure");
        }
        AfterRestart behaviour = afterRestart.getOrDefault(ref.containerName(), AfterRestart.HEALTHY);
        if (behaviour == AfterRestart.NO_NEW_START) {
            return;
        }
        ContainerSnapshot base = snapshot(ref);
        // Strictly later than the previous start, as Docker's StartedAt would be.
        Instant previous = base.startedAt() == null ? Instant.EPOCH : base.startedAt();
        Instant started = Instant.now().isAfter(previous) ? Instant.now() : previous.plusMillis(1);
        ContainerState state = behaviour == AfterRestart.EXITS ? ContainerState.EXITED : ContainerState.RUNNING;
        HealthStatus health = switch (behaviour) {
            case NO_HEALTHCHECK, EXITS -> HealthStatus.NONE;
            case UNHEALTHY -> HealthStatus.UNHEALTHY;
            case STAYS_STARTING -> HealthStatus.STARTING;
            default -> HealthStatus.HEALTHY;
        };
        snapshots.put(ref.containerName(), new ContainerSnapshot(ref.containerName(), state, health,
                behaviour == AfterRestart.EXITS ? 1 : null, false, base.restartCount(), started,
                behaviour == AfterRestart.EXITS ? started : null, base.image()));
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
        // The default is created once per container, so its StartedAt is stable like a real container's.
        ContainerSnapshot base = snapshots.computeIfAbsent(ref.containerName(), name -> new ContainerSnapshot(name,
                ContainerState.RUNNING, HealthStatus.HEALTHY, null, false, 0, Instant.now(), null, "demo:latest"));
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
