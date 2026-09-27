package com.devopsaaas.agent.api;

import com.devopsaaas.agent.ExecutionCancellation;
import com.devopsaaas.agent.ExecutionQueries;
import com.devopsaaas.agent.ExecutionView;
import com.devopsaaas.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/executions")
class ExecutionController {

    private final ExecutionQueries queries;
    private final ExecutionCancellation cancellation;

    ExecutionController(ExecutionQueries queries, ExecutionCancellation cancellation) {
        this.queries = queries;
        this.cancellation = cancellation;
    }

    @GetMapping("/{executionId}")
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    ExecutionView get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID executionId) {
        return queries.get(user, executionId);
    }

    /** RF-23: cooperative; the step in progress finishes and is recorded, nothing runs after it. */
    @PostMapping("/{executionId}/cancel")
    @PreAuthorize("hasAuthority('AGENT_INTERACT')")
    ExecutionView cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID executionId) {
        return cancellation.cancel(user, executionId);
    }
}
