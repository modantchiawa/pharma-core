-- =====================================================================
-- Row-Level Security : isolation stricte des tenants au niveau SGBD.
-- L'application se connecte (ou fait SET ROLE) en pharmacore_app, qui
-- n'est PAS propriétaire des tables : les politiques s'appliquent donc
-- toujours, même en cas de requête SQL native ou de bug applicatif.
-- =====================================================================

GRANT USAGE ON SCHEMA public TO pharmacore_app, pharmacore_network_reader;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    tenant, tenant_asset, pharmacy_config, terminal_heartbeat, tenant_sequence,
    app_role, role_permission, app_user, user_role,
    product, stock, stock_lot,
    sale, sale_line, sale_line_allocation, sale_payment,
    pharmacy_network, network_membership, inter_pharmacy_transfer,
    guard_schedule, conversation
    TO pharmacore_app;
GRANT SELECT ON permission TO pharmacore_app;
-- Tables append-only : aucune modification ni suppression possible
GRANT SELECT, INSERT ON stock_movement, chat_message, audit_event TO pharmacore_app;

-- ---------------------------------------------------------------------
-- Tables strictement tenant : tenant_id = tenant courant
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'tenant_asset', 'pharmacy_config', 'terminal_heartbeat', 'tenant_sequence',
        'app_role', 'role_permission', 'app_user', 'user_role',
        'stock', 'stock_lot', 'stock_movement',
        'sale', 'sale_line', 'sale_line_allocation', 'sale_payment']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- Tenant : lecture de sa propre fiche, écriture réservée à la plateforme
