package com.devopsaaas.identity;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;
import java.util.UUID;

/**
 * A row of {@code user_role}. It carries {@code organization_id} like every other domain table, so the
 * composite foreign key to {@code app_user} keeps roles inside the user's tenant.
 */
@Embeddable
public class RoleAssignment {

    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    private Role role;

    protected RoleAssignment() {
    }

    RoleAssignment(UUID organizationId, Role role) {
        this.organizationId = organizationId;
        this.role = role;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RoleAssignment that
                && Objects.equals(organizationId, that.organizationId)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(organizationId, role);
    }
}
