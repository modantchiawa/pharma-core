-- =====================================================================
-- V9 : annuaire public des officines, disponibilité d'un produit
-- (grand public et entre partenaires), gardes partagées.
--
-- Principe : les fonctions publiques sont SECURITY DEFINER, possédées par
-- pharmacore_network_reader dont les droits sont accordés colonne par
-- colonne. Elles ne renvoient jamais une quantité, un prix ou un coût au
-- grand public : seulement un niveau (IN_STOCK / LOW). L'officine choisit
-- ce qu'elle publie (fiche, disponibilités) ; le coordinateur d'un réseau
-- choisit de publier son planning de garde.
-- =====================================================================

ALTER TABLE pharmacy_config
    ADD COLUMN public_listing      boolean      NOT NULL DEFAULT false,
    ADD COLUMN public_availability boolean      NOT NULL DEFAULT false,
    ADD COLUMN latitude            numeric(9, 6) CHECK (latitude BETWEEN -90 AND 90),
    ADD COLUMN longitude           numeric(9, 6) CHECK (longitude BETWEEN -180 AND 180),
    ADD COLUMN opening_hours       varchar(300),
    ADD COLUMN whatsapp            varchar(30);

ALTER TABLE pharmacy_network
    ADD COLUMN publish_guards boolean NOT NULL DEFAULT false;

-- Recherche publique par ville : insensible à la casse
CREATE INDEX ix_pharmacy_config_public_city ON pharmacy_config (lower(city)) WHERE public_listing;

-- ---------------------------------------------------------------------
-- Droits du rôle lecteur (colonne par colonne, jamais de données financières)
-- ---------------------------------------------------------------------
GRANT SELECT (public_listing, public_availability, latitude, longitude, opening_hours, whatsapp,
              address_line1, address_line2, postal_code)
    ON pharmacy_config TO pharmacore_network_reader;
GRANT SELECT (publish_guards) ON pharmacy_network TO pharmacore_network_reader;
GRANT SELECT (time_zone) ON tenant TO pharmacore_network_reader;
GRANT SELECT (reorder_threshold) ON stock TO pharmacore_network_reader;
GRANT SELECT (id, cip13, designation, dci, pharmaceutical_form, dosage, active) ON product TO pharmacore_network_reader;
GRANT SELECT (id, network_id, pharmacy_tenant_id, guard_type, starts_at, ends_at) ON guard_schedule
    TO pharmacore_network_reader;

CREATE POLICY p_reader ON product FOR SELECT TO pharmacore_network_reader USING (true);
CREATE POLICY p_reader ON guard_schedule FOR SELECT TO pharmacore_network_reader USING (true);

GRANT CREATE ON SCHEMA public TO pharmacore_network_reader;

