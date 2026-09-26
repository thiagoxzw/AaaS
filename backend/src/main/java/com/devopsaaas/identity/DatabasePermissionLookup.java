package com.devopsaaas.identity;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.shared.security.PermissionLookup;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DatabasePermissionLookup implements PermissionLookup {

    private final AppUserRepository users;
    private final PermissionResolver resolver;

    DatabasePermissionLookup(AppUserRepository users, PermissionResolver resolver) {
        this.users = users;
        this.resolver = resolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Permission> currentPermissions(UUID organizationId, UUID userId) {
        return users.findForAuthenticationById(userId)
                .filter(AppUser::isActive)
                .filter(user -> user.getOrganizationId().equals(organizationId))
                .map(user -> resolver.permissionsFor(user.roles()))
                .orElse(Set.of());
    }
}
