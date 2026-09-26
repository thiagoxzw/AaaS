-- Every tool proposal, including denied ones, becomes a row (ADR-0009, docs/04-modelo-de-dados.md section 4.9).
--
-- MANDATORY FOLLOW-UP (slice 4, docs/07-plano-do-mvp.md): agent_execution_id and llm_call_id are required by
-- the domain but have no foreign key yet, because agent_execution and llm_call are created in slice 4 (they
-- depend on conversation and message). Slice 4 is not done until its migration adds:
--   FOREIGN KEY (organization_id, agent_execution_id) REFERENCES agent_execution (organization_id, id)
--   FOREIGN KEY (organization_id, llm_call_id)        REFERENCES llm_call (organization_id, id)

-- Target of the composite foreign key below.
ALTER TABLE environment_service
    ADD CONSTRAINT environment_service_org_id_uk UNIQUE (organization_id, id);

CREATE TABLE tool_execution (
    id                 uuid        PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id),
    agent_execution_id uuid        NOT NULL,
    llm_call_id        uuid,
    seq                int         NOT NULL,
    llm_tool_call_id   text,
    tool_name          text        NOT NULL,
    tool_version       int,
    risk_level         text,
    target_service_id  uuid,
    arguments          jsonb       NOT NULL,
    arguments_hash     text,
    rationale          text,
    policy_decision    text,
    denial_reason      text,
    status             text        NOT NULL,
    attempt_count      int         NOT NULL,
    output             jsonb,
    output_truncated   boolean     NOT NULL,
    redaction_count    int         NOT NULL,
    error_code         text,
    error_message      text,
    created_at         timestamptz NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    duration_ms        bigint,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL,
    CONSTRAINT tool_execution_target_fk
        FOREIGN KEY (organization_id, target_service_id) REFERENCES environment_service (organization_id, id),
    CONSTRAINT tool_execution_seq_uk UNIQUE (agent_execution_id, seq),
    -- Every denial has a reason, and only denials have one.
    CONSTRAINT tool_execution_denial_ck CHECK ((status = 'DENIED') = (denial_reason IS NOT NULL)),
    CONSTRAINT tool_execution_status_ck CHECK (status IN (
        'PROPOSED', 'DENIED', 'WAITING_APPROVAL', 'REJECTED', 'EXPIRED', 'CANCELLED',
        'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'OUTCOME_UNKNOWN')),
    CONSTRAINT tool_execution_decision_ck CHECK (policy_decision IN ('ALLOW', 'REQUIRE_APPROVAL', 'DENY')),
    CONSTRAINT tool_execution_denial_reason_ck CHECK (denial_reason IN (
        'ENVIRONMENT_UNAVAILABLE', 'UNKNOWN_TOOL', 'NOT_ALLOWED_BY_AUTONOMY', 'INVALID_ARGUMENTS',
        'RESOURCE_NOT_ALLOWED', 'INSUFFICIENT_PERMISSION', 'BUDGET_EXCEEDED', 'POLICY_ERROR')),
    CONSTRAINT tool_execution_risk_ck CHECK (risk_level IN ('READ_ONLY', 'LOW_RISK', 'HIGH_RISK', 'DESTRUCTIVE'))
);

-- "Who touched service X?"
CREATE INDEX tool_execution_target_ix ON tool_execution (organization_id, target_service_id, created_at DESC);
-- Startup recovery of executions interrupted mid-call (RUNNING -> OUTCOME_UNKNOWN).
CREATE INDEX tool_execution_running_ix ON tool_execution (status) WHERE status = 'RUNNING';
