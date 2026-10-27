-- =====================================================================
-- V18 : miniatures des produits et verrouillage des postes inactifs.
--  * Chaque pharmacie peut photographier ses produits (boîte) : une image
--    réduite (256 px, PNG, JPEG ou WebP) par produit, affichée en miniature
--    partout où l'on choisit un produit. Le contenu est répliqué comme le
--    logo (bytea sérialisé en hexadécimal par to_jsonb, relu par
--    jsonb_populate_record).
--  * Une suppression garde la ligne, vidée de son contenu : elle se
--    réplique comme une mise à jour et réémet le produit vers le cache
--    hors ligne des caisses (updated_at dans le curseur du flux POS).
--  * Délais d'inactivité de la pharmacie : verrouillage de l'écran puis
--    déconnexion. Colonnes facultatives (NULL = valeur par défaut, 5 et
--    30 minutes) : un hub d'une version antérieure réplique sa
--    configuration sans ces colonnes pendant une mise à jour.
-- =====================================================================
CREATE TABLE product_image (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id   uuid        NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    content      bytea,
    content_type varchar(20) CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    size_bytes   integer     CHECK (size_bytes BETWEEN 1 AND 262144),
    sha256       varchar(64),
    updated_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, product_id),
    CHECK ((content IS NULL) = (sha256 IS NULL)
        AND (content IS NULL) = (content_type IS NULL)
        AND (content IS NULL) = (size_bytes IS NULL))
);

GRANT SELECT, INSERT, UPDATE, DELETE ON product_image TO pharmacore_app;

ALTER TABLE product_image ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON product_image TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('product_image', 'HUB', 'TENANT', NULL, 29);
SELECT sync_track('product_image');

-- Délais d'inactivité en minutes ; verrouillage 0 = jamais
ALTER TABLE pharmacy_config ADD COLUMN idle_lock_minutes smallint
    CHECK (idle_lock_minutes BETWEEN 0 AND 120);
ALTER TABLE pharmacy_config ADD COLUMN idle_logout_minutes smallint
    CHECK (idle_logout_minutes BETWEEN 5 AND 720);
