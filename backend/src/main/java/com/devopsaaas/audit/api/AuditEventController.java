package com.devopsaaas.audit.api;

import com.devopsaaas.audit.AuditQuery;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.shared.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/audit-events")
class AuditEventController {

    private final AuditQuery auditQuery;

    AuditEventController(AuditQuery auditQuery) {
        this.auditQuery = auditQuery;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    AuditPageResponse search(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) AuditResourceType resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return AuditPageResponse.from(
                auditQuery.search(user, resourceType, resourceId, actorUserId, from, to, page, size));
    }
}
