-- =====================================================================
-- V4 : identité locale (fonctionnement hors-ligne), clients et crédit,
--      sessions de caisse, mobile money, facture certifiée, topologie.
-- Marché cible : Afrique de l'Ouest (XOF sans décimales, coupures
-- d'Internet et de courant fréquentes, paiements mobile money).
-- =====================================================================

ALTER TABLE tenant ALTER COLUMN currency SET DEFAULT 'XOF';
ALTER TABLE tenant ALTER COLUMN time_zone SET DEFAULT 'Africa/Niamey';
-- CLOUD : la pharmacie travaille directement sur le cloud (PWA hors-ligne).
-- HUB   : un serveur local (hub) en officine est maître des données
--         opérationnelles et se synchronise avec le cloud.
ALTER TABLE tenant ADD COLUMN topology varchar(10) NOT NULL DEFAULT 'CLOUD' CHECK (topology IN ('CLOUD', 'HUB'));
ALTER TABLE pharmacy_config ALTER COLUMN country_code SET DEFAULT 'NE';
ALTER TABLE pharmacy_config ADD COLUMN tax_identifier varchar(30);
ALTER TABLE pharmacy_config ADD COLUMN ui_density varchar(12) NOT NULL DEFAULT 'COMFORTABLE'
    CHECK (ui_density IN ('COMPACT', 'COMFORTABLE', 'LARGE'));
ALTER TABLE pharmacy_config ADD COLUMN locale varchar(10) NOT NULL DEFAULT 'fr-NE';

-- ---------------------------------------------------------------------
-- Comptes locaux : mot de passe (connexion) + PIN (bascule rapide de
-- caissier sur un poste déjà ouvert). Verrouillage après échecs.
-- ---------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN password_hash varchar(100);
ALTER TABLE app_user ADD COLUMN pin_hash varchar(100);
ALTER TABLE app_user ADD COLUMN must_change_password boolean NOT NULL DEFAULT true;
ALTER TABLE app_user ADD COLUMN failed_attempts smallint NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until timestamptz;
ALTER TABLE app_user ADD COLUMN password_changed_at timestamptz;

-- Clés publiques des émetteurs internes de jetons (un hub = une clé,
-- le cloud a la sienne). Les clés privées ne sont jamais en base.
CREATE TABLE signing_key (
    id             uuid PRIMARY KEY,
    kid            varchar(64)  NOT NULL UNIQUE,
    issuer         varchar(120) NOT NULL,
    tenant_id      uuid REFERENCES tenant (id),
    node_id        varchar(64)  NOT NULL,
    algorithm      varchar(10)  NOT NULL DEFAULT 'RS256',
    public_key_pem text         NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    revoked_at     timestamptz
);
CREATE INDEX ix_signing_key_issuer ON signing_key (issuer) WHERE revoked_at IS NULL;

-- Sessions longues (jeton de rafraîchissement, jeton de poste) : locales
-- au nœud qui les a émises, jamais synchronisées.
CREATE TABLE auth_session (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    user_id       uuid         NOT NULL REFERENCES app_user (id),
    kind          varchar(10)  NOT NULL CHECK (kind IN ('REFRESH', 'TERMINAL')),
    token_hash    varchar(64)  NOT NULL UNIQUE,
    terminal_id   varchar(40)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    expires_at    timestamptz  NOT NULL,
    last_used_at  timestamptz,
    revoked_at    timestamptz
);
CREATE INDEX ix_auth_session_user ON auth_session (tenant_id, user_id) WHERE revoked_at IS NULL;

-- Nœuds hub appairés (supervision cloud) et codes d'appairage à usage unique.
CREATE TABLE hub_node (
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    node_id       varchar(64) NOT NULL,
    app_version   varchar(40),
    paired_at     timestamptz NOT NULL DEFAULT now(),
    last_push_xid bigint      NOT NULL DEFAULT 0,
    last_push_seq bigint      NOT NULL DEFAULT 0,
    last_push_at  timestamptz,
    last_pull_at  timestamptz,
    last_seen_at  timestamptz,
    PRIMARY KEY (tenant_id, node_id)
);

CREATE TABLE hub_pairing_code (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    code_hash  varchar(64) NOT NULL UNIQUE,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    used_by_node varchar(64)
);

