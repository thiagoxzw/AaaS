package com.devopsaaas.environment.api;

import com.devopsaaas.environment.EnvironmentManagement;
import com.devopsaaas.environment.api.EnvironmentRequests.CreateEnvironment;
import com.devopsaaas.environment.api.EnvironmentRequests.UpdateEnvironment;
import com.devopsaaas.environment.api.EnvironmentResponses.EnvironmentView;
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
@RequestMapping("/api/v1/environments")
class EnvironmentController {

    private final EnvironmentManagement environments;

    EnvironmentController(EnvironmentManagement environments) {
        this.environments = environments;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENVIRONMENT_MANAGE')")
    ResponseEntity<EnvironmentView> create(@AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody CreateEnvironment request) {
        EnvironmentView created = EnvironmentView.from(environments.create(user, request.toCommand()));
        return ResponseEntity.created(URI.create("/api/v1/environments/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    List<EnvironmentView> list(@AuthenticationPrincipal CurrentUser user) {
        return environments.list(user).stream().map(EnvironmentView::from).toList();
    }

    @GetMapping("/{environmentId}")
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    EnvironmentView get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId) {
        return EnvironmentView.from(environments.get(user, environmentId));
    }

    @PatchMapping("/{environmentId}")
    @PreAuthorize("hasAuthority('ENVIRONMENT_MANAGE')")
    EnvironmentView update(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID environmentId,
            @Valid @RequestBody UpdateEnvironment request) {
        return EnvironmentView.from(environments.update(user, environmentId, request.toCommand()));
    }
}
