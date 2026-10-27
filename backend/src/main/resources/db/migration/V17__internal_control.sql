-- =====================================================================
-- V17 : contrôle interne et prévention de la fraude.
--  * Contrôle du jour : micro-inventaire tournant à l'aveugle, produits
--    tirés au sort selon un score de risque (sélection figée pour la journée).
--  * Quatre yeux : seconde validation (PIN d'une autre personne habilitée)
--    des sorties de stock, retours et annulations au-delà des seuils, et
--    toujours pour les stupéfiants.
--  * Sorties de stock déclarées (casse, périmé, perte, vol) et retours
--    partiels de vente remboursés en espèces.
--  * Réception à l'aveugle et séparation des tâches commande / réception.
--  * Alertes au titulaire (temps réel) et webhook sortant signé, livré via
--    une file d'attente persistante (fonctionne hub hors ligne).
-- Montants en unités mineures de la devise (francs entiers pour le XOF).
-- =====================================================================

INSERT INTO permission (code, category, description) VALUES
    ('CONTROL_VIEW',     'CONTROL', 'Consulter le contrôle interne : indicateurs par employé, registre des stupéfiants, alertes'),
    ('CONTROL_APPROVE',  'CONTROL', 'Donner la seconde validation (quatre yeux) des sorties de stock, retours et annulations sensibles'),
    ('CONTROL_SETTINGS', 'CONTROL', 'Régler les seuils du contrôle interne, les heures d''activité et le webhook des alertes');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('CONTROL_VIEW', 'CONTROL_APPROVE')
WHERE r.code IN ('TITULAIRE', 'ADJOINT');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code = 'CONTROL_SETTINGS'
WHERE r.code = 'TITULAIRE';

-- ---------------------------------------------------------------------
-- Mouvements de stock : sorties déclarées et retours de vente
-- ---------------------------------------------------------------------
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check CHECK (movement_type IN
    ('RECEIPT', 'SALE', 'SALE_SHORTFALL', 'SALE_CANCELLATION', 'ADJUSTMENT',
     'TRANSFER_OUT', 'TRANSFER_IN', 'RESERVATION', 'RESERVATION_RELEASE',
     'COUNT_ADJUSTMENT', 'EXPIRY_WRITE_OFF', 'WRITE_OFF', 'SALE_RETURN'));
CREATE INDEX ix_stock_movement_recorded_by ON stock_movement (tenant_id, recorded_by, occurred_at DESC);
CREATE INDEX ix_sale_cashier_occurred ON sale (tenant_id, cashier_user_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Réglages du contrôle interne (une ligne par pharmacie, créée au premier
-- accès). Un seuil à 0 désactive la règle, sauf pour les stupéfiants.
-- ---------------------------------------------------------------------
CREATE TABLE control_settings (
    tenant_id                   uuid PRIMARY KEY REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    micro_count_size            smallint     NOT NULL DEFAULT 6 CHECK (micro_count_size BETWEEN 5 AND 10),
    adjustment_approval_cents   bigint       NOT NULL DEFAULT 10000 CHECK (adjustment_approval_cents >= 0),
    refund_approval_cents       bigint       NOT NULL DEFAULT 5000 CHECK (refund_approval_cents >= 0),
    cancellation_approval_cents bigint       NOT NULL DEFAULT 5000 CHECK (cancellation_approval_cents >= 0),
    alert_adjustment_cents      bigint       NOT NULL DEFAULT 25000 CHECK (alert_adjustment_cents >= 0),
    alert_cancellation_cents    bigint       NOT NULL DEFAULT 10000 CHECK (alert_cancellation_cents >= 0),
    alert_cash_variance_cents   bigint       NOT NULL DEFAULT 2000 CHECK (alert_cash_variance_cents >= 0),
    pin_failure_alert_count     smallint     NOT NULL DEFAULT 3 CHECK (pin_failure_alert_count BETWEEN 2 AND 10),
    blind_receiving             boolean      NOT NULL DEFAULT false,
    receiving_separation        varchar(5)   NOT NULL DEFAULT 'OFF' CHECK (receiving_separation IN ('OFF', 'WARN', 'BLOCK')),
    -- [{"day":"MONDAY","open":"07:00","close":"22:00"}, ...] ; un jour absent = fermé
    activity_hours              jsonb        NOT NULL,
    webhook_enabled             boolean      NOT NULL DEFAULT false,
    webhook_url                 varchar(500),
    webhook_secret              varchar(128),
    -- Graine secrète du tirage du contrôle du jour : jamais exposée par l'API
    selection_salt              varchar(64)  NOT NULL,
    updated_by                  uuid,
    updated_at                  timestamptz  NOT NULL DEFAULT now(),
    version                     bigint       NOT NULL DEFAULT 0,
    CHECK (NOT webhook_enabled OR (webhook_url IS NOT NULL AND webhook_secret IS NOT NULL))
);

-- ---------------------------------------------------------------------
-- Quatre yeux : qui a demandé, qui a validé, pourquoi, pour quel montant
-- ---------------------------------------------------------------------
CREATE TABLE control_approval (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    action        varchar(30)  NOT NULL CHECK (action IN
                      ('STOCK_ADJUSTMENT', 'STOCK_WRITE_OFF', 'STOCK_COUNT', 'SALE_RETURN', 'SALE_CANCELLATION')),
    resource_type varchar(40)  NOT NULL,
    resource_id   uuid         NOT NULL,
    amount_cents  bigint       NOT NULL,
    narcotic      boolean      NOT NULL,
    requested_by  uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    approved_by   uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    reason        varchar(255) NOT NULL,
    approved_at   timestamptz  NOT NULL,
    CHECK (requested_by <> approved_by)
);
CREATE INDEX ix_control_approval_period ON control_approval (tenant_id, approved_at DESC);
CREATE INDEX ix_control_approval_resource ON control_approval (tenant_id, resource_type, resource_id);

-- ---------------------------------------------------------------------
-- Sorties de stock déclarées à la main (le mouvement WRITE_OFF y renvoie)
-- ---------------------------------------------------------------------
CREATE TABLE stock_write_off (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    lot_id          uuid         NOT NULL REFERENCES stock_lot (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id      uuid         NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    kind            varchar(10)  NOT NULL CHECK (kind IN ('CASSE', 'PERIME', 'PERTE', 'VOL')),
    quantity        integer      NOT NULL CHECK (quantity > 0),
    unit_cost_cents bigint       NOT NULL CHECK (unit_cost_cents >= 0),
    value_cents     bigint       NOT NULL CHECK (value_cents >= 0),
    reason          varchar(255) NOT NULL,
    recorded_by     uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    approval_id     uuid REFERENCES control_approval (id) DEFERRABLE INITIALLY IMMEDIATE,
    occurred_at     timestamptz  NOT NULL
);
CREATE INDEX ix_stock_write_off_period ON stock_write_off (tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Retours partiels de vente, remboursés en espèces
-- ---------------------------------------------------------------------
CREATE TABLE sale_return (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_id         uuid         NOT NULL REFERENCES sale (id) DEFERRABLE INITIALLY IMMEDIATE,
    refund_cents    bigint       NOT NULL CHECK (refund_cents > 0),
    reason          varchar(255) NOT NULL,
    terminal_id     varchar(40),
    cash_session_id uuid REFERENCES cash_session (id) DEFERRABLE INITIALLY IMMEDIATE,
    recorded_by     uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    approval_id     uuid REFERENCES control_approval (id) DEFERRABLE INITIALLY IMMEDIATE,
    occurred_at     timestamptz  NOT NULL
);
CREATE INDEX ix_sale_return_sale ON sale_return (tenant_id, sale_id);
CREATE INDEX ix_sale_return_period ON sale_return (tenant_id, occurred_at DESC);

CREATE TABLE sale_return_line (
    id             uuid PRIMARY KEY,
    tenant_id      uuid    NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_return_id uuid    NOT NULL REFERENCES sale_return (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_line_id   uuid    NOT NULL REFERENCES sale_line (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id     uuid    NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    quantity       integer NOT NULL CHECK (quantity > 0),
    refund_cents   bigint  NOT NULL CHECK (refund_cents >= 0)
);
CREATE INDEX ix_sale_return_line_return ON sale_return_line (sale_return_id);
CREATE INDEX ix_sale_return_line_sale_line ON sale_return_line (tenant_id, sale_line_id);

-- ---------------------------------------------------------------------
-- Contrôle du jour : la sélection est figée au premier calcul de la journée
-- ---------------------------------------------------------------------
CREATE TABLE control_daily_count (
    id             uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    count_date     date        NOT NULL,
    stock_count_id uuid REFERENCES stock_count (id) DEFERRABLE INITIALLY IMMEDIATE,
    selected_at    timestamptz NOT NULL,
    started_by     uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    started_at     timestamptz,
    version        bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, count_date)
);

CREATE TABLE control_daily_count_item (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    daily_count_id uuid         NOT NULL REFERENCES control_daily_count (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id     uuid         NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    rank           smallint     NOT NULL,
    score          integer      NOT NULL CHECK (score BETWEEN 0 AND 100),
    reasons        varchar(300) NOT NULL,
    UNIQUE (daily_count_id, product_id)
);

-- ---------------------------------------------------------------------
-- Réception contrôlée : écart avec la commande calculé après la saisie
-- ---------------------------------------------------------------------
CREATE TABLE goods_receipt_check (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    goods_receipt_id   uuid        NOT NULL UNIQUE REFERENCES goods_receipt (id) DEFERRABLE INITIALLY IMMEDIATE,
    purchase_order_id  uuid        NOT NULL REFERENCES purchase_order (id) DEFERRABLE INITIALLY IMMEDIATE,
    blind              boolean     NOT NULL,
    received_by        uuid        NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- Le réceptionnaire a aussi créé ou envoyé la commande (mode avertissement)
    separation_warning boolean     NOT NULL,
    discrepancy_lines  integer     NOT NULL CHECK (discrepancy_lines >= 0),
    discrepancy_units  integer     NOT NULL,
    -- [{"productId":..., "designation":..., "expected":12, "received":10, "difference":-2}]
    lines              jsonb       NOT NULL,
    checked_at         timestamptz NOT NULL
);
CREATE INDEX ix_goods_receipt_check_period ON goods_receipt_check (tenant_id, checked_at DESC);

-- ---------------------------------------------------------------------
-- Alertes au titulaire (jamais de donnée patient ou client) et file du webhook
-- ---------------------------------------------------------------------
CREATE TABLE control_alert (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    kind            varchar(30)  NOT NULL CHECK (kind IN
                        ('LARGE_ADJUSTMENT', 'CANCELLATION_AFTER_PAYMENT', 'CASH_VARIANCE', 'COUNT_DISCREPANCY',
                         'OUTSIDE_HOURS', 'PIN_FAILURES', 'RECEIPT_DISCREPANCY', 'TEST')),
    severity        varchar(8)   NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title           varchar(150) NOT NULL,
    detail          varchar(500) NOT NULL,
    resource_type   varchar(40),
    resource_id     uuid,
    staff_user_id   uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    amount_cents    bigint,
    occurred_at     timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL,
    acknowledged_at timestamptz,
    acknowledged_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    version         bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_control_alert_feed ON control_alert (tenant_id, created_at DESC);
CREATE INDEX ix_control_alert_unread ON control_alert (tenant_id) WHERE acknowledged_at IS NULL;

CREATE TABLE control_webhook_outbox (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    alert_id        uuid         NOT NULL REFERENCES control_alert (id) DEFERRABLE INITIALLY IMMEDIATE,
    payload         text         NOT NULL,
    status          varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        smallint     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    last_error      varchar(300),
    created_at      timestamptz  NOT NULL,
    delivered_at    timestamptz,
    version         bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_control_webhook_queue ON control_webhook_outbox (status, next_attempt_at);
CREATE INDEX ix_control_webhook_created ON control_webhook_outbox (status, created_at);
CREATE INDEX ix_control_webhook_tenant ON control_webhook_outbox (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------
-- Droits, isolation par pharmacie, synchronisation hub ↔ cloud
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON control_settings TO pharmacore_app;
GRANT SELECT, INSERT ON control_approval TO pharmacore_app;
GRANT SELECT, INSERT ON stock_write_off TO pharmacore_app;
GRANT SELECT, INSERT ON sale_return TO pharmacore_app;
GRANT SELECT, INSERT ON sale_return_line TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON control_daily_count TO pharmacore_app;
GRANT SELECT, INSERT ON control_daily_count_item TO pharmacore_app;
GRANT SELECT, INSERT ON goods_receipt_check TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON control_alert TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON control_webhook_outbox TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['control_settings', 'control_approval', 'stock_write_off', 'sale_return',
                             'sale_return_line', 'control_daily_count', 'control_daily_count_item',
                             'goods_receipt_check', 'control_alert', 'control_webhook_outbox']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Parents avant enfants : utilisateurs (24), lots (34), ventes (36-37),
-- réceptions (45), inventaires (61).
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('control_settings',         'HUB', 'TENANT', NULL, 65),
    ('control_approval',         'HUB', 'TENANT', NULL, 65),
    ('control_alert',            'HUB', 'TENANT', NULL, 65),
    ('stock_write_off',          'HUB', 'TENANT', NULL, 66),
    ('sale_return',              'HUB', 'TENANT', NULL, 66),
    ('control_daily_count',      'HUB', 'TENANT', NULL, 66),
    ('goods_receipt_check',      'HUB', 'TENANT', NULL, 66),
    ('control_webhook_outbox',   'HUB', 'TENANT', NULL, 66),
    ('sale_return_line',         'HUB', 'TENANT', NULL, 67),
    ('control_daily_count_item', 'HUB', 'TENANT', NULL, 67);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('control_settings', 'control_approval', 'stock_write_off', 'sale_return', 'sale_return_line',
                     'control_daily_count', 'control_daily_count_item', 'goods_receipt_check', 'control_alert',
                     'control_webhook_outbox');
