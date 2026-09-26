package com.devopsaaas.identity;

/**
 * Roles are only known inside the identity module. Everything else checks {@code Permission}s, resolved by
 * {@link PermissionResolver} (enforced by ArchitectureTest).
 */
public enum Role {
    VIEWER,
    OPERATOR,
    APPROVER,
    ADMIN
}
