-- Conversations, messages, agent executions and LLM calls (docs/04-modelo-de-dados.md, sections 4.5 to 4.8),
-- and the foreign keys that tool_execution has been waiting for since slice 2.

CREATE TABLE conversation (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id),
    environment_id  uuid        NOT NULL,
    created_by      uuid        NOT NULL,
    title           text,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,
    CONSTRAINT conversation_org_id_uk UNIQUE (organization_id, id),
    -- A conversation belongs to exactly one environment of its own organization.
    CONSTRAINT conversation_environment_fk
        FOREIGN KEY (organization_id, environment_id) REFERENCES environment (organization_id, id),
    CONSTRAINT conversation_created_by_fk
        FOREIGN KEY (organization_id, created_by) REFERENCES app_user (organization_id, id),
    CONSTRAINT conversation_status_ck CHECK (status IN ('OPEN', 'ARCHIVED'))
);

-- Only USER and ASSISTANT: tool calls and intermediate LLM turns live in tool_execution and llm_call.
CREATE TABLE message (
    id                 uuid        PRIMARY KEY,
    organization_id    uuid        NOT NULL,
    conversation_id    uuid        NOT NULL,
    agent_execution_id uuid,
    seq                int         NOT NULL,
    role               text        NOT NULL,
    content            text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT message_org_id_uk UNIQUE (organization_id, id),
    CONSTRAINT message_conversation_fk
        FOREIGN KEY (organization_id, conversation_id) REFERENCES conversation (organization_id, id),
    CONSTRAINT message_seq_uk UNIQUE (conversation_id, seq),
    CONSTRAINT message_role_ck CHECK (role IN ('USER', 'ASSISTANT')),
    -- An assistant message is always the answer of one execution.
    CONSTRAINT message_assistant_execution_ck CHECK (role = 'USER' OR agent_execution_id IS NOT NULL)
);

CREATE TABLE agent_execution (
    id                       uuid          PRIMARY KEY,
    organization_id          uuid          NOT NULL,
    conversation_id          uuid          NOT NULL,
    trigger_message_id       uuid          NOT NULL,
    requested_by             uuid          NOT NULL,
    status                   text          NOT NULL,
    status_reason            text,
    autonomy_level           text          NOT NULL,
    llm_model                text          NOT NULL,
    prompt_version           text          NOT NULL,
    context_snapshot         jsonb         NOT NULL,
    max_tool_calls           int           NOT NULL,
    max_llm_iterations       int           NOT NULL,
    max_active_ms            bigint        NOT NULL,
    tool_call_count          int           NOT NULL,
    llm_iteration_count      int           NOT NULL,
    active_ms                bigint        NOT NULL,
    input_tokens             bigint        NOT NULL,
    output_tokens            bigint        NOT NULL,
    estimated_cost_usd       numeric(12,6) NOT NULL,
    idempotency_key          text,
    idempotency_request_hash text,
    created_at               timestamptz   NOT NULL,
    started_at               timestamptz,
    finished_at              timestamptz,
    updated_at               timestamptz   NOT NULL,
    version                  bigint        NOT NULL,
    CONSTRAINT agent_execution_org_id_uk UNIQUE (organization_id, id),
    CONSTRAINT agent_execution_conversation_fk
        FOREIGN KEY (organization_id, conversation_id) REFERENCES conversation (organization_id, id),
    CONSTRAINT agent_execution_trigger_message_fk
        FOREIGN KEY (organization_id, trigger_message_id) REFERENCES message (organization_id, id),
    CONSTRAINT agent_execution_trigger_message_uk UNIQUE (trigger_message_id),
    CONSTRAINT agent_execution_requested_by_fk
        FOREIGN KEY (organization_id, requested_by) REFERENCES app_user (organization_id, id),
    CONSTRAINT agent_execution_status_ck CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'BUDGET_EXCEEDED', 'INTERRUPTED',
        'CANCELLED')),
    CONSTRAINT agent_execution_autonomy_ck CHECK (autonomy_level IN ('OBSERVE_ONLY', 'ASSISTED', 'AUTOMATED')),
    CONSTRAINT agent_execution_idempotency_ck
        CHECK ((idempotency_key IS NULL) = (idempotency_request_hash IS NULL))
);

-- Invariant 2 (docs/04-modelo-de-dados.md, section 5): at most one active execution per conversation.
CREATE UNIQUE INDEX agent_execution_one_active_uk ON agent_execution (conversation_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL');
-- Invariant 3: one execution per Idempotency-Key and user.
CREATE UNIQUE INDEX agent_execution_idempotency_uk ON agent_execution (requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- Startup recovery and redispatch.
CREATE INDEX agent_execution_status_ix ON agent_execution (status) WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE message
    ADD CONSTRAINT message_agent_execution_fk
        FOREIGN KEY (organization_id, agent_execution_id) REFERENCES agent_execution (organization_id, id);

CREATE TABLE llm_call (
    id                 uuid          PRIMARY KEY,
    organization_id    uuid          NOT NULL,
    agent_execution_id uuid          NOT NULL,
    seq                int           NOT NULL,
    model              text          NOT NULL,
    finish_reason      text          NOT NULL,
    assistant_text     text,
    input_tokens       int,
    output_tokens      int,
    estimated_cost_usd numeric(12,6),
    duration_ms        int           NOT NULL,
    error_code         text,
    created_at         timestamptz   NOT NULL,
    CONSTRAINT llm_call_org_id_uk UNIQUE (organization_id, id),
    CONSTRAINT llm_call_agent_execution_fk
        FOREIGN KEY (organization_id, agent_execution_id) REFERENCES agent_execution (organization_id, id),
    CONSTRAINT llm_call_seq_uk UNIQUE (agent_execution_id, seq),
    CONSTRAINT llm_call_finish_reason_ck CHECK (finish_reason IN ('TOOL_CALLS', 'STOP', 'LENGTH', 'ERROR')),
    CONSTRAINT llm_call_error_ck CHECK ((finish_reason = 'ERROR') = (error_code IS NOT NULL))
);

-- Mandatory follow-up of slice 2 (docs/07-plano-do-mvp.md, slice 4). Every proposal belongs to an execution
-- and comes from one LLM call, of the same organization. If rows without them exist, this migration fails on
-- purpose instead of deleting or inventing data: before slice 4 only tests could write tool_execution.
ALTER TABLE tool_execution ALTER COLUMN llm_call_id SET NOT NULL;
ALTER TABLE tool_execution
    ADD CONSTRAINT tool_execution_agent_execution_fk
        FOREIGN KEY (organization_id, agent_execution_id) REFERENCES agent_execution (organization_id, id),
    ADD CONSTRAINT tool_execution_llm_call_fk
        FOREIGN KEY (organization_id, llm_call_id) REFERENCES llm_call (organization_id, id);

-- Conversations become an audited resource.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_resource_type_ck;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_resource_type_ck CHECK (resource_type IN (
    'ENVIRONMENT', 'SERVICE', 'EXECUTION', 'TOOL_EXECUTION', 'APPROVAL', 'USER', 'CONVERSATION'));
