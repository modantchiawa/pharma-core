-- =====================================================================
-- V5 : approvisionnement — fournisseurs (grossistes-répartiteurs),
--      fiche de commande du lendemain, réceptions de livraison.
-- =====================================================================

CREATE TABLE supplier (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    code             varchar(20)  NOT NULL,
    name             varchar(150) NOT NULL,
    contact_name     varchar(150),
    phone            varchar(30),
    whatsapp         varchar(30),
    email            varchar(200),
    address          varchar(300),
    -- Délai de livraison habituel, en jours ouvrés
    lead_time_days   smallint     NOT NULL DEFAULT 1 CHECK (lead_time_days BETWEEN 0 AND 90),
    -- Jours de commande habituels (ex. MON,WED,FRI), vide = tous les jours
    order_days       varchar(30),
    min_order_cents  bigint       NOT NULL DEFAULT 0 CHECK (min_order_cents >= 0),
    payment_terms    varchar(100),
    active           boolean      NOT NULL DEFAULT true,
    notes            varchar(500),
    version          bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE supplier_product (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant (id),
    supplier_id          uuid        NOT NULL REFERENCES supplier (id),
    product_id           uuid        NOT NULL REFERENCES product (id),
    supplier_reference   varchar(40),
    purchase_price_cents bigint CHECK (purchase_price_cents >= 0),
    pack_size            smallint    NOT NULL DEFAULT 1 CHECK (pack_size >= 1),
    preferred            boolean     NOT NULL DEFAULT false,
    last_purchase_at     timestamptz,
    version              bigint      NOT NULL DEFAULT 0,
    UNIQUE (supplier_id, product_id)
);
CREATE INDEX ix_supplier_product_product ON supplier_product (tenant_id, product_id);

CREATE TABLE purchase_order (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    supplier_id   uuid         NOT NULL REFERENCES supplier (id),
    number        varchar(30)  NOT NULL,
    status        varchar(20)  NOT NULL CHECK (status IN
                      ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    created_by    uuid         NOT NULL REFERENCES app_user (id),
    created_at    timestamptz  NOT NULL,
    sent_at       timestamptz,
    expected_at   date,
    closed_at     timestamptz,
    notes         varchar(500),
    total_cents   bigint       NOT NULL DEFAULT 0,
    version       bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, number)
);
CREATE INDEX ix_purchase_order_status ON purchase_order (tenant_id, status, created_at DESC);

CREATE TABLE purchase_order_line (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid     NOT NULL REFERENCES tenant (id),
    purchase_order_id  uuid     NOT NULL REFERENCES purchase_order (id),
    line_no            smallint NOT NULL,
    product_id         uuid     NOT NULL REFERENCES product (id),
    quantity_ordered   integer  NOT NULL CHECK (quantity_ordered > 0),
    quantity_received  integer  NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    unit_cost_cents    bigint   NOT NULL DEFAULT 0 CHECK (unit_cost_cents >= 0),
    pack_size          smallint NOT NULL DEFAULT 1,
    suggested_quantity integer,
    reason             varchar(255),
    UNIQUE (purchase_order_id, product_id)
);

-- Réception d'une livraison (bon de livraison / facture fournisseur) :
-- entrée des lots en stock et écriture d'achat.
CREATE TABLE goods_receipt (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenant (id),
    supplier_id             uuid        NOT NULL REFERENCES supplier (id),
    purchase_order_id       uuid REFERENCES purchase_order (id),
    supplier_invoice_number varchar(40),
    invoice_date            date,
    received_by             uuid        NOT NULL REFERENCES app_user (id),
    received_at             timestamptz NOT NULL,
    total_excl_vat_cents    bigint      NOT NULL DEFAULT 0,
    vat_cents               bigint      NOT NULL DEFAULT 0,
    notes                   varchar(500)
);
CREATE INDEX ix_goods_receipt_supplier ON goods_receipt (tenant_id, supplier_id, received_at DESC);

CREATE TABLE goods_receipt_line (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    goods_receipt_id    uuid        NOT NULL REFERENCES goods_receipt (id),
    product_id          uuid        NOT NULL REFERENCES product (id),
    lot_id              uuid        NOT NULL REFERENCES stock_lot (id),
    lot_number          varchar(40) NOT NULL,
    expiry_date         date        NOT NULL,
    quantity            integer     NOT NULL CHECK (quantity > 0),
    unit_cost_cents     bigint      NOT NULL CHECK (unit_cost_cents >= 0),
    vat_rate            numeric(5, 2) NOT NULL DEFAULT 0,
    selling_price_cents bigint CHECK (selling_price_cents >= 0)
);
CREATE INDEX ix_goods_receipt_line_receipt ON goods_receipt_line (goods_receipt_id);

-- Paramètres de réapprovisionnement par produit : jours de couverture
-- souhaités et stock de sécurité, complément des seuils existants.
ALTER TABLE stock ADD COLUMN coverage_days smallint NOT NULL DEFAULT 7 CHECK (coverage_days BETWEEN 1 AND 180);
ALTER TABLE stock ADD COLUMN safety_days smallint NOT NULL DEFAULT 2 CHECK (safety_days BETWEEN 0 AND 60);
ALTER TABLE stock ADD COLUMN exclude_from_ordering boolean NOT NULL DEFAULT false;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    supplier, supplier_product, purchase_order, purchase_order_line, goods_receipt, goods_receipt_line
    TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['supplier', 'supplier_product', 'purchase_order', 'purchase_order_line',
                             'goods_receipt', 'goods_receipt_line']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;
