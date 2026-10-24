-- =====================================================================
-- V8 : permissions de la version 3 (annuaire public, fiabilité stock et
-- caisse, tiers payant, ordonnancier, tableau de bord).
-- Les tables de chaque module arrivent dans V9 (annuaire public et
-- gardes partagées), V10 (inventaires, caisse) et V11 (assureurs,
-- ordonnances).
-- =====================================================================
INSERT INTO permission (code, category, description) VALUES
    ('DIRECTORY_SHARING_MANAGE',   'NETWORK',   'Publier la pharmacie, ses disponibilités et ses gardes dans l''annuaire public'),
    ('STOCK_COUNT',                'STOCK',     'Réaliser un inventaire physique (comptage à l''aveugle)'),
    ('STOCK_COUNT_VALIDATE',       'STOCK',     'Valider un inventaire et régulariser les écarts'),
    ('CASH_APPROVE',               'SALES',     'Approuver annulations, remboursements et écarts de caisse au-dessus du seuil'),
    ('PAYMENT_RECONCILE',          'SALES',     'Rapprocher les paiements mobile money et carte avec les relevés des opérateurs'),
    ('INSURANCE_VIEW',             'INSURANCE', 'Consulter assureurs, polices et bordereaux de tiers payant'),
    ('INSURANCE_MANAGE',           'INSURANCE', 'Gérer assureurs et polices, émettre et régler les bordereaux'),
    ('PRESCRIPTION_REGISTER_VIEW', 'SALES',     'Consulter et exporter l''ordonnancier et le registre des stupéfiants'),
    ('DASHBOARD_VIEW',             'ADMIN',     'Consulter le tableau de bord de pilotage');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('DIRECTORY_SHARING_MANAGE', 'STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'CASH_APPROVE',
                                'PAYMENT_RECONCILE', 'INSURANCE_VIEW', 'INSURANCE_MANAGE',
                                'PRESCRIPTION_REGISTER_VIEW', 'DASHBOARD_VIEW')
WHERE r.code IN ('TITULAIRE', 'ADJOINT');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('STOCK_COUNT', 'INSURANCE_VIEW')
WHERE r.code = 'PREPARATEUR';

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('INSURANCE_VIEW')
WHERE r.code = 'CAISSIER';

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code IN ('STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'DASHBOARD_VIEW')
WHERE r.code = 'GESTIONNAIRE_STOCK';
