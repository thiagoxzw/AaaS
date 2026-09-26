-- Environments and their service allowlist (docs/04-modelo-de-dados.md, sections 4.3 and 4.4).
CREATE TABLE environment (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id),
    name            text        NOT NULL,
    description     text,
    type            text        NOT NULL,
    tier            text        NOT NULL,
    autonomy_level  text        NOT NULL,
    connection_ref  text        NOT NULL,
    status          text        NOT NULL,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,
    CONSTRAINT environment_org_id_uk UNIQUE (organization_id, id),
    CONSTRAINT environment_name_uk UNIQUE (organization_id, name),
    CONSTRAINT environment_created_by_fk FOREIGN KEY (organization_id, created_by) REFERENCES app_user (organization_id, id),
    CONSTRAINT environment_type_ck CHECK (type IN ('DOCKER')),
    CONSTRAINT environment_tier_ck CHECK (tier IN ('DEV', 'STAGING', 'PROD')),
    CONSTRAINT environment_autonomy_ck CHECK (autonomy_level IN ('OBSERVE_ONLY', 'ASSISTED', 'AUTOMATED')),
    CONSTRAINT environment_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE environment_service (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    environment_id  uuid        NOT NULL,
    name            text        NOT NULL,
    container_name  text        NOT NULL,
    description     text,
    enabled         boolean     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,
    -- The database itself refuses a service that points at another organization's environment.
    CONSTRAINT environment_service_environment_fk
        FOREIGN KEY (organization_id, environment_id) REFERENCES environment (organization_id, id),
    CONSTRAINT environment_service_name_uk UNIQUE (environment_id, name),
    CONSTRAINT environment_service_container_uk UNIQUE (environment_id, container_name),
    CONSTRAINT environment_service_name_ck CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,62}$')
);
