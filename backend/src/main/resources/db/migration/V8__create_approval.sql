-- Human approval of risky tool calls (ADR-0006, docs/04-modelo-de-dados.md section 4.10, slice 7).

-- Target of the composite foreign key below.
ALTER TABLE tool_execution
    ADD CONSTRAINT tool_execution_org_id_uk UNIQUE (organization_id, id);

CREATE TABLE approval (
    id                   uuid        PRIMARY KEY,
    organization_id      uuid        NOT NULL,
    agent_execution_id   uuid        NOT NULL,
    tool_execution_id    uuid        NOT NULL,
    status               text        NOT NULL,
    -- Copy of tool_execution.arguments_hash when the approval was requested; compared again before running.
    arguments_hash       text        NOT NULL,
    risk_level           text        NOT NULL,
    -- From the tool definition: deterministic and trusted.
    impact_description   text        NOT NULL,
    -- From the LLM: untrusted, always shown labelled as such and never rendered.
    agent_justification  text,
    expires_at           timestamptz NOT NULL,
    decided_by           uuid,
    decided_at           timestamptz,
    decision_comment     text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL,
    CONSTRAINT approval_org_id_uk UNIQUE (organization_id, id),
    -- Invariant 4 (docs/04-modelo-de-dados.md, section 5): at most one approval per tool call.
    CONSTRAINT approval_tool_execution_uk UNIQUE (tool_execution_id),
    CONSTRAINT approval_tool_execution_fk
        FOREIGN KEY (organization_id, tool_execution_id) REFERENCES tool_execution (organization_id, id),
    CONSTRAINT approval_agent_execution_fk
        FOREIGN KEY (organization_id, agent_execution_id) REFERENCES agent_execution (organization_id, id),
    CONSTRAINT approval_decided_by_fk
        FOREIGN KEY (organization_id, decided_by) REFERENCES app_user (organization_id, id),
    CONSTRAINT approval_status_ck CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT approval_risk_ck CHECK (risk_level IN ('READ_ONLY', 'LOW_RISK', 'HIGH_RISK', 'DESTRUCTIVE')),
    -- Invariant 6: a human decision always has an author and a time, and only human decisions have them.
    CONSTRAINT approval_decision_ck
        CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT approval_comment_length_ck CHECK (char_length(decision_comment) <= 1000)
);

-- Pending approvals of an organization, and the expiration sweep.
CREATE INDEX approval_pending_ix ON approval (organization_id, expires_at) WHERE status = 'PENDING';
-- "Is anything of this execution still waiting for a human?"
CREATE INDEX approval_agent_execution_ix ON approval (agent_execution_id);

-- Slice 7: an approved call whose stored arguments no longer match the approved hash is denied, not run.
ALTER TABLE tool_execution DROP CONSTRAINT tool_execution_denial_reason_ck;
ALTER TABLE tool_execution ADD CONSTRAINT tool_execution_denial_reason_ck CHECK (denial_reason IN (
    'ENVIRONMENT_UNAVAILABLE', 'UNKNOWN_TOOL', 'NOT_ALLOWED_BY_AUTONOMY', 'INVALID_ARGUMENTS',
    'RESOURCE_NOT_ALLOWED', 'INSUFFICIENT_PERMISSION', 'BUDGET_EXCEEDED', 'POLICY_ERROR', 'ARGUMENTS_MISMATCH'));
