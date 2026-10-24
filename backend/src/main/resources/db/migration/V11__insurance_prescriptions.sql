-- =====================================================================
-- V11 : tiers payant (assureurs, mutuelles, polices, bordereaux) et
--       ordonnances (registre des produits listés, renouvellements).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Assureurs et polices
-- ---------------------------------------------------------------------
CREATE TABLE insurer (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    code                  varchar(20)  NOT NULL,
    name                  varchar(150) NOT NULL,
    kind                  varchar(12)  NOT NULL CHECK (kind IN ('MUTUELLE', 'ASSURANCE', 'ETAT', 'ENTREPRISE')),
    default_coverage_rate smallint     NOT NULL CHECK (default_coverage_rate BETWEEN 0 AND 100),
    contact_name          varchar(150),
    phone                 varchar(30),
    email                 varchar(200),
    address               varchar(300),
    payment_terms_days    smallint     NOT NULL DEFAULT 30 CHECK (payment_terms_days >= 0),
    -- Sous-compte SYSCOHADA 4112xxx dédié : encours lisible par assureur dans la balance
    account_code          varchar(10)  NOT NULL,
    active                boolean      NOT NULL DEFAULT true,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    version               bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, account_code)
);
CREATE INDEX ix_insurer_tenant_name ON insurer (tenant_id, name);

CREATE TABLE insurance_policy (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    insurer_id            uuid         NOT NULL REFERENCES insurer (id) DEFERRABLE INITIALLY IMMEDIATE,
    customer_id           uuid         NOT NULL REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    policy_number         varchar(60)  NOT NULL,
    beneficiary_name      varchar(150) NOT NULL,
    coverage_rate         smallint     NOT NULL CHECK (coverage_rate BETWEEN 0 AND 100),
    monthly_ceiling_cents bigint CHECK (monthly_ceiling_cents > 0),
    valid_from            date         NOT NULL,
    valid_until           date,
    active                boolean      NOT NULL DEFAULT true,
    version               bigint       NOT NULL DEFAULT 0,
    CHECK (valid_until IS NULL OR valid_until >= valid_from),
    UNIQUE (tenant_id, insurer_id, policy_number)
);
CREATE INDEX ix_insurance_policy_customer ON insurance_policy (tenant_id, customer_id) WHERE active;
CREATE INDEX ix_insurance_policy_insurer ON insurance_policy (tenant_id, insurer_id);

-- ---------------------------------------------------------------------
-- Bordereaux de facturation aux assureurs
-- ---------------------------------------------------------------------
CREATE TABLE insurer_statement (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    insurer_id        uuid        NOT NULL REFERENCES insurer (id) DEFERRABLE INITIALLY IMMEDIATE,
    number            varchar(20) NOT NULL,
    period_from       date        NOT NULL,
    period_to         date        NOT NULL,
    status            varchar(10) NOT NULL CHECK (status IN ('DRAFT', 'ISSUED', 'PAID')),
    total_cents       bigint      NOT NULL CHECK (total_cents >= 0),
    paid_cents        bigint      NOT NULL DEFAULT 0 CHECK (paid_cents >= 0),
    line_count        integer     NOT NULL CHECK (line_count >= 0),
    created_by        uuid        NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    created_at        timestamptz NOT NULL,
    issued_by         uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    issued_at         timestamptz,
    paid_at           timestamptz,
    payment_method    varchar(20),
    payment_reference varchar(64),
    version           bigint      NOT NULL DEFAULT 0,
    CHECK (period_to >= period_from),
    CHECK (paid_cents <= total_cents),
    UNIQUE (tenant_id, number)
);
CREATE INDEX ix_insurer_statement_insurer ON insurer_statement (tenant_id, insurer_id, period_from DESC);
CREATE INDEX ix_insurer_statement_status ON insurer_statement (tenant_id, status, created_at);

