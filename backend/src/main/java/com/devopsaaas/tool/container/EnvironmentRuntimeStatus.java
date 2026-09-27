package com.devopsaaas.tool.container;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.container.TargetResolver.AllowlistedTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reads the runtime for the API itself, not for the agent (RF-12): a connectivity check and the live state
 * of the allowlisted services. These are user reads with a checked permission, scoped to the user's
 * organization and to the allowlist; they do not go through the tool executor and are not audited as agent
 * actions.
 */
@Service
public class EnvironmentRuntimeStatus {

    private final EnvironmentDirectory environments;
    private final TargetResolver targets;
    private final ContainerRuntime runtime;

    EnvironmentRuntimeStatus(EnvironmentDirectory environments, TargetResolver targets, ContainerRuntime runtime) {
        this.environments = environments;
        this.targets = targets;
        this.runtime = runtime;
    }

    /** {@code problem} is a category name, never a Docker message. */
    public record Connectivity(boolean reachable, String engineVersion, String apiVersion, long latencyMs,
            String problem) {
    }

    public record ServiceStatus(String service, String description, ContainerState state, HealthStatus health) {
    }

    public Connectivity checkConnectivity(CurrentUser user, UUID environmentId) {
        ActiveEnvironment environment = active(user, environmentId);
        long start = System.nanoTime();
        try {
            RuntimeVersion version = runtime.version(environment.connectionRef());
            return new Connectivity(true, version.engineVersion(), version.apiVersion(), elapsedMillis(start), null);
        } catch (ContainerRuntimeException exception) {
            return new Connectivity(false, null, null, elapsedMillis(start), exception.category().name());
        }
    }

    public List<ServiceStatus> servicesStatus(CurrentUser user, UUID environmentId) {
        active(user, environmentId);
        List<AllowlistedTarget> allowlist = targets.resolveAll(user.organizationId(), environmentId);
        if (allowlist.isEmpty()) {
            return List.of();
        }
        List<ContainerSnapshot> snapshots;
        try {
            snapshots = runtime.list(allowlist.stream().map(AllowlistedTarget::ref).toList());
        } catch (ContainerRuntimeException exception) {
            throw exception.category() == ContainerRuntimeException.Category.UNAVAILABLE
                    ? new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The container runtime is unavailable.")
                    : new ApiException(HttpStatus.BAD_GATEWAY, "The container runtime refused or failed the request.");
        }
        List<ServiceStatus> statuses = new ArrayList<>(allowlist.size());
        for (int i = 0; i < allowlist.size(); i++) {
            ContainerSnapshot snapshot = snapshots.get(i);
            statuses.add(new ServiceStatus(allowlist.get(i).ref().serviceName(), allowlist.get(i).description(),
                    snapshot.state(), snapshot.health()));
        }
        return statuses;
    }

    private ActiveEnvironment active(CurrentUser user, UUID environmentId) {
        return environments.findActive(user.organizationId(), environmentId)
                .orElseThrow(() -> ApiException.notFound("Environment not found."));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
