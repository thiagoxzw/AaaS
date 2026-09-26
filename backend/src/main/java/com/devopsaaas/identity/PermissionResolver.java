package com.devopsaaas.identity;

import com.devopsaaas.shared.security.Permission;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single place that knows what each role may do (docs/04-modelo-de-dados.md, section 4.2). Roles are
 * additive: a user's permissions are the union of their roles' permissions.
 */
@Component
public class PermissionResolver {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        MATRIX.put(Role.VIEWER, EnumSet.of(Permission.EXECUTION_READ));
        MATRIX.put(Role.OPERATOR, EnumSet.of(
                Permission.EXECUTION_READ, Permission.AGENT_INTERACT, Permission.TOOL_OPERATE));
        MATRIX.put(Role.APPROVER, EnumSet.of(
                Permission.EXECUTION_READ, Permission.APPROVAL_DECIDE, Permission.AUDIT_READ));
        MATRIX.put(Role.ADMIN, EnumSet.allOf(Permission.class));
    }

    public Set<Permission> permissionsFor(Collection<Role> roles) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        roles.forEach(role -> permissions.addAll(MATRIX.get(role)));
        return permissions;
    }
}
