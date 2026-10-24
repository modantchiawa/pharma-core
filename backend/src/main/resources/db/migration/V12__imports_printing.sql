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
