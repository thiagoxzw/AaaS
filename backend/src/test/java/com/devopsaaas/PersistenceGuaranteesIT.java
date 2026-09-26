package com.devopsaaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Invariants the database guarantees even if the application has a bug (docs/04-modelo-de-dados.md, sections 5 and 10). */
class PersistenceGuaranteesIT extends IntegrationTest {

    @Test
    void everyDomainTable_hasANotNullOrganizationId() {
        List<String> tablesWithoutTenant = jdbc.queryForList("""
                SELECT t.table_name
                FROM information_schema.tables t
                WHERE t.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                  AND t.table_name NOT IN ('organization', 'flyway_schema_history')
                  AND NOT EXISTS (
                      SELECT 1 FROM information_schema.columns c
                      WHERE c.table_schema = 'public' AND c.table_name = t.table_name
                        AND c.column_name = 'organization_id' AND c.is_nullable = 'NO')
                """, String.class);

        assertThat(tablesWithoutTenant).isEmpty();
    }

    @Test
    void compositeForeignKey_rejectsAServicePointingToAnotherOrganizationsEnvironment() {
        UUID environmentOfA = UUID.fromString(
                createEnvironment(createAdmin(DEFAULT_ORGANIZATION), uniqueName("fk")).get("id").asString());
        UUID organizationB = createOrganization();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO environment_service (id, organization_id, environment_id, name, container_name, enabled,
                                                 created_at, updated_at, version)
                VALUES (?, ?, ?, 'sneaky', 'sneaky-container', true, ?, ?, 0)
                """, Ids.newId(), organizationB, environmentOfA, now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("environment_service_environment_fk");
    }

    @Test
    void entityIds_areUuidV7GeneratedByTheApplication() {
        UUID id = UUID.fromString(
                createEnvironment(createAdmin(DEFAULT_ORGANIZATION), uniqueName("uuid")).get("id").asString());

        assertThat(id.version()).isEqualTo(7);
    }
}
