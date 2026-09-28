package com.devopsaaas.agent.api;

import com.devopsaaas.agent.ActionExplanation;
import com.devopsaaas.agent.ActionExplanations;
import com.devopsaaas.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RF-46 / H3 (slice 8): why an action happened, who allowed it, and what came out, in one read. */
@RestController
@RequestMapping("/api/v1/tool-executions")
class ToolExecutionController {

    private final ActionExplanations explanations;

    ToolExecutionController(ActionExplanations explanations) {
        this.explanations = explanations;
    }

    @GetMapping("/{toolExecutionId}")
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    ActionExplanation explain(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID toolExecutionId) {
        return explanations.explain(user, toolExecutionId);
    }
}
