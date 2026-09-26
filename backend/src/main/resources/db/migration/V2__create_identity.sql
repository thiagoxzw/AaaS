-- Users and roles (docs/04-modelo-de-dados.md, section 4.2). A user belongs to exactly one organization in the MVP.
CREATE TABLE app_user (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id),
    email           text        NOT NULL,
    display_name    text        NOT NULL,
    password_hash   text        NOT NULL,
    status          text        NOT NULL,
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,
    -- Target of the tenant-safe composite foreign keys.
    CONSTRAINT app_user_org_id_uk UNIQUE (organization_id, id),
    CONSTRAINT app_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX app_user_email_uk ON app_user (lower(email));

CREATE TABLE user_role (
    user_id         uuid NOT NULL,
    organization_id uuid NOT NULL,
    role            text NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT user_role_user_fk FOREIGN KEY (organization_id, user_id) REFERENCES app_user (organization_id, id),
    CONSTRAINT user_role_role_ck CHECK (role IN ('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN'))
);
