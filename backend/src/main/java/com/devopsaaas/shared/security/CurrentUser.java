package com.devopsaaas.shared.security;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user, reloaded from the database on every request. Modules receive it through
 * {@code @AuthenticationPrincipal} and never depend on the identity module directly.
 *
 * <p>{@code organizationId} always comes from here, never from request input.
 */
public record CurrentUser(UUID userId, UUID organizationId, String email, Set<Permission> permissions)
        implements Serializable {

    public CurrentUser {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