-- Paramètres persistants du nœud (identifiant, appairage, curseurs divers).
CREATE TABLE node_setting (
    key        varchar(64) PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Clients : fiche, compte crédit ("ardoise"), fidélité.
-- ---------------------------------------------------------------------
CREATE TABLE customer (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    code                 varchar(20)  NOT NULL,
    display_name         varchar(150) NOT NULL,
    phone                varchar(30),
    email                varchar(200),
    address              varchar(300),
    credit_limit_cents   bigint       NOT NULL DEFAULT 0 CHECK (credit_limit_cents >= 0),
    -- Montant dû par le client (ventes à crédit - encaissements)
    credit_balance_cents bigint       NOT NULL DEFAULT 0,
    loyalty_points       integer      NOT NULL DEFAULT 0 CHECK (loyalty_points >= 0),
    notes                varchar(500),
    active               boolean      NOT NULL DEFAULT true,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    version              bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);
CREATE INDEX ix_customer_phone ON customer (tenant_id, phone) WHERE phone IS NOT NULL;
CREATE INDEX ix_customer_name_trgm ON customer USING gin (display_name gin_trgm_ops);
CREATE INDEX ix_customer_debtors ON customer (tenant_id) WHERE credit_balance_cents > 0;

-- ---------------------------------------------------------------------
-- Sessions de caisse (ouverture avec fond de caisse, clôture avec
-- comptage, rapport Z) et mouvements d'espèces hors vente.
-- ---------------------------------------------------------------------
CREATE TABLE cash_session (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant (id),
    terminal_id          varchar(40) NOT NULL,
    status               varchar(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_by            uuid        NOT NULL REFERENCES app_user (id),
    opened_at            timestamptz NOT NULL,
    opening_float_cents  bigint      NOT NULL CHECK (opening_float_cents >= 0),
    closed_by            uuid REFERENCES app_user (id),
    closed_at            timestamptz,
    expected_cash_cents  bigint,
    counted_cash_cents   bigint,
    difference_cents     bigint,
    notes                varchar(500),
    version              bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_cash_session_open ON cash_session (tenant_id, terminal_id) WHERE status = 'OPEN';
CREATE INDEX ix_cash_session_period ON cash_session (tenant_id, opened_at DESC);

CREATE TABLE cash_movement (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    cash_session_id uuid        NOT NULL REFERENCES cash_session (id),
    kind            varchar(10) NOT NULL CHECK (kind IN ('CASH_IN', 'CASH_OUT', 'EXPENSE')),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    reason          varchar(255) NOT NULL,
    -- Compte de charge SYSCOHADA pour les dépenses (ex. 6052 électricité)
    expense_account varchar(10),
    recorded_by     uuid        NOT NULL REFERENCES app_user (id),
    occurred_at     timestamptz NOT NULL
);
CREATE INDEX ix_cash_movement_session ON cash_movement (cash_session_id);

-- ---------------------------------------------------------------------
-- Ventes : client, session de caisse, facture certifiée (DGI Niger /
-- SECeF) et moyens de paiement africains.
-- ---------------------------------------------------------------------
ALTER TABLE sale ADD COLUMN customer_id uuid REFERENCES customer (id);
ALTER TABLE sale ADD COLUMN cash_session_id uuid REFERENCES cash_session (id);
ALTER TABLE sale ADD COLUMN certification_status varchar(12) NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK (certification_status IN ('NOT_REQUIRED', 'PENDING', 'CERTIFIED', 'FAILED'));
ALTER TABLE sale ADD COLUMN certification_code varchar(64);
ALTER TABLE sale ADD COLUMN certification_qr text;
ALTER TABLE sale ADD COLUMN certified_at timestamptz;
ALTER TABLE sale ADD COLUMN certification_error varchar(255);
CREATE INDEX ix_sale_customer ON sale (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_sale_certification_pending ON sale (tenant_id, occurred_at) WHERE certification_status = 'PENDING';
CREATE INDEX ix_sale_cash_session ON sale (cash_session_id) WHERE cash_session_id IS NOT NULL;

ALTER TABLE sale_payment DROP CONSTRAINT sale_payment_method_check;
ALTER TABLE sale_payment ADD CONSTRAINT sale_payment_method_check CHECK (method IN
    ('CASH', 'CARD', 'CHECK', 'THIRD_PARTY_PAYER', 'VOUCHER', 'MOBILE_MONEY', 'CREDIT'));
-- Opérateur mobile money (AIRTEL_MONEY, MOOV_MONEY, ORANGE_MONEY, ZAMANI_CASH, MYNITA, WAVE...)
ALTER TABLE sale_payment ADD COLUMN provider varchar(30);
ALTER TABLE sale_payment ADD COLUMN customer_id uuid REFERENCES customer (id);

-- Encaissement des créances clients (règlement d'une ardoise).
CREATE TABLE customer_payment (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    customer_id     uuid        NOT NULL REFERENCES customer (id),
    cash_session_id uuid REFERENCES cash_session (id),
    method          varchar(20) NOT NULL CHECK (method IN ('CASH', 'CARD', 'CHECK', 'MOBILE_MONEY', 'VOUCHER')),
    provider        varchar(30),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    reference       varchar(64),
    received_by     uuid        NOT NULL REFERENCES app_user (id),
    received_at     timestamptz NOT NULL
);
CREATE INDEX ix_customer_payment_customer ON customer_payment (customer_id, received_at DESC);

-- ---------------------------------------------------------------------
-- Journal d'audit : chaîne par (tenant, origine) car le hub et le cloud
-- écrivent chacun leurs propres événements pour une même pharmacie.
-- ---------------------------------------------------------------------
ALTER TABLE audit_event ADD COLUMN origin varchar(5) NOT NULL DEFAULT 'CLOUD' CHECK (origin IN ('CLOUD', 'HUB'));
ALTER TABLE audit_event DROP CONSTRAINT audit_event_tenant_id_sequence_no_key;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_chain_key UNIQUE (tenant_id, origin, sequence_no);

-- ---------------------------------------------------------------------
-- Nouvelles permissions et attribution aux rôles existants
-- ---------------------------------------------------------------------
INSERT INTO permission (code, category, description) VALUES
    ('CASH_SESSION_MANAGE',  'SALES',       'Ouvrir et clôturer une session de caisse'),
    ('CASH_EXPENSE',         'SALES',       'Enregistrer une sortie de caisse ou une dépense'),
    ('CREDIT_SALE',          'SALES',       'Vendre à crédit à un client'),
    ('CUSTOMER_MANAGE',      'SALES',       'Gérer les fiches clients et encaisser les créances'),
    ('PROCUREMENT_VIEW',     'PROCUREMENT', 'Consulter fournisseurs et commandes'),
    ('PROCUREMENT_MANAGE',   'PROCUREMENT', 'Préparer, envoyer et réceptionner les commandes'),
    ('ACCOUNTING_VIEW',      'ACCOUNTING',  'Consulter journaux, balance, résultat et bilan'),
    ('ACCOUNTING_MANAGE',    'ACCOUNTING',  'Clôturer les périodes et passer des écritures diverses'),
    ('HUB_ADMIN',            'ADMIN',       'Administrer le serveur local (synchronisation, sauvegardes)');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('CASH_SESSION_MANAGE', 'CASH_EXPENSE', 'CREDIT_SALE', 'CUSTOMER_MANAGE',
                                'PROCUREMENT_VIEW', 'PROCUREMENT_MANAGE', 'ACCOUNTING_VIEW', 'ACCOUNTING_MANAGE',
                                'HUB_ADMIN')
WHERE r.code = 'TITULAIRE';

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('CASH_SESSION_MANAGE', 'CASH_EXPENSE', 'CREDIT_SALE', 'CUSTOMER_MANAGE',
                                'PROCUREMENT_VIEW', 'PROCUREMENT_MANAGE', 'ACCOUNTING_VIEW')
WHERE r.code = 'ADJOINT';

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('PROCUREMENT_VIEW', 'PROCUREMENT_MANAGE')
WHERE r.code = 'GESTIONNAIRE_STOCK';

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('CASH_SESSION_MANAGE', 'CUSTOMER_MANAGE')
WHERE r.code IN ('CAISSIER', 'PREPARATEUR');

-- ---------------------------------------------------------------------
-- Droits et RLS
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON
    signing_key, auth_session, hub_node, hub_pairing_code, node_setting,
    customer, cash_session, cash_movement, customer_payment
    TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['auth_session', 'customer', 'cash_session', 'cash_movement', 'customer_payment']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Les clés publiques sont lisibles par tous (vérification de signature) ;
-- un nœud n'enregistre que les siennes.
ALTER TABLE signing_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_signing_key_read ON signing_key FOR SELECT TO pharmacore_app USING (true);
CREATE POLICY p_signing_key_write ON signing_key FOR ALL TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

ALTER TABLE hub_node ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_hub_node ON hub_node TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

ALTER TABLE hub_pairing_code ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_pairing_platform ON hub_pairing_code TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- node_setting n'est pas tenant : lecture/écriture par les processus système seulement.
ALTER TABLE node_setting ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_node_setting ON node_setting TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());
