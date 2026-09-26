package com.devopsaaas.shared.security;

/**
 * What an authenticated user may do. Code checks permissions, never roles: the role-to-permission matrix
 * lives in a single place in the identity module (docs/04-modelo-de-dados.md, section 4.2).
 */
public enum Permission {
    EXECUTION_READ,
    AGENT_INTERACT,
    TOOL_OPERATE,
    APPROVAL_DECIDE,
    AUDIT_READ,
    ENVIRONMENT_MANAGE
}
