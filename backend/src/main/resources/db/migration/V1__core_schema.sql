-- =====================================================================
-- PharmaCore PGO - Schéma principal
-- Stratégie multi-tenant : colonne discriminante tenant_id + Row-Level
-- Security PostgreSQL (défense en profondeur, cf. V2).
-- Montants : entiers en unités mineures (centimes) pour éviter tout
-- arrondi flottant.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- Rôles techniques
--  * pharmacore_app            : rôle d'exécution de l'application (RLS appliquée)
--  * pharmacore_network_reader : propriétaire des fonctions SECURITY DEFINER
--                                du réseau inter-pharmacies, avec des droits
--                                colonne par colonne (aucune donnée financière)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_app') THEN
        CREATE ROLE pharmacore_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_network_reader') THEN
        CREATE ROLE pharmacore_network_reader NOLOGIN;
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- Contexte de session (positionné par TenantAwareDataSource à chaque
-- emprunt de connexion au pool)
-- ---------------------------------------------------------------------
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION app_is_platform_admin() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(current_setting('app.platform_admin', true), '') = 'on' $$;

-- ---------------------------------------------------------------------
-- Tenants (pharmacies) & personnalisation
-- ---------------------------------------------------------------------
CREATE TABLE tenant (
    id                      uuid PRIMARY KEY,
    slug                    varchar(63)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    legal_name              varchar(200) NOT NULL,
    status                  varchar(20)  NOT NULL CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    subscription_plan       varchar(30)  NOT NULL,
    subscription_expires_at timestamptz,
    currency                varchar(3)      NOT NULL DEFAULT 'EUR',
    time_zone               varchar(64)  NOT NULL DEFAULT 'Europe/Paris',
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    version                 bigint       NOT NULL DEFAULT 0
);

CREATE TABLE tenant_asset (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    kind         varchar(20)  NOT NULL CHECK (kind IN ('LOGO')),
    content_type varchar(50)  NOT NULL,
    content      bytea        NOT NULL,
    sha256       varchar(64)     NOT NULL,
    size_bytes   integer      NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 524288),
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_tenant_asset_tenant ON tenant_asset (tenant_id);

CREATE TABLE pharmacy_config (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL UNIQUE REFERENCES tenant (id),
    display_name        varchar(150) NOT NULL,
    address_line1       varchar(200),
    address_line2       varchar(200),
    postal_code         varchar(20),
    city                varchar(120),
    country_code        varchar(2)      NOT NULL DEFAULT 'FR',
    phone               varchar(30),
    email               varchar(200),
    legal_identifier    varchar(30),
    finess_number       varchar(9),
    ordonnancier_prefix varchar(10),
    primary_color       varchar(7)      NOT NULL DEFAULT '#0F766E' CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    secondary_color     varchar(7)      NOT NULL DEFAULT '#134E4A' CHECK (secondary_color ~ '^#[0-9A-Fa-f]{6}$'),
    accent_color        varchar(7)      NOT NULL DEFAULT '#F59E0B' CHECK (accent_color ~ '^#[0-9A-Fa-f]{6}$'),
    receipt_header      varchar(500),
    receipt_footer      varchar(500),
    logo_asset_id       uuid REFERENCES tenant_asset (id),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0
);

CREATE TABLE terminal_heartbeat (
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    terminal_id   varchar(40) NOT NULL,
    app_version   varchar(40) NOT NULL,
    pending_sales integer     NOT NULL CHECK (pending_sales >= 0),
    last_sync_at  timestamptz,
    last_seen_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, terminal_id)
);

-- Compteurs séquentiels par tenant (n° ordonnancier, n° de facture...)
CREATE TABLE tenant_sequence (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    name       varchar(40) NOT NULL,
    next_value bigint      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, name)
);

-- ---------------------------------------------------------------------
-- Identité & droits (RBAC + attributs ABAC sur le rôle)
-- ---------------------------------------------------------------------
CREATE TABLE permission (
    code        varchar(64)  PRIMARY KEY,
    category    varchar(40)  NOT NULL,
    description varchar(255) NOT NULL
);

