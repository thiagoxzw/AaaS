package com.devopsaaas.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * The permissions a user has right now, read from the database. Used where there is no HTTP request to
 * authenticate (for example a tool call proposed while an agent execution runs), so revoking a role or
 * disabling a user still takes effect immediately (RNF-SEG-13). Implemented by the identity module.
 */
public interface PermissionLookup {

    /** Empty when the user does not exist, belongs to another organization or is disabled. */
    Set<Permission> currentPermissions(UUID organizationId, UUID userId);
}