-- ---------------------------------------------------------------------
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_read ON tenant FOR SELECT TO pharmacore_app
    USING (id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_tenant_write ON tenant FOR ALL TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Catalogue national : lecture pour tous, écriture plateforme uniquement
-- ---------------------------------------------------------------------
ALTER TABLE product ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_product_read ON product FOR SELECT TO pharmacore_app USING (true);
CREATE POLICY p_product_insert ON product FOR INSERT TO pharmacore_app WITH CHECK (app_is_platform_admin());
CREATE POLICY p_product_update ON product FOR UPDATE TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Réseau : fonctions SECURITY DEFINER possédées par un rôle aux droits
-- colonne par colonne. Elles évitent la récursion des politiques et
-- garantissent qu'aucune donnée financière (prix, coûts, ventes) ne
-- franchit la frontière d'un tenant.
-- ---------------------------------------------------------------------
GRANT SELECT (network_id, tenant_id, share_stock, status) ON network_membership TO pharmacore_network_reader;
GRANT SELECT (id, name, region, coordinator_tenant_id, quantity_cap) ON pharmacy_network TO pharmacore_network_reader;
GRANT SELECT (id, slug, status) ON tenant TO pharmacore_network_reader;
GRANT SELECT (tenant_id, display_name, city, phone) ON pharmacy_config TO pharmacore_network_reader;
GRANT SELECT (id, tenant_id, product_id, reserved_quantity) ON stock TO pharmacore_network_reader;
GRANT SELECT (stock_id, quantity, expiry_date) ON stock_lot TO pharmacore_network_reader;

CREATE POLICY p_reader ON network_membership FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON pharmacy_network FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON tenant FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON pharmacy_config FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON stock FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON stock_lot FOR SELECT TO pharmacore_network_reader USING (true);

-- Transférer la propriété d'une fonction exige CREATE sur le schéma pour le nouveau
-- propriétaire : accordé le temps de la migration seulement (révoqué plus bas).
GRANT CREATE ON SCHEMA public TO pharmacore_network_reader;

CREATE FUNCTION app_member_networks() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT network_id
FROM network_membership
WHERE tenant_id = app_current_tenant()
  AND status = 'ACTIVE'
$$;
ALTER FUNCTION app_member_networks() OWNER TO pharmacore_network_reader;

CREATE FUNCTION resolve_active_tenant(p_slug text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT id FROM tenant WHERE slug = p_slug AND status = 'ACTIVE'
$$;
ALTER FUNCTION resolve_active_tenant(text) OWNER TO pharmacore_network_reader;

-- Disponibilité d'un produit chez les partenaires du réseau.
-- Règles :
--  * le demandeur est déduit du contexte de session (jamais d'un paramètre) ;
--  * réciprocité : seul un membre ACTIF qui partage son stock peut interroger ;
--  * seuls les partenaires ACTIFS partageant leur stock sont visibles ;
--  * quantité = lots non périmés - réservations, plafonnée par le réseau ;
--  * aucune information de prix, de coût, de lot ou de vente.
CREATE FUNCTION network_stock_availability(p_product_id uuid)
    RETURNS TABLE (
        tenant_id          uuid,
        pharmacy_name      text,
        city               text,
        phone              text,
        network_id         uuid,
        network_name       text,
        available_quantity integer,
        quantity_cap       integer
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
WITH partners AS (
    SELECT DISTINCT ON (m.tenant_id)
           m.tenant_id, n.id AS network_id, n.name AS network_name, n.quantity_cap
    FROM network_membership mine
    JOIN pharmacy_network n ON n.id = mine.network_id
    JOIN network_membership m ON m.network_id = mine.network_id
    WHERE mine.tenant_id = app_current_tenant()
      AND mine.status = 'ACTIVE'
      AND mine.share_stock
      AND m.tenant_id <> mine.tenant_id
      AND m.status = 'ACTIVE'
      AND m.share_stock
    ORDER BY m.tenant_id, n.quantity_cap
)
SELECT p.tenant_id,
       c.display_name::text,
       c.city::text,
       c.phone::text,
       p.network_id,
       p.network_name::text,
       LEAST(GREATEST(coalesce(l.qty, 0) - s.reserved_quantity, 0), p.quantity_cap)::integer,
       p.quantity_cap
FROM partners p
JOIN tenant t ON t.id = p.tenant_id AND t.status = 'ACTIVE'
JOIN pharmacy_config c ON c.tenant_id = p.tenant_id
JOIN stock s ON s.tenant_id = p.tenant_id AND s.product_id = p_product_id
LEFT JOIN LATERAL (
    SELECT sum(sl.quantity) AS qty
    FROM stock_lot sl
    WHERE sl.stock_id = s.id
      AND sl.quantity > 0
      AND sl.expiry_date > current_date
) l ON true
$$;
ALTER FUNCTION network_stock_availability(uuid) OWNER TO pharmacore_network_reader;

-- Annuaire des pharmacies des réseaux dont le tenant courant est membre actif.
CREATE FUNCTION network_directory()
    RETURNS TABLE (
        network_id    uuid,
        network_name  text,
        region        text,
        tenant_id     uuid,
        pharmacy_name text,
        city          text,
        phone         text,
        share_stock   boolean,
        coordinator   boolean
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT n.id, n.name::text, n.region::text, m.tenant_id, c.display_name::text, c.city::text, c.phone::text,
       m.share_stock, n.coordinator_tenant_id = m.tenant_id
FROM network_membership mine
JOIN pharmacy_network n ON n.id = mine.network_id
JOIN network_membership m ON m.network_id = mine.network_id AND m.status = 'ACTIVE'
JOIN tenant t ON t.id = m.tenant_id AND t.status = 'ACTIVE'
JOIN pharmacy_config c ON c.tenant_id = m.tenant_id
WHERE mine.tenant_id = app_current_tenant()
  AND mine.status = 'ACTIVE'
ORDER BY n.name, c.display_name
$$;
ALTER FUNCTION network_directory() OWNER TO pharmacore_network_reader;

REVOKE CREATE ON SCHEMA public FROM pharmacore_network_reader;

REVOKE ALL ON FUNCTION network_directory() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION network_directory() TO pharmacore_app;
REVOKE ALL ON FUNCTION app_member_networks() FROM PUBLIC;
REVOKE ALL ON FUNCTION resolve_active_tenant(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION network_stock_availability(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_member_networks() TO pharmacore_app;
GRANT EXECUTE ON FUNCTION resolve_active_tenant(text) TO pharmacore_app;
GRANT EXECUTE ON FUNCTION network_stock_availability(uuid) TO pharmacore_app;

-- ---------------------------------------------------------------------
-- Réseau : politiques côté application
-- ---------------------------------------------------------------------
ALTER TABLE pharmacy_network ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_network_read ON pharmacy_network FOR SELECT TO pharmacore_app
    USING (id IN (SELECT app_member_networks()) OR app_is_platform_admin());
CREATE POLICY p_network_insert ON pharmacy_network FOR INSERT TO pharmacore_app
    WITH CHECK (app_is_platform_admin());
CREATE POLICY p_network_update ON pharmacy_network FOR UPDATE TO pharmacore_app
    USING (coordinator_tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (coordinator_tenant_id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_network_delete ON pharmacy_network FOR DELETE TO pharmacore_app
    USING (app_is_platform_admin());

ALTER TABLE network_membership ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_membership_read ON network_membership FOR SELECT TO pharmacore_app
    USING (network_id IN (SELECT app_member_networks()) OR app_is_platform_admin());
CREATE POLICY p_membership_update ON network_membership FOR UPDATE TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_membership_insert ON network_membership FOR INSERT TO pharmacore_app
    WITH CHECK (app_is_platform_admin());
CREATE POLICY p_membership_delete ON network_membership FOR DELETE TO pharmacore_app
    USING (app_is_platform_admin());

ALTER TABLE inter_pharmacy_transfer ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_transfer_read ON inter_pharmacy_transfer FOR SELECT TO pharmacore_app
    USING (app_current_tenant() IN (requester_tenant_id, provider_tenant_id) OR app_is_platform_admin());
CREATE POLICY p_transfer_insert ON inter_pharmacy_transfer FOR INSERT TO pharmacore_app
    WITH CHECK (
        requester_tenant_id = app_current_tenant()
        AND network_id IN (SELECT app_member_networks())
        AND EXISTS (SELECT 1 FROM network_membership m
                    WHERE m.network_id = inter_pharmacy_transfer.network_id
                      AND m.tenant_id = inter_pharmacy_transfer.provider_tenant_id
                      AND m.status = 'ACTIVE'));
CREATE POLICY p_transfer_update ON inter_pharmacy_transfer FOR UPDATE TO pharmacore_app
    USING (app_current_tenant() IN (requester_tenant_id, provider_tenant_id) OR app_is_platform_admin())
    WITH CHECK (app_current_tenant() IN (requester_tenant_id, provider_tenant_id) OR app_is_platform_admin());

ALTER TABLE guard_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_guard_read ON guard_schedule FOR SELECT TO pharmacore_app
    USING (network_id IN (SELECT app_member_networks()) OR app_is_platform_admin());
CREATE POLICY p_guard_write ON guard_schedule FOR ALL TO pharmacore_app
    USING (network_id IN (SELECT id FROM pharmacy_network WHERE coordinator_tenant_id = app_current_tenant())
           OR app_is_platform_admin())
    WITH CHECK (
        (network_id IN (SELECT id FROM pharmacy_network WHERE coordinator_tenant_id = app_current_tenant())
         OR app_is_platform_admin())
        AND EXISTS (SELECT 1 FROM network_membership m
                    WHERE m.network_id = guard_schedule.network_id
                      AND m.tenant_id = guard_schedule.pharmacy_tenant_id
                      AND m.status = 'ACTIVE'));

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_conversation_access ON conversation FOR ALL TO pharmacore_app
    USING ((network_id IN (SELECT app_member_networks())
            AND (kind = 'NETWORK_CHANNEL' OR app_current_tenant() IN (participant_a_tenant, participant_b_tenant)))
           OR app_is_platform_admin())
    WITH CHECK (network_id IN (SELECT app_member_networks())
                AND (kind = 'NETWORK_CHANNEL' OR app_current_tenant() IN (participant_a_tenant, participant_b_tenant)));

ALTER TABLE chat_message ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_chat_read ON chat_message FOR SELECT TO pharmacore_app
    USING (conversation_id IN (SELECT id FROM conversation));
CREATE POLICY p_chat_insert ON chat_message FOR INSERT TO pharmacore_app
    WITH CHECK (sender_tenant_id = app_current_tenant() AND conversation_id IN (SELECT id FROM conversation));

-- ---------------------------------------------------------------------
-- Audit : lecture/écriture limitées au tenant ; la plateforme écrit sous
-- l'identifiant nul.
-- ---------------------------------------------------------------------
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_audit_read ON audit_event FOR SELECT TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin());
CREATE POLICY p_audit_insert ON audit_event FOR INSERT TO pharmacore_app
    WITH CHECK (tenant_id = app_current_tenant()
                OR (app_is_platform_admin() AND app_current_tenant() IS NULL
                    AND tenant_id = '00000000-0000-0000-0000-000000000000'));
