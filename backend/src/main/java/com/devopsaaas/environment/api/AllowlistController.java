package com.devopsaaas.environment.api;

import com.devopsaaas.environment.EnvironmentManagement;
import com.devopsaaas.environment.api.EnvironmentRequests.CreateService;
import com.devopsaaas.environment.api.EnvironmentRequests.UpdateService;
import com.devopsaaas.environment.api.EnvironmentResponses.ServiceView;
import com.devopsaaas.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/services")
class AllowlistController {

    private final EnvironmentManagement environments;

    AllowlistController(EnvironmentManagement environments) {
        this.environments = environments;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENVIRONMENT_MANAGE')")
    ResponseEntity<ServiceView> add(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId,
            @Valid @RequestBody CreateService request) {
        ServiceView created = ServiceView.from(environments.addService(user, environmentId, request.toCommand()));
        return ResponseEntity
                .created(URI.create("/api/v1/environments/" + environmentId + "/services/" + created.id()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    List<ServiceView> list(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId) {
        return environments.listServices(user, environmentId).stream().map(ServiceView::from).toList();
    }

    @PatchMapping("/{serviceId}")
    @PreAuthorize("hasAuthority('ENVIRONMENT_MANAGE')")
    ServiceView update(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId,
            @PathVariable UUID serviceId, @Valid @RequestBody UpdateService request) {
        return ServiceView.from(environments.updateService(user, environmentId, serviceId, request.toCommand()));
    }
}
