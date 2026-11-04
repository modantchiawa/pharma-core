-- =====================================================================
-- V25 — Discipline de caisse, bons et commandes clients, solde mobile money
--  * Vente interdite sans session de caisse ouverte sur le poste (réglable).
--  * Le caissier ne voit que ses ventes et ses encaissements ; il n'ouvre ni
--    ne clôture la caisse par défaut (CASH_SESSION_MANAGE retiré du rôle).
--  * Archive figée de toutes les opérations d'une session à sa clôture.
--  * Solde des portefeuilles mobile money (relevé par SMS ou saisi).
--  * Commandes clients : bons (produit vendu, payé, livré à l'arrivée) et
--    commandes spéciales (produit demandé, réservé à la réception).
-- Montants en unités mineures de la devise.
-- =====================================================================

-- Vente sans session ouverte : refusée en ligne, mise en revue hors ligne
ALTER TABLE pharmacy_config ADD COLUMN cash_session_required boolean NOT NULL DEFAULT true;

-- ---------------------------------------------------------------- permissions
INSERT INTO permission (code, category, description) VALUES
    ('SALES_VIEW_ALL', 'SALES', 'Voir les ventes et les encaissements de tous les vendeurs');

-- Rôles qui voyaient déjà tout (pilotage, rapprochement) : rien ne change pour eux
INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT DISTINCT r.id, 'SALES_VIEW_ALL', r.tenant_id
FROM app_role r
LEFT JOIN role_permission rp ON rp.role_id = r.id
WHERE r.code IN ('TITULAIRE', 'ADJOINT', 'PREPARATEUR')
   OR rp.permission_code IN ('REPORT_VIEW', 'PAYMENT_RECONCILE');

-- Le caissier n'ouvre ni ne clôture la caisse par défaut (le titulaire peut le lui rendre)
DELETE FROM role_permission rp
USING app_role r
WHERE rp.role_id = r.id AND r.code = 'CAISSIER' AND rp.permission_code = 'CASH_SESSION_MANAGE';

-- ---------------------------------------------------------------- archive de session
CREATE TABLE cash_session_archive (
    session_id   uuid PRIMARY KEY REFERENCES cash_session (id) DEFERRABLE INITIALLY IMMEDIATE,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- Toutes les opérations de la session (ventes, règlements, annulations, retours, mouvements,
    -- encaissements de créances, approbations), figées à la clôture
    content      jsonb       NOT NULL,
    -- SHA-256 du contenu : une archive modifiée ne correspond plus à son empreinte
    content_hash varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL
);
CREATE INDEX ix_cash_session_archive_tenant ON cash_session_archive (tenant_id, created_at DESC);

-- ---------------------------------------------------------------- solde mobile money
CREATE TABLE mobile_money_balance (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    provider        varchar(30) NOT NULL,
    balance_cents   bigint      NOT NULL CHECK (balance_cents >= 0),
    source          varchar(10) NOT NULL CHECK (source IN ('SMS', 'MANUAL')),
    notification_id uuid REFERENCES payment_notification (id) DEFERRABLE INITIALLY IMMEDIATE,
    note            varchar(255),
    recorded_by     uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    observed_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX ix_mobile_money_balance_latest ON mobile_money_balance (tenant_id, provider, observed_at DESC);

-- ---------------------------------------------------------------- commandes clients
CREATE TABLE customer_order (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    number            bigint       NOT NULL,
    -- VOUCHER : bon (vendu et payé, produit dû) ; SPECIAL : commande spéciale (payée au retrait)
    kind              varchar(10)  NOT NULL CHECK (kind IN ('VOUCHER', 'SPECIAL')),
    -- OPEN : en attente du produit ; READY : produit arrivé et réservé ; DELIVERED ; CANCELLED
    status            varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'READY', 'DELIVERED', 'CANCELLED')),
    customer_id       uuid REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    holder_name       varchar(150),
    holder_phone      varchar(30),
    product_id        uuid         NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    quantity          integer      NOT NULL CHECK (quantity BETWEEN 1 AND 9999),
    unit_price_cents  bigint       CHECK (unit_price_cents IS NULL OR unit_price_cents >= 0),
    -- Bon : la vente qui l'a payé ; commande spéciale : la vente du retrait
    sale_id           uuid REFERENCES sale (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- Bon : la ligne du ticket qui reçoit les lots à la remise (un retour les retrouve)
    sale_line_id      uuid REFERENCES sale_line (id) DEFERRABLE INITIALLY IMMEDIATE,
    delivered_sale_id uuid REFERENCES sale (id) DEFERRABLE INITIALLY IMMEDIATE,
    purchase_order_id uuid REFERENCES purchase_order (id) DEFERRABLE INITIALLY IMMEDIATE,
    notes             varchar(255),
    created_by        uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    created_at        timestamptz  NOT NULL,
    ready_at          timestamptz,
    delivered_at      timestamptz,
    delivered_by      uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    cancelled_at      timestamptz,
    cancel_reason     varchar(255),
    version           bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, number),
    CHECK (kind <> 'SPECIAL' OR customer_id IS NOT NULL OR holder_phone IS NOT NULL)
);
CREATE INDEX ix_customer_order_queue ON customer_order (tenant_id, status, created_at);
CREATE INDEX ix_customer_order_product ON customer_order (tenant_id, product_id, created_at)
    WHERE status IN ('OPEN', 'READY');
CREATE INDEX ix_customer_order_customer ON customer_order (tenant_id, customer_id, created_at DESC);
CREATE INDEX ix_customer_order_sale ON customer_order (sale_id) WHERE sale_id IS NOT NULL;

GRANT SELECT, INSERT ON cash_session_archive TO pharmacore_app;
GRANT SELECT, INSERT ON mobile_money_balance TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON customer_order TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['cash_session_archive', 'mobile_money_balance', 'customer_order']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Exploitation de l'officine : propriété du hub, après les sessions, ventes, commandes
-- fournisseurs (43) et confirmations de paiement (74).
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('cash_session_archive', 'HUB', 'TENANT', NULL, 75),
    ('mobile_money_balance', 'HUB', 'TENANT', NULL, 75),
    ('customer_order',       'HUB', 'TENANT', NULL, 76);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('cash_session_archive', 'mobile_money_balance', 'customer_order');
