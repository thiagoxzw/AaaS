-- The single organization of the MVP (ADR-0005). The bootstrap admin is created by the application from
-- environment variables, so no credential ever lives in a migration.
INSERT INTO organization (id, name, slug, status)
VALUES ('0199a000-0000-7000-8000-000000000001', 'Default organization', 'default', 'ACTIVE');
