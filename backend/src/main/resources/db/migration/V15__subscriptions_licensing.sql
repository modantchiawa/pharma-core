-- =====================================================================
-- V15 : abonnements des pharmacies et licence vérifiable hors ligne.
--  * L'éditeur enregistre les règlements reçus hors de l'application
--    (mobile money, virement, espèces, chèque) : chaque règlement
--    prolonge la période payée (tenant.subscription_expires_at).
--  * L'éditeur peut suspendre une pharmacie (tenant.status = SUSPENDED).
--  * Le cloud signe une licence (Ed25519) que le hub vérifie avec la clé
--    publique épinglée à l'appairage : le blocage tient même si le hub
--    reste hors ligne (validité 15 jours après la dernière licence reçue).
-- Ces tables appartiennent au cloud et descendent vers les hubs.
-- =====================================================================
INSERT INTO permission (code, category, description) VALUES
    ('SUBSCRIPTION_VIEW', 'ADMIN', 'Consulter l''abonnement de la pharmacie, ses règlements et sa licence');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code = 'SUBSCRIPTION_VIEW'
WHERE r.code = 'TITULAIRE';

-- ---------------------------------------------------------------------
-- Formules d'abonnement (référentiel commun, prix mensuel en francs CFA)
-- ---------------------------------------------------------------------
CREATE TABLE subscription_plan (
    code              varchar(30)  PRIMARY KEY,
    name              varchar(80)  NOT NULL,
    monthly_price_xof bigint       NOT NULL CHECK (monthly_price_xof >= 0),
    max_terminals     smallint     NOT NULL CHECK (max_terminals > 0),
    description       varchar(255),
    sort_order        smallint     NOT NULL DEFAULT 0,
    active            boolean      NOT NULL DEFAULT true,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    version           bigint       NOT NULL DEFAULT 0
);

INSERT INTO subscription_plan (code, name, monthly_price_xof, max_terminals, description, sort_order) VALUES
    ('ESSENTIEL', 'Essentiel', 25000, 2, 'Petite officine : caisse, stock, ordonnancier', 10),
    ('PRO',       'Pro',       45000, 5, 'Officine de quartier : comptabilité, tiers payant, commandes', 20),
    ('RESEAU',    'Réseau',    75000, 12, 'Grande officine ou groupement : postes multiples, réseau, pilotage', 30);

-- Formules déjà portées par des pharmacies existantes : reprises telles quelles, à tarifer
INSERT INTO subscription_plan (code, name, monthly_price_xof, max_terminals, sort_order)
SELECT DISTINCT t.subscription_plan, t.subscription_plan, 0, 5, 90
FROM tenant t
ON CONFLICT (code) DO NOTHING;

ALTER TABLE tenant ADD CONSTRAINT fk_tenant_subscription_plan FOREIGN KEY (subscription_plan)
    REFERENCES subscription_plan (code) DEFERRABLE INITIALLY IMMEDIATE;

-- Suspension par l'éditeur : date (les ventes hors ligne antérieures restent recevables) et motif
ALTER TABLE tenant ADD COLUMN suspended_at timestamptz;
ALTER TABLE tenant ADD COLUMN suspension_reason varchar(255);

GRANT SELECT ON subscription_plan TO pharmacore_app;
GRANT INSERT, UPDATE ON subscription_plan TO pharmacore_app;
ALTER TABLE subscription_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_plan_read ON subscription_plan FOR SELECT TO pharmacore_app USING (true);
CREATE POLICY p_plan_insert ON subscription_plan FOR INSERT TO pharmacore_app WITH CHECK (app_is_platform_admin());
CREATE POLICY p_plan_update ON subscription_plan FOR UPDATE TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Règlements reçus par l'éditeur (écriture seule : une erreur se corrige
-- par un nouveau règlement, jamais en modifiant l'historique)
-- ---------------------------------------------------------------------
CREATE TABLE subscription_payment (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    plan_code   varchar(30)  NOT NULL REFERENCES subscription_plan (code) DEFERRABLE INITIALLY IMMEDIATE,
    amount_xof  bigint       NOT NULL CHECK (amount_xof > 0),
    method      varchar(15)  NOT NULL CHECK (method IN ('MOBILE_MONEY', 'VIREMENT', 'ESPECES', 'CHEQUE', 'AUTRE')),
    reference   varchar(80),
    months      smallint     NOT NULL CHECK (months BETWEEN 1 AND 36),
    period_from timestamptz  NOT NULL,
    period_to   timestamptz  NOT NULL,
    paid_at     date         NOT NULL,
    recorded_by varchar(150) NOT NULL,
    recorded_at timestamptz  NOT NULL DEFAULT now(),
    note        varchar(255),
    CHECK (period_to > period_from)
);
CREATE INDEX ix_subscription_payment_tenant ON subscription_payment (tenant_id, paid_at DESC, recorded_at DESC);

GRANT SELECT, INSERT ON subscription_payment TO pharmacore_app;
ALTER TABLE subscription_payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON subscription_payment FOR SELECT TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_payment_insert ON subscription_payment FOR INSERT TO pharmacore_app
    WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Licence signée en vigueur pour chaque pharmacie (une ligne, réémise à
-- chaque événement et rafraîchie par les hubs à chaque synchronisation)
-- ---------------------------------------------------------------------
CREATE TABLE tenant_license (
    tenant_id   uuid PRIMARY KEY REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    serial      bigint       NOT NULL CHECK (serial > 0),
    kid         varchar(64)  NOT NULL,
    token       text         NOT NULL,
    issued_at   timestamptz  NOT NULL,
    valid_until timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

GRANT SELECT, INSERT, UPDATE ON tenant_license TO pharmacore_app;
ALTER TABLE tenant_license ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON tenant_license FOR SELECT TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_license_write ON tenant_license FOR ALL TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Clés publiques de l'autorité de licence (cloud uniquement, non
-- synchronisées : le hub épingle les siennes). Une clé de remplacement
-- est endossée par la précédente.
-- ---------------------------------------------------------------------
CREATE TABLE license_key (
    kid            varchar(64) PRIMARY KEY,
    algorithm      varchar(10) NOT NULL DEFAULT 'Ed25519',
    public_key_pem text        NOT NULL,
    endorsed_by    varchar(64) REFERENCES license_key (kid) DEFERRABLE INITIALLY IMMEDIATE,
    endorsement    text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    retired_at     timestamptz,
    CHECK ((endorsed_by IS NULL) = (endorsement IS NULL))
);

GRANT SELECT, INSERT, UPDATE ON license_key TO pharmacore_app;
ALTER TABLE license_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_license_key_read ON license_key FOR SELECT TO pharmacore_app USING (true);
CREATE POLICY p_license_key_write ON license_key FOR ALL TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Connexion d'une pharmacie suspendue : elle garde l'accès en lecture
-- (registres, rapports, exports), seules les modifications sont bloquées.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION resolve_active_tenant(p_slug text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT id FROM tenant WHERE slug = p_slug AND status IN ('ACTIVE', 'SUSPENDED')
$$;

-- Les formules avant les pharmacies qui les référencent ; licences et
-- règlements après la pharmacie.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('subscription_plan',    'CLOUD', 'GLOBAL', NULL, 9),
    ('tenant_license',       'CLOUD', 'TENANT', NULL, 18),
    ('subscription_payment', 'CLOUD', 'TENANT', NULL, 19);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('subscription_plan', 'tenant_license', 'subscription_payment');