CREATE TABLE app_role (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    code                 varchar(50)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    name                 varchar(100) NOT NULL,
    system_role          boolean      NOT NULL DEFAULT false,
    max_discount_percent numeric(5, 2) NOT NULL DEFAULT 0 CHECK (max_discount_percent BETWEEN 0 AND 100),
    version              bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE role_permission (
    role_id         uuid        NOT NULL REFERENCES app_role (id) ON DELETE CASCADE,
    permission_code varchar(64) NOT NULL REFERENCES permission (code),
    -- Renseigné par défaut depuis le contexte de session (collections JPA)
    tenant_id       uuid        NOT NULL DEFAULT app_current_tenant() REFERENCES tenant (id),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE app_user (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    external_subject varchar(255) NOT NULL,
    email            varchar(200) NOT NULL,
    display_name     varchar(150) NOT NULL,
    active           boolean      NOT NULL DEFAULT true,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, external_subject),
    UNIQUE (tenant_id, email)
);

CREATE TABLE user_role (
    user_id   uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id   uuid NOT NULL REFERENCES app_role (id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL DEFAULT app_current_tenant() REFERENCES tenant (id),
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------
-- Catalogue national commun (géré par le Super-Admin, non tenant)
-- ---------------------------------------------------------------------
CREATE TABLE product (
    id                 uuid PRIMARY KEY,
    cip13              varchar(13)     NOT NULL UNIQUE CHECK (cip13 ~ '^[0-9]{13}$'),
    ean13              varchar(13) CHECK (ean13 ~ '^[0-9]{13}$'),
    designation        varchar(255) NOT NULL,
    dci                varchar(255),
    pharmaceutical_form varchar(100),
    dosage             varchar(100),
    laboratory         varchar(150),
    atc_code           varchar(10),
    listing            varchar(20)  NOT NULL CHECK (listing IN ('NONE', 'LIST_I', 'LIST_II', 'STUPEFIANT')),
    vat_rate           numeric(5, 2) NOT NULL CHECK (vat_rate >= 0),
    public_price_cents bigint CHECK (public_price_cents >= 0),
    reimbursement_rate smallint CHECK (reimbursement_rate BETWEEN 0 AND 100),
    monograph_url      varchar(500),
    active             boolean      NOT NULL DEFAULT true,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_product_updated_at ON product (updated_at);
CREATE INDEX ix_product_ean13 ON product (ean13) WHERE ean13 IS NOT NULL;
CREATE INDEX ix_product_designation_trgm ON product USING gin (designation gin_trgm_ops);
CREATE INDEX ix_product_dci_trgm ON product USING gin (dci gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Stocks par lot (FEFO)
-- ---------------------------------------------------------------------
CREATE TABLE stock (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    product_id          uuid        NOT NULL REFERENCES product (id),
    selling_price_cents bigint CHECK (selling_price_cents >= 0),
    reorder_threshold   integer     NOT NULL DEFAULT 0 CHECK (reorder_threshold >= 0),
    reorder_quantity    integer     NOT NULL DEFAULT 0 CHECK (reorder_quantity >= 0),
    reserved_quantity   integer     NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- Unités vendues sans lot disponible (vente hors-ligne) : écart à régulariser
    shortfall_quantity  integer     NOT NULL DEFAULT 0 CHECK (shortfall_quantity >= 0),
    location            varchar(50),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, product_id)
);
CREATE INDEX ix_stock_tenant_updated ON stock (tenant_id, updated_at);

CREATE TABLE stock_lot (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    stock_id        uuid        NOT NULL REFERENCES stock (id),
    lot_number      varchar(40) NOT NULL,
    expiry_date     date        NOT NULL,
    quantity        integer     NOT NULL CHECK (quantity >= 0),
    unit_cost_cents bigint      NOT NULL CHECK (unit_cost_cents >= 0),
    received_at     timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    UNIQUE (stock_id, lot_number, expiry_date)
);
CREATE INDEX ix_stock_lot_fefo ON stock_lot (stock_id, expiry_date) WHERE quantity > 0;
CREATE INDEX ix_stock_lot_expiry ON stock_lot (tenant_id, expiry_date) WHERE quantity > 0;

CREATE TABLE stock_movement (
    id             uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    stock_id       uuid        NOT NULL REFERENCES stock (id),
    lot_id         uuid REFERENCES stock_lot (id),
    movement_type  varchar(30) NOT NULL CHECK (movement_type IN
                       ('RECEIPT', 'SALE', 'SALE_SHORTFALL', 'SALE_CANCELLATION', 'ADJUSTMENT',
                        'TRANSFER_OUT', 'RESERVATION', 'RESERVATION_RELEASE')),
    quantity       integer     NOT NULL,
    reference_type varchar(40),
    reference_id   uuid,
    occurred_at    timestamptz NOT NULL,
    recorded_by    uuid
);
CREATE INDEX ix_stock_movement_stock ON stock_movement (tenant_id, stock_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Ventes (POS) - idempotence par (tenant_id, client_sale_id)
-- ---------------------------------------------------------------------
CREATE TABLE sale (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    client_sale_id       uuid         NOT NULL,
    terminal_id          varchar(40)  NOT NULL,
    ticket_number        varchar(40)  NOT NULL,
    origin               varchar(20)  NOT NULL CHECK (origin IN ('ONLINE', 'OFFLINE_REPLAY')),
    status               varchar(20)  NOT NULL CHECK (status IN ('COMPLETED', 'CANCELLED')),
    review_required      boolean      NOT NULL DEFAULT false,
    anomalies            varchar(500),
    occurred_at          timestamptz  NOT NULL,
    recorded_at          timestamptz  NOT NULL,
    cashier_user_id      uuid         NOT NULL REFERENCES app_user (id),
    total_gross_cents    bigint       NOT NULL,
    total_discount_cents bigint       NOT NULL,
    total_net_cents      bigint       NOT NULL,
    prescriber_name      varchar(150),
    prescriber_rpps      varchar(11),
    patient_reference    varchar(64),
    ordonnancier_number  bigint,
    cancelled_at         timestamptz,
    cancelled_by         uuid REFERENCES app_user (id),
    cancel_reason        varchar(255),
    version              bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, client_sale_id)
);
CREATE INDEX ix_sale_tenant_occurred ON sale (tenant_id, occurred_at DESC);
CREATE INDEX ix_sale_review ON sale (tenant_id) WHERE review_required;

CREATE TABLE sale_line (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    sale_id          uuid          NOT NULL REFERENCES sale (id),
    line_no          smallint      NOT NULL,
    product_id       uuid          NOT NULL REFERENCES product (id),
    quantity         integer       NOT NULL CHECK (quantity > 0),
    unit_price_cents bigint        NOT NULL CHECK (unit_price_cents >= 0),
    discount_cents   bigint        NOT NULL CHECK (discount_cents >= 0),
    net_cents        bigint        NOT NULL,
    vat_rate         numeric(5, 2) NOT NULL,
    UNIQUE (sale_id, line_no)
);

CREATE TABLE sale_line_allocation (
    id           uuid PRIMARY KEY,
    tenant_id    uuid    NOT NULL REFERENCES tenant (id),
    sale_line_id uuid    NOT NULL REFERENCES sale_line (id),
    lot_id       uuid    NOT NULL REFERENCES stock_lot (id),
    quantity     integer NOT NULL CHECK (quantity > 0)
);
CREATE INDEX ix_sale_line_allocation_line ON sale_line_allocation (sale_line_id);

CREATE TABLE sale_payment (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    sale_id          uuid        NOT NULL REFERENCES sale (id),
    method           varchar(20) NOT NULL CHECK (method IN ('CASH', 'CARD', 'CHECK', 'THIRD_PARTY_PAYER', 'VOUCHER')),
    amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),
    third_party_code varchar(20),
    reference        varchar(64)
);
CREATE INDEX ix_sale_payment_sale ON sale_payment (sale_id);

-- ---------------------------------------------------------------------
-- Réseau inter-pharmacies
-- ---------------------------------------------------------------------
CREATE TABLE pharmacy_network (
    id                    uuid PRIMARY KEY,
    name                  varchar(150) NOT NULL,
    region                varchar(100),
    coordinator_tenant_id uuid         NOT NULL REFERENCES tenant (id),
    quantity_cap          integer      NOT NULL DEFAULT 10 CHECK (quantity_cap BETWEEN 1 AND 1000),
    created_at            timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE network_membership (
    network_id  uuid        NOT NULL REFERENCES pharmacy_network (id),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    share_stock boolean     NOT NULL DEFAULT true,
    status      varchar(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'LEFT')),
    joined_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (network_id, tenant_id)
);
CREATE INDEX ix_network_membership_tenant ON network_membership (tenant_id) WHERE status = 'ACTIVE';

CREATE TABLE inter_pharmacy_transfer (
    id                  uuid PRIMARY KEY,
    network_id          uuid        NOT NULL REFERENCES pharmacy_network (id),
    requester_tenant_id uuid        NOT NULL REFERENCES tenant (id),
    provider_tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    product_id          uuid        NOT NULL REFERENCES product (id),
    quantity            integer     NOT NULL CHECK (quantity > 0),
    status              varchar(20) NOT NULL CHECK (status IN
                            ('REQUESTED', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'COMPLETED')),
    requested_by        uuid        NOT NULL,
    requester_note      varchar(500),
    provider_note       varchar(500),
    requested_at        timestamptz NOT NULL,
    responded_at        timestamptz,
    reservation_expires_at timestamptz,
    -- Vrai tant que des unités sont réservées dans le stock du fournisseur
    reservation_held    boolean     NOT NULL DEFAULT false,
    completed_at        timestamptz,
    version             bigint      NOT NULL DEFAULT 0,
    CHECK (requester_tenant_id <> provider_tenant_id)
);
CREATE INDEX ix_transfer_requester ON inter_pharmacy_transfer (requester_tenant_id, requested_at DESC);
CREATE INDEX ix_transfer_provider ON inter_pharmacy_transfer (provider_tenant_id, requested_at DESC);
CREATE INDEX ix_transfer_expiry ON inter_pharmacy_transfer (reservation_expires_at) WHERE status = 'ACCEPTED';
CREATE INDEX ix_transfer_dangling_reservation ON inter_pharmacy_transfer (provider_tenant_id)
    WHERE reservation_held AND status <> 'ACCEPTED';

-- ---------------------------------------------------------------------
-- Planning des gardes (partagé au sein d'un réseau)
-- ---------------------------------------------------------------------
CREATE TABLE guard_schedule (
    id                 uuid PRIMARY KEY,
    network_id         uuid         NOT NULL REFERENCES pharmacy_network (id),
    pharmacy_tenant_id uuid         NOT NULL REFERENCES tenant (id),
    guard_type         varchar(20)  NOT NULL CHECK (guard_type IN ('NIGHT', 'SUNDAY', 'PUBLIC_HOLIDAY', 'EXTENDED_DAY')),
    starts_at          timestamptz  NOT NULL,
    ends_at            timestamptz  NOT NULL,
    notes              varchar(500),
    created_by         uuid         NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    CONSTRAINT ex_guard_no_overlap EXCLUDE USING gist (
        network_id WITH =, pharmacy_tenant_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
);
CREATE INDEX ix_guard_network_period ON guard_schedule USING gist (network_id, tstzrange(starts_at, ends_at));

-- ---------------------------------------------------------------------
-- Messagerie inter-pharmacies
-- ---------------------------------------------------------------------
CREATE TABLE conversation (
    id                    uuid PRIMARY KEY,
    network_id            uuid         NOT NULL REFERENCES pharmacy_network (id),
    kind                  varchar(20)  NOT NULL CHECK (kind IN ('NETWORK_CHANNEL', 'DIRECT')),
    title                 varchar(150) NOT NULL,
    participant_a_tenant  uuid REFERENCES tenant (id),
    participant_b_tenant  uuid REFERENCES tenant (id),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    CHECK ((kind = 'NETWORK_CHANNEL' AND participant_a_tenant IS NULL AND participant_b_tenant IS NULL)
        OR (kind = 'DIRECT' AND participant_a_tenant IS NOT NULL AND participant_b_tenant IS NOT NULL
            AND participant_a_tenant < participant_b_tenant))
);
CREATE UNIQUE INDEX ux_conversation_direct ON conversation (network_id, participant_a_tenant, participant_b_tenant)
    WHERE kind = 'DIRECT';

CREATE TABLE chat_message (
    id                    uuid PRIMARY KEY,
    conversation_id       uuid          NOT NULL REFERENCES conversation (id),
    sender_tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    sender_user_id        uuid          NOT NULL,
    sender_display_name   varchar(150)  NOT NULL,
    sender_pharmacy_name  varchar(150)  NOT NULL,
    kind                  varchar(20)   NOT NULL CHECK (kind IN ('TEXT', 'STOCK_SHORTAGE', 'HEALTH_ALERT', 'TRANSFER')),
    body                  varchar(4000) NOT NULL,
    related_product_id    uuid REFERENCES product (id),
    sent_at               timestamptz   NOT NULL
);
CREATE INDEX ix_chat_message_conversation ON chat_message (conversation_id, sent_at DESC);

-- ---------------------------------------------------------------------
-- Journal d'audit inaltérable (chaîne de hachage par tenant)
-- tenant_id = 00000000-0000-0000-0000-000000000000 pour la plateforme
-- ---------------------------------------------------------------------
CREATE TABLE audit_event (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL,
    sequence_no    bigint       NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    actor_type     varchar(20)  NOT NULL,
    actor_id       varchar(255) NOT NULL,
    action         varchar(80)  NOT NULL,
    resource_type  varchar(60)  NOT NULL,
    resource_id    varchar(80),
    details        text         NOT NULL,
    correlation_id varchar(64)  NOT NULL,
    previous_hash  varchar(64)     NOT NULL,
    hash           varchar(64)     NOT NULL,
    UNIQUE (tenant_id, sequence_no)
);
CREATE INDEX ix_audit_event_tenant_time ON audit_event (tenant_id, occurred_at DESC);

CREATE FUNCTION audit_event_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER trg_audit_event_no_update
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
CREATE TRIGGER trg_audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_immutable();
