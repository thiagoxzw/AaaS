package com.devopsaaas.approval.api;

import com.devopsaaas.approval.ApprovalQueries;
import com.devopsaaas.approval.ApprovalStatus;
import com.devopsaaas.approval.ApprovalView;
import com.devopsaaas.approval.ApprovalWorkflow;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-41..43 and ADR-0006: an approval is decided only here, by an authenticated user holding APPROVAL_DECIDE
 * now. A "yes" typed in the chat, or any text from the model, approves nothing.
 */
@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    record DecisionRequest(@NotNull ApprovalWorkflow.Decision decision, @Size(max = 1000) String comment) {
    }

    private final ApprovalQueries queries;
    private final ApprovalWorkflow workflow;

    ApprovalController(ApprovalQueries queries, ApprovalWorkflow workflow) {
        this.queries = queries;
        this.workflow = workflow;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    List<ApprovalView> list(@AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) ApprovalStatus status) {
        return queries.list(user, Optional.ofNullable(status));
    }

    @GetMapping("/{approvalId}")
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    ApprovalView get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID approvalId) {
        return queries.get(user, approvalId);
    }

    /** 200 with the decided approval; the execution resumes on its own right after. 409 if not PENDING. */
    @PostMapping("/{approvalId}/decision")
    @PreAuthorize("hasAuthority('APPROVAL_DECIDE')")
    ApprovalView decide(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID approvalId,
            @Valid @RequestBody DecisionRequest request) {
        return switch (workflow.decide(user, approvalId, request.decision(), request.comment())) {
            case ApprovalWorkflow.Decided decided -> queries.view(decided.approval());
            case ApprovalWorkflow.NotPending notPending -> throw ApiException.conflict(
                    "The approval is " + notPending.status() + "; an approval can be decided only once.");
            case ApprovalWorkflow.ExpiredNow expired -> throw ApiException.conflict("The approval expired.");
        };
    }
}
