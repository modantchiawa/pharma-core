-- =====================================================================
-- V24 — Postes nominatifs
-- Chaque poste de caisse garde son identifiant unique (généré par le navigateur, préfixe des
-- numéros de ticket) et reçoit en plus un nom lisible (« Caisse 1 », « Comptoir ordonnances »)
-- et un emplacement facultatif, choisis par la pharmacie.
-- =====================================================================

CREATE TABLE terminal_profile (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    terminal_id varchar(40)  NOT NULL,
    name        varchar(60)  NOT NULL CHECK (length(btrim(name)) > 0),
    location    varchar(80),
    updated_by  uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, terminal_id)
);
-- Deux postes d'une même pharmacie ne portent pas le même nom.
CREATE UNIQUE INDEX ux_terminal_profile_name ON terminal_profile (tenant_id, lower(name));

GRANT SELECT, INSERT, UPDATE, DELETE ON terminal_profile TO pharmacore_app;

ALTER TABLE terminal_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON terminal_profile TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

-- Exploitation de l'officine : le hub est propriétaire, après les utilisateurs (24).
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('terminal_profile', 'HUB', 'TENANT', NULL, 29);
SELECT sync_track('terminal_profile');
