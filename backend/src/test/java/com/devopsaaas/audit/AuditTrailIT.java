package com.devopsaaas.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.support.IntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** RF-44/45, RNF-SEG-09, TM-B8-03, docs/04-modelo-de-dados.md section 4.11. */
class AuditTrailIT extends IntegrationTest {

    @Autowired
    AuditRecorder recorder;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void auditEvents_cannotBeUpdatedDeletedOrTruncated_evenWithDirectSql() {
        UUID resourceId = recordOne(DEFAULT_ORGANIZATION);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor_label = 'forged' WHERE resource_id = ?",
                resourceId)).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE resource_id = ?", resourceId))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_event"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThat(countFor(resourceId)).isEqualTo(1);
    }

    @Test
    void recorder_refusesToRecordOutsideATransaction() {
        assertThatThrownBy(() -> recorder.record(entry(DEFAULT_ORGANIZATION, Ids.newId())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void auditEvent_rollsBackTogetherWithTheChangeItDescribes() {
        UUID resourceId = Ids.newId();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recorder.record(entry(DEFAULT_ORGANIZATION, resourceId));
            throw new IllegalStateException("the audited change failed");
        })).hasMessage("the audited change failed");

        assertThat(countFor(resourceId)).isZero();
    }

    @Test
    void details_areStoredAsJsonb() {
        UUID resourceId = recordOne(DEFAULT_ORGANIZATION);

        String columnType = jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'audit_event' AND column_name = 'details'
                """, String.class);
        String name = jdbc.queryForObject("SELECT details ->> 'name' FROM audit_event WHERE resource_id = ?",
                String.class, resourceId);

        assertThat(columnType).isEqualTo("jsonb");
        assertThat(name).isEqualTo("recorded-by-test");
    }

    @Test
    void auditSearch_neverShowsAnotherOrganizationsEvents() {
        UUID organizationB = createOrganization();
        UUID eventOfB = recordOne(organizationB);
        TestUser adminOfA = createAdmin(DEFAULT_ORGANIZATION);

        String body = get("/api/v1/audit-events?resourceId=" + eventOfB, adminOfA.token()).getBody();

        assertThat(read(get("/api/v1/audit-events?resourceId=" + eventOfB, adminOfA.token())).get("totalElements").asLong())
                .isZero();
        assertThat(body).doesNotContain(eventOfB.toString());
    }

    private UUID recordOne(UUID organizationId) {
        UUID resourceId = Ids.newId();
        transactions.executeWithoutResult(status -> recorder.record(entry(organizationId, resourceId)));
        return resourceId;
    }

    private static AuditEntry entry(UUID organizationId, UUID resourceId) {
        CurrentUser actor = new CurrentUser(Ids.newId(), organizationId, "tester@test.local", Set.of());
        return AuditEntry.byUser(actor, AuditAction.ENVIRONMENT_CREATED, AuditResourceType.ENVIRONMENT, resourceId)
                .detail("name", "recorded-by-test");
    }

    private int countFor(UUID resourceId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id = ?", Integer.class, resourceId);
    }
}
