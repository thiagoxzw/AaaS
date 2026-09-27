package com.devopsaaas.tool.diagnostics;

import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.FindingSeverity;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.HealthStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic diagnosis of a container's state (RF-33, docs/05-contratos-das-ferramentas.md, 8.3): the
 * observed data goes through fixed rules and becomes findings with their evidence. The model never has to
 * guess what an exit code means, and an ambiguous signal is reported as ambiguous: exit code 137 without the
 * OOM flag is a SIGKILL of unknown origin, never "probably OOM".
 *
 * <p>A pure class: no Docker, no database, no Spring. The clock and the limits are given to it.
 */
public final class ContainerDiagnostics {

    private static final int SIGKILL = 137;
    private static final int SIGTERM = 143;

    private final DiagnosticsProperties properties;
    private final Clock clock;

    public ContainerDiagnostics(DiagnosticsProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Findings for one snapshot, most severe first. */
    public List<Finding> diagnose(ContainerSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        ContainerState state = snapshot.state();
        Integer exitCode = snapshot.exitCode();
        boolean running = state == ContainerState.RUNNING;

        if (state == ContainerState.NOT_FOUND) {
            findings.add(finding(ContainerFindingCode.CONTAINER_NOT_FOUND, FindingSeverity.HIGH,
                    "The service is in the allowlist, but its container does not exist in the runtime.", snapshot));
            return findings;
        }
        if (snapshot.oomKilled()) {
            findings.add(finding(ContainerFindingCode.OOM_KILLED, FindingSeverity.HIGH,
                    "The kernel killed the container for exceeding its memory limit (OOMKilled).", snapshot));
        } else if (exitCode != null && exitCode == SIGKILL) {
            findings.add(finding(ContainerFindingCode.KILLED_BY_SIGKILL, FindingSeverity.HIGH,
                    "The container was killed by SIGKILL (exit code 137) and the OOM flag is not set. It may have "
                            + "been an out-of-memory kill, a docker kill, or a docker stop that exceeded its grace "
                            + "period: this is not conclusive.", snapshot));
        }
        if (state == ContainerState.EXITED && exitCode != null) {
            if (exitCode == 0) {
                findings.add(finding(ContainerFindingCode.STOPPED, FindingSeverity.MEDIUM,
                        "The container stopped normally (exit code 0).", snapshot));
            } else if (exitCode == SIGTERM) {
                findings.add(finding(ContainerFindingCode.STOPPED, FindingSeverity.MEDIUM,
                        "The container was stopped by SIGTERM (exit code 143), usually a docker stop.", snapshot));
            } else if (exitCode != SIGKILL) {
                findings.add(finding(ContainerFindingCode.EXITED_WITH_ERROR, FindingSeverity.HIGH,
                        "The container exited with a non-zero exit code.", snapshot));
            }
        }
        // Docker keeps the last health of a stopped container: only a running one can be "unhealthy".
        if (running && snapshot.health() == HealthStatus.UNHEALTHY) {
            findings.add(finding(ContainerFindingCode.UNHEALTHY, FindingSeverity.HIGH,
                    "The container is running, but its healthcheck reports unhealthy.", snapshot));
        }
        if (state == ContainerState.RESTARTING) {
            findings.add(finding(ContainerFindingCode.RESTART_LOOP, FindingSeverity.MEDIUM,
                    "The container is being restarted by its restart policy.", snapshot));
        } else if (snapshot.restartCount() >= properties.restartLoopThreshold()) {
            findings.add(finding(ContainerFindingCode.RESTART_LOOP, FindingSeverity.MEDIUM,
                    "The restart policy has restarted the container " + snapshot.restartCount() + " times.",
                    snapshot));
        }
        if (running && snapshot.startedAt() != null
                && Duration.between(snapshot.startedAt(), Instant.now(clock))
                        .compareTo(properties.recentlyStartedWindow()) < 0) {
            findings.add(finding(ContainerFindingCode.RECENTLY_STARTED, FindingSeverity.INFO,
                    "The container started less than " + properties.recentlyStartedWindow().toSeconds()
                            + " seconds ago; its state may still be settling.", snapshot));
        }
        if (running && snapshot.health() == HealthStatus.NONE) {
            findings.add(finding(ContainerFindingCode.NO_HEALTHCHECK, FindingSeverity.INFO,
                    "The container has no healthcheck: running does not mean the application is healthy.",
                    snapshot));
        }
        findings.sort(Comparator.comparing(Finding::severity, Comparator.reverseOrder())
                .thenComparing(Finding::code));
        return findings;
    }

    /** The evidence is the observed data behind the finding, so a reader sees the facts, not only the verdict. */
    private static Finding finding(ContainerFindingCode code, FindingSeverity severity, String message,
            ContainerSnapshot snapshot) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("service", snapshot.serviceName());
        evidence.put("state", snapshot.state().name());
        if (snapshot.health() != null) {
            evidence.put("health", snapshot.health().name());
        }
        if (snapshot.exitCode() != null) {
            evidence.put("exitCode", snapshot.exitCode().toString());
        }
        evidence.put("oomKilled", Boolean.toString(snapshot.oomKilled()));
        evidence.put("restartCount", Integer.toString(snapshot.restartCount()));
        if (snapshot.startedAt() != null) {
            evidence.put("startedAt", snapshot.startedAt().toString());
        }
        return new Finding(code.name(), severity, message, evidence);
    }
}
