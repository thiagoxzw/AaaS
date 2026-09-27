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

    /**
     * Mandatory follow-up of slice 2: a tool call must belong to a real execution and LLM call of its own
     * organization (docs/07-plano-do-mvp.md, slice 4).
     */
    @Test
    void toolExecution_rejectsAnUnknownExecution_orAnotherOrganizationsLlmCall() {
        TestUser user = createAdmin(DEFAULT_ORGANIZATION);
        ExecutionIds own = executionFixture(user);
        ExecutionIds foreign = executionFixture(createAdmin(createOrganization()));

        assertThatThrownBy(() -> insertToolCall(user.organizationId(), Ids.newId(), own.llmCallId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("tool_execution_agent_execution_fk");
        assertThatThrownBy(() -> insertToolCall(user.organizationId(), own.agentExecutionId(), foreign.llmCallId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("tool_execution_llm_call_fk");
        assertThatThrownBy(() -> insertToolCall(user.organizationId(), own.agentExecutionId(), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("llm_call_id");
        insertToolCall(user.organizationId(), own.agentExecutionId(), own.llmCallId());
    }

    /** Invariant 2: at most one active execution per conversation, whatever the application does. */
    @Test
    void aConversation_cannotHaveTwoActiveExecutions() {
        TestUser user = createAdmin(DEFAULT_ORGANIZATION);
        ExecutionIds first = executionFixture(user);
        jdbc.update("UPDATE agent_execution SET status = 'RUNNING' WHERE id = ?", first.agentExecutionId());
        UUID conversation = jdbc.queryForObject("SELECT conversation_id FROM agent_execution WHERE id = ?",
                UUID.class, first.agentExecutionId());
        UUID message = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO message (id, organization_id, conversation_id, seq, role, content, created_at) "
                + "VALUES (?, ?, ?, 2, 'USER', 'again', ?)", message, user.organizationId(), conversation, now);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_execution (id, organization_id, conversation_id, trigger_message_id, requested_by,
                    status, autonomy_level, llm_model, prompt_version, context_snapshot, max_tool_calls,
                    max_llm_iterations, max_active_ms, tool_call_count, llm_iteration_count, active_ms,
                    input_tokens, output_tokens, estimated_cost_usd, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'ASSISTED', 'm', 'p', '{}'::jsonb, 10, 8, 1, 0, 0, 0, 0, 0, 0, ?, ?, 0)
                """, Ids.newId(), user.organizationId(), conversation, message, user.id(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("agent_execution_one_active_uk");
    }

    private void insertToolCall(UUID organizationId, UUID agentExecutionId, UUID llmCallId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO tool_execution (id, organization_id, agent_execution_id, llm_call_id, seq, tool_name,
                    arguments, status, attempt_count, output_truncated, redaction_count, created_at, updated_at,
                    version)
                VALUES (?, ?, ?, ?, (SELECT coalesce(max(seq), 0) + 1 FROM tool_execution WHERE agent_execution_id = ?),
                    'testStatus', '{}'::jsonb, 'PROPOSED', 0, false, 0, ?, ?, 0)
                """, Ids.newId(), organizationId, agentExecutionId, llmCallId, agentExecutionId, now, now);
    }

    @Test
    void entityIds_areUuidV7GeneratedByTheApplication() {
        UUID id = UUID.fromString(
                createEnvironment(createAdmin(DEFAULT_ORGANIZATION), uniqueName("uuid")).get("id").asString());

        assertThat(id.version()).isEqualTo(7);
    }
}