-- ---------------------------------------------------------------------
-- Officines publiées (fiche publique)
-- ---------------------------------------------------------------------
CREATE FUNCTION public_pharmacies(p_city text)
    RETURNS TABLE (
        tenant_id     uuid,
        slug          text,
        pharmacy_name text,
        address       text,
        city          text,
        phone         text,
        whatsapp      text,
        opening_hours text,
        latitude      numeric,
        longitude     numeric,
        shares_availability boolean
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT t.id, t.slug::text, c.display_name::text,
       nullif(concat_ws(', ', c.address_line1, c.address_line2, c.postal_code), '')::text,
       c.city::text, c.phone::text, c.whatsapp::text, c.opening_hours::text, c.latitude, c.longitude,
       c.public_availability
FROM pharmacy_config c
JOIN tenant t ON t.id = c.tenant_id AND t.status = 'ACTIVE'
WHERE c.public_listing
  AND (p_city IS NULL OR lower(c.city) = lower(p_city))
ORDER BY c.display_name
$$;
ALTER FUNCTION public_pharmacies(text) OWNER TO pharmacore_network_reader;

-- ---------------------------------------------------------------------
-- Disponibilité publique : au plus 20 produits correspondant à la recherche,
-- croisés avec les officines qui publient leurs disponibilités. Niveau
-- seulement : IN_STOCK (plus de 3 unités et au-dessus du seuil de
-- réapprovisionnement), LOW sinon. Les lots périmés et les réservations
-- sont exclus ; une officine sans unité disponible n'apparaît pas.
-- ---------------------------------------------------------------------
CREATE FUNCTION public_availability(p_query text, p_city text)
    RETURNS TABLE (
        product_id    uuid,
        cip13         text,
        designation   text,
        dci           text,
        tenant_id     uuid,
        pharmacy_name text,
        address       text,
        city          text,
        phone         text,
        whatsapp      text,
        latitude      numeric,
        longitude     numeric,
        level         text
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
WITH products AS (
    SELECT p.id, p.cip13, p.designation, p.dci
    FROM product p
    WHERE p.active
      AND length(trim(p_query)) >= 3
      AND (p.cip13 = trim(p_query)
           OR p.designation ILIKE '%' || trim(p_query) || '%'
           OR p.dci ILIKE '%' || trim(p_query) || '%')
    ORDER BY similarity(p.designation, trim(p_query)) DESC, p.designation
    LIMIT 20
),
pharmacies AS (
    SELECT c.tenant_id, c.display_name, c.address_line1, c.address_line2, c.postal_code, c.city, c.phone,
           c.whatsapp, c.latitude, c.longitude
    FROM pharmacy_config c
    JOIN tenant t ON t.id = c.tenant_id AND t.status = 'ACTIVE'
    WHERE c.public_listing
      AND c.public_availability
      AND (p_city IS NULL OR lower(c.city) = lower(p_city))
)
SELECT pr.id, pr.cip13::text, pr.designation::text, pr.dci::text,
       ph.tenant_id, ph.display_name::text,
       nullif(concat_ws(', ', ph.address_line1, ph.address_line2, ph.postal_code), '')::text,
       ph.city::text, ph.phone::text, ph.whatsapp::text, ph.latitude, ph.longitude,
       CASE WHEN a.available > 3 AND a.available > s.reorder_threshold THEN 'IN_STOCK' ELSE 'LOW' END::text
FROM products pr
JOIN stock s ON s.product_id = pr.id
JOIN pharmacies ph ON ph.tenant_id = s.tenant_id
JOIN LATERAL (
    SELECT GREATEST(coalesce(sum(sl.quantity), 0) - s.reserved_quantity, 0)::integer AS available
    FROM stock_lot sl
    WHERE sl.stock_id = s.id
      AND sl.quantity > 0
      AND sl.expiry_date > current_date
) a ON true
WHERE a.available > 0
ORDER BY pr.designation, ph.display_name
$$;
ALTER FUNCTION public_availability(text, text) OWNER TO pharmacore_network_reader;

-- ---------------------------------------------------------------------
-- Ce qu'une officine partenaire a parmi les produits recherchés : même
-- règle que network_stock_availability (réseau commun, partage activé
-- des deux côtés, quantité plafonnée par le réseau).
-- ---------------------------------------------------------------------
CREATE FUNCTION partner_stock_availability(p_partner uuid, p_query text)
    RETURNS TABLE (
        product_id         uuid,
        cip13              text,
        designation        text,
        dci                text,
        network_id         uuid,
        network_name       text,
        available_quantity integer,
        quantity_cap       integer
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
WITH link AS (
    SELECT n.id AS network_id, n.name AS network_name, n.quantity_cap
    FROM network_membership mine
    JOIN pharmacy_network n ON n.id = mine.network_id
    JOIN network_membership m ON m.network_id = mine.network_id
    JOIN tenant t ON t.id = m.tenant_id AND t.status = 'ACTIVE'
    WHERE mine.tenant_id = app_current_tenant()
      AND mine.status = 'ACTIVE'
      AND mine.share_stock
      AND m.tenant_id = p_partner
      AND m.tenant_id <> mine.tenant_id
      AND m.status = 'ACTIVE'
      AND m.share_stock
    ORDER BY n.quantity_cap
    LIMIT 1
),
products AS (
    SELECT p.id, p.cip13, p.designation, p.dci
    FROM product p
    WHERE p.active
      AND length(trim(p_query)) >= 2
      AND (p.cip13 = trim(p_query)
           OR p.designation ILIKE '%' || trim(p_query) || '%'
           OR p.dci ILIKE '%' || trim(p_query) || '%')
    ORDER BY similarity(p.designation, trim(p_query)) DESC, p.designation
    LIMIT 20
)
SELECT pr.id, pr.cip13::text, pr.designation::text, pr.dci::text, l.network_id, l.network_name::text,
       LEAST(GREATEST(coalesce(a.qty, 0) - s.reserved_quantity, 0), l.quantity_cap)::integer,
       l.quantity_cap
FROM link l
CROSS JOIN products pr
JOIN stock s ON s.tenant_id = p_partner AND s.product_id = pr.id
LEFT JOIN LATERAL (
    SELECT sum(sl.quantity) AS qty
    FROM stock_lot sl
    WHERE sl.stock_id = s.id
      AND sl.quantity > 0
      AND sl.expiry_date > current_date
) a ON true
ORDER BY pr.designation
$$;
ALTER FUNCTION partner_stock_availability(uuid, text) OWNER TO pharmacore_network_reader;

-- ---------------------------------------------------------------------
-- Gardes publiées : créneaux chevauchant [p_from, p_to) des réseaux dont le
-- coordinateur a activé la publication.
-- ---------------------------------------------------------------------
CREATE FUNCTION public_guards(p_city text, p_from timestamptz, p_to timestamptz)
    RETURNS TABLE (
        slot_id       uuid,
        network_name  text,
        guard_type    text,
        starts_at     timestamptz,
        ends_at       timestamptz,
        tenant_id     uuid,
        pharmacy_name text,
        address       text,
        city          text,
        phone         text,
        whatsapp      text,
        latitude      numeric,
        longitude     numeric,
        time_zone     text
    )
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
SELECT g.id, n.name::text, g.guard_type::text, g.starts_at, g.ends_at,
       c.tenant_id, c.display_name::text,
       nullif(concat_ws(', ', c.address_line1, c.address_line2, c.postal_code), '')::text,
       c.city::text, c.phone::text, c.whatsapp::text, c.latitude, c.longitude, t.time_zone::text
FROM guard_schedule g
JOIN pharmacy_network n ON n.id = g.network_id AND n.publish_guards
JOIN tenant t ON t.id = g.pharmacy_tenant_id AND t.status = 'ACTIVE'
JOIN pharmacy_config c ON c.tenant_id = g.pharmacy_tenant_id
WHERE g.starts_at < p_to
  AND g.ends_at > p_from
  AND (p_city IS NULL OR lower(c.city) = lower(p_city))
ORDER BY g.starts_at, c.display_name
$$;
ALTER FUNCTION public_guards(text, timestamptz, timestamptz) OWNER TO pharmacore_network_reader;

REVOKE CREATE ON SCHEMA public FROM pharmacore_network_reader;

REVOKE ALL ON FUNCTION public_pharmacies(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public_availability(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION partner_stock_availability(uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public_guards(text, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public_pharmacies(text) TO pharmacore_app;
GRANT EXECUTE ON FUNCTION public_availability(text, text) TO pharmacore_app;
GRANT EXECUTE ON FUNCTION partner_stock_availability(uuid, text) TO pharmacore_app;
GRANT EXECUTE ON FUNCTION public_guards(text, timestamptz, timestamptz) TO pharmacore_app;
