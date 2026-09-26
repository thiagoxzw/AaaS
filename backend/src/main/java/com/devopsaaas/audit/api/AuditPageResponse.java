package com.devopsaaas.audit.api;

import com.devopsaaas.audit.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Page;

record AuditPageResponse(List<AuditEventResponse> items, int page, int size, long totalElements, int totalPages) {

    static AuditPageResponse from(Page<AuditEvent> page) {
        return new AuditPageResponse(
                page.getContent().stream().map(AuditEventResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
