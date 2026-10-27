-- =====================================================================
-- V14 : emplacements de rangement de l'officine (rayons, tiroirs,
-- réfrigérateur, coffre des stupéfiants, réserve, comptoir).
--  * Chaque pharmacie organise ses emplacements (code court unique,
--    libellé, nature, ordre d'affichage).
--  * Chaque produit en stock est rangé à un emplacement, avec un détail
--    libre (« case 12 », « bac 4 »).
--  * L'ancien emplacement en texte libre (stock.location) est repris en
--    emplacements structurés puis supprimé.
-- =====================================================================
INSERT INTO permission (code, category, description) VALUES
    ('STOCK_LOCATION_MANAGE', 'STOCK', 'Organiser les emplacements de rangement et y affecter les produits');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, p.code, r.tenant_id
FROM app_role r
JOIN permission p ON p.code = 'STOCK_LOCATION_MANAGE'
WHERE r.code IN ('TITULAIRE', 'ADJOINT', 'GESTIONNAIRE_STOCK');

CREATE TABLE storage_location (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- Code court affiché au comptoir et sur les étiquettes (A3, FRIGO, COFFRE)
    code       varchar(16) NOT NULL,
    name       varchar(80) NOT NULL,
    kind       varchar(10) NOT NULL CHECK (kind IN ('SHELF', 'DRAWER', 'FRIDGE', 'SAFE', 'RESERVE', 'COUNTER', 'OTHER')),
    sort_order integer     NOT NULL DEFAULT 0,
    active     boolean     NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);
CREATE INDEX ix_storage_location_order ON storage_location (tenant_id, active, sort_order, code);

GRANT SELECT, INSERT, UPDATE, DELETE ON storage_location TO pharmacore_app;

ALTER TABLE storage_location ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON storage_location TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

ALTER TABLE stock ADD COLUMN location_id uuid REFERENCES storage_location (id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE stock ADD COLUMN location_detail varchar(40);
-- Produits d'un emplacement (écran Emplacements, filtre de l'écran Stocks, inventaire par emplacement)
CREATE INDEX ix_stock_location ON stock (tenant_id, location_id);

-- Suivie avant la reprise ci-dessous : les emplacements repris partent dans
-- le journal avec les stocks qui les référencent.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('storage_location', 'HUB', 'TENANT', NULL, 29);
SELECT sync_track('storage_location');

-- ---------------------------------------------------------------------
-- Reprise de l'emplacement en texte libre. Le code est dérivé du texte
-- (majuscules sans accents, 16 caractères) et l'identifiant du couple
-- (pharmacie, code) : le hub et le cloud, qui jouent tous deux cette
-- migration, produisent les mêmes lignes et la réplication les fusionne.
-- ---------------------------------------------------------------------
CREATE TEMPORARY TABLE legacy_location AS
SELECT s.id AS stock_id, s.tenant_id, btrim(s.location) AS label,
       coalesce(nullif(btrim(left(btrim(regexp_replace(regexp_replace(
           translate(upper(btrim(s.location)), 'ÀÂÄÁÃÇÉÈÊËÍÌÎÏÑÓÒÔÖÕÚÙÛÜÝŸ', 'AAAAACEEEEIIIINOOOOOUUUUYY'),
           '[^A-Z0-9 ._/-]', ' ', 'g'), '\s+', ' ', 'g')), 16)), ''),
           'E-' || upper(left(md5(btrim(s.location)), 6))) AS code
FROM stock s
WHERE s.location IS NOT NULL AND btrim(s.location) <> '';

INSERT INTO storage_location (id, tenant_id, code, name, kind, sort_order)
SELECT md5(g.tenant_id::text || ':' || g.code)::uuid, g.tenant_id, g.code, left(g.label, 80),
       CASE
           WHEN g.label ILIKE '%frigo%' OR g.label ILIKE '%frig%rat%' THEN 'FRIDGE'
           WHEN g.label ILIKE '%coffre%' OR g.label ILIKE '%stup%' THEN 'SAFE'
           WHEN g.label ILIKE '%tiroir%' THEN 'DRAWER'
           WHEN g.label ILIKE '%r_serve%' THEN 'RESERVE'
           WHEN g.label ILIKE '%comptoir%' THEN 'COUNTER'
           WHEN g.label ILIKE '%rayon%' OR g.label ILIKE '%_tag_re%' OR g.label ILIKE '%lin_aire%' THEN 'SHELF'
           ELSE 'OTHER'
       END,
       10 * row_number() OVER (PARTITION BY g.tenant_id ORDER BY g.code)
FROM (SELECT tenant_id, code, min(label) AS label FROM legacy_location GROUP BY tenant_id, code) g;

UPDATE stock s
SET location_id = md5(l.tenant_id::text || ':' || l.code)::uuid
FROM legacy_location l
WHERE l.stock_id = s.id;

DROP TABLE legacy_location;
ALTER TABLE stock DROP COLUMN location;
