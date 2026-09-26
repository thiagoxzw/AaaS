package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.shared.security.Permission;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The matrix of docs/04-modelo-de-dados.md, section 4.2. */
class PermissionResolverTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    void viewer_canOnlyRead() {
        assertThat(resolver.permissionsFor(List.of(Role.VIEWER))).containsExactly(Permission.EXECUTION_READ);
    }

    @Test
    void operator_canInteractAndOperate_butNotApproveOrAudit() {
        assertThat(resolver.permissionsFor(List.of(Role.OPERATOR)))
                .containsExactlyInAnyOrder(Permission.EXECUTION_READ, Permission.AGENT_INTERACT, Permission.TOOL_OPERATE);
    }

    @Test
    void approver_canApproveAndAudit_butNotOperate() {
        assertThat(resolver.permissionsFor(List.of(Role.APPROVER)))
                .containsExactlyInAnyOrder(Permission.EXECUTION_READ, Permission.APPROVAL_DECIDE, Permission.AUDIT_READ);
    }

    @Test
    void admin_hasEveryPermission() {
        assertThat(resolver.permissionsFor(List.of(Role.ADMIN))).containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    void roles_areAdditive() {
        assertThat(resolver.permissionsFor(List.of(Role.OPERATOR, Role.APPROVER)))
                .contains(Permission.TOOL_OPERATE, Permission.APPROVAL_DECIDE)
                .doesNotContain(Permission.ENVIRONMENT_MANAGE);
    }

    @Test
    void noRoles_meansNoPermissions() {
        assertThat(resolver.permissionsFor(List.of())).isEmpty();
    }
}
