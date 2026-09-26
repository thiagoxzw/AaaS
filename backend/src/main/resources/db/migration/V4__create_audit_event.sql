-- Append-only audit trail (docs/04-modelo-de-dados.md, section 4.11).
-- No foreign keys on purpose: ids are informative values and the history survives any domain change.
CREATE TABLE audit_event (
    id                   uuid        PRIMARY KEY,
    organization_id      uuid        NOT NULL,
    occurred_at          timestamptz NOT NULL,
    actor_type           text        NOT NULL,
    actor_user_id        uuid,
    on_behalf_of_user_id uuid,
    actor_label          text        NOT NULL,
    action               text        NOT NULL,
    resource_type        text        NOT NULL,
    resource_id          uuid,
    tool_name            text,
    agent_execution_id   uuid,
    tool_execution_id    uuid,
    outcome              text        NOT NULL,
    details              jsonb       NOT NULL,
    trace_id             text,
    CONSTRAINT audit_event_actor_type_ck CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT audit_event_outcome_ck CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT audit_event_resource_type_ck
        CHECK (resource_type IN ('ENVIRONMENT', 'SERVICE', 'EXECUTION', 'TOOL_EXECUTION', 'APPROVAL', 'USER'))
);

CREATE INDEX audit_event_org_time_ix ON audit_event (organization_id, occurred_at DESC);
CREATE INDEX audit_event_resource_ix ON audit_event (organization_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX audit_event_actor_ix ON audit_event (organization_id, actor_user_id, occurred_at DESC);
CREATE INDEX audit_event_execution_ix ON audit_event (agent_execution_id);

CREATE FUNCTION forbid_audit_event_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_event_mutation();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_event_mutation();
