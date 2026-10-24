-- =====================================================================
-- V12 : importation de données (migration depuis un autre logiciel,
-- référentiel de médicaments) et impression des tickets.
-- =====================================================================
INSERT INTO permission (code, category, description) VALUES
    ('DATA_IMPORT',    'ADMIN', 'Importer produits, stocks, clients et fournisseurs depuis un fichier (CSV, Excel)'),
    ('PRINTER_MANAGE', 'ADMIN', 'Configurer les imprimantes de tickets et de factures des postes');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('DATA_IMPORT', 'PRINTER_MANAGE')
WHERE r.code IN ('TITULAIRE', 'ADJOINT');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('DATA_IMPORT')
WHERE r.code = 'GESTIONNAIRE_STOCK';

-- ---------------------------------------------------------------------
-- Journal des importations (traçabilité des migrations de données)
-- ---------------------------------------------------------------------
CREATE TABLE import_job (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL,
    kind          varchar(20)  NOT NULL CHECK (kind IN ('CATALOG', 'PRODUCTS', 'STOCK', 'CUSTOMERS', 'SUPPLIERS')),
    file_name     varchar(255) NOT NULL,
    file_format   varchar(10)  NOT NULL,
    dry_run       boolean      NOT NULL,
    status        varchar(10)  NOT NULL CHECK (status IN ('DONE', 'FAILED')),
    total_rows    integer      NOT NULL,
    created_rows  integer      NOT NULL,
    updated_rows  integer      NOT NULL,
    rejected_rows integer      NOT NULL,
    errors        jsonb        NOT NULL DEFAULT '[]'::jsonb,
    mapping       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    started_by    varchar(255) NOT NULL,
    started_at    timestamptz  NOT NULL,
    finished_at   timestamptz  NOT NULL
);
CREATE INDEX ix_import_job_tenant ON import_job (tenant_id, started_at DESC);

GRANT SELECT, INSERT ON import_job TO pharmacore_app;
ALTER TABLE import_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON import_job TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

-- Les importations d'une officine sont faites sur son serveur et remontent au cloud.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order)
VALUES ('import_job', 'HUB', 'TENANT', NULL, 70);
SELECT sync_track('import_job');