CREATE TABLE insurer_statement_line (
    id              uuid PRIMARY KEY,
    tenant_id       uuid   NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    statement_id    uuid   NOT NULL REFERENCES insurer_statement (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_id         uuid   NOT NULL REFERENCES sale (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_payment_id uuid   NOT NULL REFERENCES sale_payment (id) DEFERRABLE INITIALLY IMMEDIATE,
    amount_cents    bigint NOT NULL CHECK (amount_cents > 0),
    UNIQUE (sale_payment_id)
);
CREATE INDEX ix_insurer_statement_line_statement ON insurer_statement_line (statement_id);

-- Un paiement tiers payant porte la police couvrante et, une fois facturé, son bordereau
ALTER TABLE sale_payment ADD COLUMN insurer_id uuid REFERENCES insurer (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE sale_payment ADD COLUMN policy_id uuid REFERENCES insurance_policy (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE sale_payment ADD COLUMN statement_id uuid REFERENCES insurer_statement (id) DEFERRABLE INITIALLY IMMEDIATE;
CREATE INDEX ix_sale_payment_insurer_unbilled ON sale_payment (tenant_id, insurer_id)
    WHERE insurer_id IS NOT NULL AND statement_id IS NULL;
CREATE INDEX ix_sale_payment_policy ON sale_payment (tenant_id, policy_id) WHERE policy_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Ordonnances renouvelables
-- ---------------------------------------------------------------------
CREATE TABLE prescription (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    customer_id           uuid REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    prescriber_name       varchar(150) NOT NULL,
    prescriber_identifier varchar(30),
    patient_reference     varchar(64),
    issued_on             date         NOT NULL,
    valid_until           date         NOT NULL,
    -- Nombre total de délivrances autorisées (la première comprise) et déjà effectuées
    renewals_allowed      smallint     NOT NULL CHECK (renewals_allowed BETWEEN 1 AND 24),
    renewals_used         smallint     NOT NULL DEFAULT 0 CHECK (renewals_used >= 0),
    notes                 varchar(500),
    status                varchar(10)  NOT NULL CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'CANCELLED')),
    created_by            uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    created_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL DEFAULT 0,
    CHECK (valid_until >= issued_on),
    CHECK (renewals_used <= renewals_allowed)
);
CREATE INDEX ix_prescription_customer ON prescription (tenant_id, customer_id, status);
CREATE INDEX ix_prescription_status ON prescription (tenant_id, status, valid_until);

CREATE TABLE prescription_line (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    prescription_id uuid         NOT NULL REFERENCES prescription (id) DEFERRABLE INITIALLY IMMEDIATE,
    line_no         smallint     NOT NULL,
    product_id      uuid         NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    quantity        integer      NOT NULL CHECK (quantity > 0),
    posology        varchar(200),
    UNIQUE (prescription_id, line_no)
);

ALTER TABLE sale ADD COLUMN prescription_id uuid REFERENCES prescription (id) DEFERRABLE INITIALLY IMMEDIATE;
CREATE INDEX ix_sale_prescription ON sale (tenant_id, prescription_id) WHERE prescription_id IS NOT NULL;

-- Registre des produits listés : lignes de vente par produit
CREATE INDEX ix_sale_line_tenant_product ON sale_line (tenant_id, product_id);

-- ---------------------------------------------------------------------
-- Droits, RLS, synchronisation
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON
    insurer, insurance_policy, insurer_statement, insurer_statement_line, prescription, prescription_line
    TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['insurer', 'insurance_policy', 'insurer_statement', 'insurer_statement_line',
                             'prescription', 'prescription_line']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- L'amorçage charge table par table : les parents (assureur, police, ordonnance, bordereau)
-- passent avant sale (36) et sale_payment (39) qui les référencent.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('insurer',                 'HUB', 'TENANT', NULL, 30),
    ('insurance_policy',        'HUB', 'TENANT', NULL, 31),
    ('prescription',            'HUB', 'TENANT', NULL, 31),
    ('prescription_line',       'HUB', 'TENANT', NULL, 32),
    ('insurer_statement',       'HUB', 'TENANT', NULL, 35),
    ('insurer_statement_line',  'HUB', 'TENANT', NULL, 40);

SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('insurer', 'insurance_policy', 'prescription', 'prescription_line',
                     'insurer_statement', 'insurer_statement_line');
