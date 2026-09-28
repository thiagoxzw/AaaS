package com.devopsaaas.approval;

/** ADR-0006: PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED. Only PENDING ever changes. */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED
}
