-- =====================================================================
-- V10 : fiabilité du stock et de la caisse.
--  * Inventaires physiques à l'aveugle (comptage figé, écarts validés).
--  * Contrôle d'intégrité du grand livre des mouvements.
--  * Sortie automatique des lots périmés.
--  * Clôture de caisse par coupures, rapprochement par mode de paiement,
--    chaîne de hachage des rapports Z, approbation des écarts et des
--    annulations par un superviseur, rapprochement mobile money / carte.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Mouvements de stock : nouveaux types
-- ---------------------------------------------------------------------
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check CHECK (movement_type IN
    ('RECEIPT', 'SALE', 'SALE_SHORTFALL', 'SALE_CANCELLATION', 'ADJUSTMENT',
     'TRANSFER_OUT', 'TRANSFER_IN', 'RESERVATION', 'RESERVATION_RELEASE',
     'COUNT_ADJUSTMENT', 'EXPIRY_WRITE_OFF'));
CREATE INDEX IF NOT EXISTS ix_stock_movement_lot ON stock_movement (tenant_id, lot_id) WHERE lot_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_stock_movement_type_period ON stock_movement (tenant_id, movement_type, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Inventaire physique. L'attendu est figé au démarrage ; le compteur ne
-- le voit jamais (comptage à l'aveugle). La validation applique l'écart
-- (compté − attendu) à la quantité courante du lot.
-- ---------------------------------------------------------------------
CREATE TABLE stock_count (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    status       varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'VALIDATED', 'CANCELLED')),
    label        varchar(150) NOT NULL,
    location     varchar(50),
    started_by   uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    started_at   timestamptz  NOT NULL,
    validated_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    validated_at timestamptz,
    notes        varchar(500),
    version      bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_stock_count_status ON stock_count (tenant_id, status, started_at DESC);

CREATE TABLE stock_count_line (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    count_id          uuid        NOT NULL REFERENCES stock_count (id) DEFERRABLE INITIALLY IMMEDIATE,
    stock_id          uuid        NOT NULL REFERENCES stock (id) DEFERRABLE INITIALLY IMMEDIATE,
    lot_id            uuid        NOT NULL REFERENCES stock_lot (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id        uuid        NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    lot_number        varchar(40) NOT NULL,
    expiry_date       date        NOT NULL,
    expected_quantity integer     NOT NULL CHECK (expected_quantity >= 0),
    counted_quantity  integer CHECK (counted_quantity >= 0),
    counted_by        uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    counted_at        timestamptz,
    UNIQUE (count_id, lot_id)
);
CREATE INDEX ix_stock_count_line_count ON stock_count_line (tenant_id, count_id);

-- ---------------------------------------------------------------------
-- Contrôle d'intégrité : quantité de chaque lot recalculée à partir de
-- ses mouvements, réservations recalculées à partir du journal.
-- ---------------------------------------------------------------------
CREATE TABLE stock_integrity_report (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    run_at       timestamptz NOT NULL,
    triggered_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    lots_checked integer     NOT NULL CHECK (lots_checked >= 0),
    mismatches   integer     NOT NULL CHECK (mismatches >= 0),
    details      jsonb       NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX ix_stock_integrity_report_run ON stock_integrity_report (tenant_id, run_at DESC);

-- ---------------------------------------------------------------------
-- Officine : seuils d'approbation (0 = jamais requis)
-- ---------------------------------------------------------------------
ALTER TABLE pharmacy_config ADD COLUMN cash_difference_threshold_cents bigint NOT NULL DEFAULT 0
    CHECK (cash_difference_threshold_cents >= 0);
ALTER TABLE pharmacy_config ADD COLUMN cancel_approval_threshold_cents bigint NOT NULL DEFAULT 0
    CHECK (cancel_approval_threshold_cents >= 0);

-- ---------------------------------------------------------------------
-- Session de caisse : comptage par coupures, approbation, chaîne Z
-- ---------------------------------------------------------------------
ALTER TABLE cash_session ADD COLUMN counted_breakdown jsonb;
ALTER TABLE cash_session ADD COLUMN closing_requires_approval boolean NOT NULL DEFAULT false;
ALTER TABLE cash_session ADD COLUMN approved_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE cash_session ADD COLUMN approved_at timestamptz;
ALTER TABLE cash_session ADD COLUMN approval_note varchar(500);
ALTER TABLE cash_session ADD COLUMN previous_report_hash varchar(64);
ALTER TABLE cash_session ADD COLUMN report_hash varchar(64);
CREATE INDEX ix_cash_session_terminal_closed ON cash_session (tenant_id, terminal_id, closed_at DESC)
    WHERE status = 'CLOSED';
CREATE INDEX ix_cash_session_pending_approval ON cash_session (tenant_id, closed_at DESC)
    WHERE closing_requires_approval AND approved_at IS NULL;

CREATE TABLE cash_session_reconciliation (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    cash_session_id uuid        NOT NULL REFERENCES cash_session (id) DEFERRABLE INITIALLY IMMEDIATE,
    method          varchar(20) NOT NULL,
    provider        varchar(30),
    expected_cents  bigint      NOT NULL,
    declared_cents  bigint      NOT NULL,
    difference_cents bigint     NOT NULL
);
CREATE UNIQUE INDEX ux_cash_session_reconciliation ON cash_session_reconciliation
    (cash_session_id, method, coalesce(provider, ''));

-- ---------------------------------------------------------------------
-- Ventes : approbation superviseur des annulations
-- ---------------------------------------------------------------------
ALTER TABLE sale ADD COLUMN approved_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE sale ADD COLUMN approved_at timestamptz;

-- ---------------------------------------------------------------------
-- Paiements : rapprochement mobile money / carte, référence unique
-- ---------------------------------------------------------------------
ALTER TABLE sale_payment ADD COLUMN confirmation_status varchar(15) NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK (confirmation_status IN ('NOT_REQUIRED', 'PENDING', 'CONFIRMED', 'REJECTED'));
ALTER TABLE sale_payment ADD COLUMN confirmed_by uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE sale_payment ADD COLUMN confirmed_at timestamptz;
ALTER TABLE sale_payment ADD COLUMN rejection_reason varchar(255);
CREATE INDEX ix_sale_payment_pending ON sale_payment (tenant_id, provider) WHERE confirmation_status = 'PENDING';
-- Une référence de transaction d'un opérateur ne peut justifier deux paiements.
CREATE UNIQUE INDEX ux_sale_payment_reference ON sale_payment (tenant_id, provider, reference)
    WHERE provider IS NOT NULL AND reference IS NOT NULL;

-- ---------------------------------------------------------------------
-- Droits, RLS, synchronisation
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_count, stock_count_line, cash_session_reconciliation TO pharmacore_app;
GRANT SELECT, INSERT ON stock_integrity_report TO pharmacore_app;
GRANT UPDATE ON sale_payment TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['stock_count', 'stock_count_line', 'stock_integrity_report', 'cash_session_reconciliation']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('stock_count',                 'HUB', 'TENANT', NULL, 61),
    ('stock_count_line',            'HUB', 'TENANT', NULL, 62),
    ('stock_integrity_report',      'HUB', 'TENANT', NULL, 63),
    ('cash_session_reconciliation', 'HUB', 'TENANT', NULL, 64);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('stock_count', 'stock_count_line', 'stock_integrity_report', 'cash_session_reconciliation');
