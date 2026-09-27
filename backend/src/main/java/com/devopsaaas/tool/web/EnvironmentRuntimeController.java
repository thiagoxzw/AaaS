package com.devopsaaas.tool.web;

import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.container.EnvironmentRuntimeStatus;
import com.devopsaaas.tool.container.EnvironmentRuntimeStatus.Connectivity;
import com.devopsaaas.tool.container.EnvironmentRuntimeStatus.ServiceStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime reads for the API user (RF-12); the agent reaches the runtime only through tools. */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
class EnvironmentRuntimeController {

    private final EnvironmentRuntimeStatus status;

    EnvironmentRuntimeController(EnvironmentRuntimeStatus status) {
        this.status = status;
    }

    @PostMapping("/connectivity-check")
    @PreAuthorize("hasAuthority('ENVIRONMENT_MANAGE')")
    Connectivity connectivityCheck(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId) {
        return status.checkConnectivity(user, environmentId);
    }

    /** State and health only: no logs, no container names. */
    @GetMapping("/services/status")
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    List<ServiceStatus> servicesStatus(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId) {
        return status.servicesStatus(user, environmentId);
    }
}
