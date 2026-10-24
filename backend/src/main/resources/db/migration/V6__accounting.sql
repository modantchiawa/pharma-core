-- =====================================================================
-- V6 : comptabilité SYSCOHADA révisé (espace OHADA : Niger, Sénégal...)
--      Plan de comptes minimal par pharmacie, écritures en partie
--      double générées par les opérations métier, périodes mensuelles.
-- =====================================================================

CREATE TABLE account (
    id        uuid PRIMARY KEY,
    tenant_id uuid         NOT NULL REFERENCES tenant (id),
    code      varchar(10)  NOT NULL CHECK (code ~ '^[1-9][0-9]{1,9}$'),
    label     varchar(150) NOT NULL,
    kind      varchar(10)  NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    system    boolean      NOT NULL DEFAULT false,
    active    boolean      NOT NULL DEFAULT true,
    UNIQUE (tenant_id, code)
);

CREATE TABLE accounting_period (
    id        uuid PRIMARY KEY,
    tenant_id uuid        NOT NULL REFERENCES tenant (id),
    year      smallint    NOT NULL,
    month     smallint    NOT NULL CHECK (month BETWEEN 1 AND 12),
    status    varchar(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at timestamptz,
    closed_by uuid,
    -- Valeur du stock au coût moyen pondéré au dernier jour du mois clôturé
    closing_stock_cents bigint,
    UNIQUE (tenant_id, year, month)
);

CREATE TABLE journal_entry (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    -- VT ventes, AC achats, CA caisse, BQ banque, OD opérations diverses
    journal        varchar(2)   NOT NULL CHECK (journal IN ('VT', 'AC', 'CA', 'BQ', 'OD')),
    entry_no       bigint       NOT NULL,
    entry_date     date         NOT NULL,
    label          varchar(255) NOT NULL,
    reference_type varchar(40),
    reference_id   uuid,
    reversal_of    uuid REFERENCES journal_entry (id),
    posted_by      varchar(255) NOT NULL,
    posted_at      timestamptz  NOT NULL,
    UNIQUE (tenant_id, entry_no)
);
CREATE INDEX ix_journal_entry_date ON journal_entry (tenant_id, entry_date, entry_no);
CREATE INDEX ix_journal_entry_reference ON journal_entry (tenant_id, reference_type, reference_id);

CREATE TABLE journal_line (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    entry_id     uuid         NOT NULL REFERENCES journal_entry (id),
    line_no      smallint     NOT NULL,
    account_code varchar(10)  NOT NULL,
    debit_cents  bigint       NOT NULL DEFAULT 0 CHECK (debit_cents >= 0),
    credit_cents bigint       NOT NULL DEFAULT 0 CHECK (credit_cents >= 0),
    label        varchar(255),
    CHECK ((debit_cents > 0 AND credit_cents = 0) OR (credit_cents > 0 AND debit_cents = 0)),
    UNIQUE (entry_id, line_no)
);
CREATE INDEX ix_journal_line_account ON journal_line (tenant_id, account_code);

-- Partie double garantie par le SGBD : à la validation de la transaction,
-- chaque écriture touchée doit être équilibrée.
CREATE FUNCTION journal_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_entry  uuid := coalesce(NEW.entry_id, OLD.entry_id);
    v_debit  bigint;
    v_credit bigint;
BEGIN
    SELECT coalesce(sum(debit_cents), 0), coalesce(sum(credit_cents), 0)
    INTO v_debit, v_credit
    FROM journal_line WHERE entry_id = v_entry;
    IF v_debit <> v_credit THEN
        RAISE EXCEPTION 'Journal entry % is not balanced (debit %, credit %)', v_entry, v_debit, v_credit
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced
    AFTER INSERT OR UPDATE OR DELETE ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

-- Les écritures sont immuables : on les contre-passe, on ne les modifie pas.
GRANT SELECT, INSERT, UPDATE, DELETE ON account, accounting_period TO pharmacore_app;
GRANT SELECT, INSERT ON journal_entry, journal_line TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['account', 'accounting_period', 'journal_entry', 'journal_line']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Plan de comptes minimal SYSCOHADA, créé pour chaque pharmacie.
CREATE FUNCTION accounting_seed_chart(p_tenant uuid) RETURNS integer
    LANGUAGE plpgsql AS
$$
DECLARE
    inserted integer;
BEGIN
    INSERT INTO account (id, tenant_id, code, label, kind, system)
    SELECT gen_random_uuid(), p_tenant, c.code, c.label, c.kind, true
    FROM (VALUES
        ('101',  'Capital',                                   'EQUITY'),
        ('130',  'Résultat de l''exercice',                   'EQUITY'),
        ('311',  'Marchandises (médicaments et parapharmacie)', 'ASSET'),
        ('401',  'Fournisseurs',                              'LIABILITY'),
        ('4111', 'Clients',                                   'ASSET'),
        ('4112', 'Clients - tiers payant (mutuelles, assurances)', 'ASSET'),
        ('4431', 'TVA facturée sur ventes',                   'LIABILITY'),
        ('4452', 'TVA récupérable sur achats',                'ASSET'),
        ('521',  'Banque',                                    'ASSET'),
        ('5221', 'Mobile money',                              'ASSET'),
        ('571',  'Caisse',                                    'ASSET'),
        ('5711', 'Chèques et cartes à encaisser',             'ASSET'),
        ('601',  'Achats de marchandises',                    'EXPENSE'),
        ('6031', 'Variation des stocks de marchandises',      'EXPENSE'),
        ('6052', 'Électricité et eau',                        'EXPENSE'),
        ('6181', 'Transport et livraison',                    'EXPENSE'),
        ('622',  'Locations',                                 'EXPENSE'),
        ('6281', 'Téléphone et Internet',                     'EXPENSE'),
        ('631',  'Frais bancaires et mobile money',           'EXPENSE'),
        ('658',  'Charges diverses (écarts de caisse)',       'EXPENSE'),
        ('661',  'Rémunérations du personnel',                'EXPENSE'),
        ('701',  'Ventes de marchandises',                    'REVENUE'),
        ('7011', 'Ventes de médicaments',                     'REVENUE'),
        ('7012', 'Ventes de parapharmacie',                   'REVENUE'),
        ('758',  'Produits divers (excédents de caisse)',     'REVENUE')
    ) AS c(code, label, kind)
    ON CONFLICT (tenant_id, code) DO NOTHING;
    GET DIAGNOSTICS inserted = ROW_COUNT;
    RETURN inserted;
END
$$;

-- Pharmacies déjà provisionnées
SELECT accounting_seed_chart(id) FROM tenant;
