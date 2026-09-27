package com.devopsaaas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.support.AgentTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * RNF-CONF-08/09: what a crash leaves behind. The rows are written as a crashed process would have left them,
 * then the startup recovery runs.
 */
class AgentRecoveryIT extends AgentTestSupport {

    @Autowired
    AgentStartupRecovery recovery;

    @Test
    void afterACrash_runningWorkIsInterrupted_callsAreUnknown_queuedWorkRuns_andApprovalsWait() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        TestUser operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        String environment = environment(admin, "ASSISTED", uniqueName("recovered-container"));

        ExecutionIds running = executionFixture(operator);
        jdbc.update("UPDATE agent_execution SET status = 'RUNNING' WHERE id = ?", running.agentExecutionId());
        UUID runningCall = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO tool_execution (id, organization_id, agent_execution_id, llm_call_id, seq, tool_name,
                    arguments, policy_decision, status, attempt_count, output_truncated, redaction_count, created_at,
                    started_at, updated_at, version)
                VALUES (?, ?, ?, ?, 1, 'testRestart', '{}'::jsonb, 'ALLOW', 'RUNNING', 1, false, 0, ?, ?, ?, 0)
                """, runningCall, operator.organizationId(), running.agentExecutionId(), running.llmCallId(), now,
                now, now);

        ExecutionIds waiting = executionFixture(operator);
        jdbc.update("UPDATE agent_execution SET status = 'WAITING_APPROVAL' WHERE id = ?", waiting.agentExecutionId());

        UUID queued = queuedExecution(operator, environment, "[OK] " + uniqueName("after-crash") + " is it up?");

        AgentStartupRecovery.Result result = recovery.recover();

        assertThat(result.interruptedExecutions()).isGreaterThanOrEqualTo(1);
        assertThat(status("agent_execution", running.agentExecutionId())).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT status_reason FROM agent_execution WHERE id = ?", String.class,
                running.agentExecutionId())).isEqualTo("BACKEND_RESTARTED");
        assertThat(status("tool_execution", runningCall)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(status("agent_execution", waiting.agentExecutionId())).isEqualTo("WAITING_APPROVAL");
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool_execution_id = ? AND actor_type = 'SYSTEM'",
                runningCall)).isEqualTo(1);

        JsonNode redispatched = settled(operator, queued.toString());
        assertThat(redispatched.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(actions(redispatched)).hasSize(1);
    }

    /** An execution accepted by a process that died before a worker picked it up. */
    private UUID queuedExecution(TestUser user, String environmentId, String content) {
        String conversation = conversation(user, environmentId);
        UUID message = Ids.newId();
        UUID execution = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO message (id, organization_id, conversation_id, seq, role, content, created_at)
                VALUES (?, ?, ?::uuid, 1, 'USER', ?, ?)
                """, message, user.organizationId(), conversation, content, now);
        jdbc.update("""
                INSERT INTO agent_execution (id, organization_id, conversation_id, trigger_message_id, requested_by,
                    status, autonomy_level, llm_model, prompt_version, context_snapshot, max_tool_calls,
                    max_llm_iterations, max_active_ms, tool_call_count, llm_iteration_count, active_ms,
                    input_tokens, output_tokens, estimated_cost_usd, created_at, updated_at, version)
                VALUES (?, ?, ?::uuid, ?, ?, 'QUEUED', 'ASSISTED', 'scripted-v1', 'agent-system-v1', '{}'::jsonb,
                    10, 8, 300000, 0, 0, 0, 0, 0, 0, ?, ?, 0)
                """, execution, user.organizationId(), conversation, message, user.id(), now, now);
        return execution;
    }

    private String status(String table, UUID id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }
}
