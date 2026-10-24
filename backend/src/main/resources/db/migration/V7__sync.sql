-- =====================================================================
-- V7 : synchronisation hub ↔ cloud.
--
-- Principe : chaque table a UN seul propriétaire (HUB ou CLOUD). Le
-- propriétaire écrit, l'autre côté reçoit une réplique. Il n'y a donc
-- jamais de conflit à résoudre. Les changements sont capturés par un
-- trigger générique dans sync_change et rejoués par upsert.
--
-- Lecture sûre : chaque changement porte l'identifiant (64 bits) de sa
-- transaction. Le journal est lu dans l'ordre (xid, seq) et le lecteur
-- n'emporte que les transactions antérieures à toute transaction encore
-- en cours (horizon xmin) : une transaction validée en retard a toujours
-- un xid supérieur au curseur et sera lue au tour suivant. Les clés
-- étrangères sont rendues différables pour qu'un lot soit vérifié à sa
-- validation, quel que soit l'ordre interne des lignes.
-- =====================================================================

CREATE TABLE sync_table (
    table_name    varchar(63) PRIMARY KEY,
    owner_side    varchar(5)  NOT NULL CHECK (owner_side IN ('HUB', 'CLOUD')),
    -- TENANT : lignes d'une pharmacie (tenant_id) ; GLOBAL : référentiel partagé
    scope         varchar(6)  NOT NULL CHECK (scope IN ('TENANT', 'GLOBAL')),
    -- Colonne discriminant le côté auteur quand les deux côtés écrivent (audit)
    origin_column varchar(63),
    apply_order   smallint    NOT NULL
);

CREATE TABLE sync_change (
    seq        bigserial PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    table_name varchar(63) NOT NULL,
    op         char(1)     NOT NULL CHECK (op IN ('I', 'U', 'D')),
    pk         jsonb       NOT NULL,
    row        jsonb,
    xid        bigint      NOT NULL DEFAULT pg_current_xact_id()::text::bigint,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sync_change_order ON sync_change (xid, seq);
CREATE INDEX ix_sync_change_tenant_order ON sync_change (tenant_id, xid, seq);

CREATE TABLE sync_cursor (
    tenant_id  uuid        NOT NULL,
    stream     varchar(10) NOT NULL CHECK (stream IN ('PUSH', 'PULL')),
    last_xid   bigint      NOT NULL DEFAULT 0,
    last_seq   bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, stream)
);

-- Capture générique. SECURITY DEFINER : l'insertion dans sync_change ne
-- dépend pas des politiques RLS de la session appelante. L'application
-- d'une réplique positionne app.sync_apply = on pour ne pas re-capturer
-- (pas d'écho).
CREATE FUNCTION sync_capture() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp AS
$$
DECLARE
    v_row    jsonb;
    v_pk     jsonb := '{}'::jsonb;
    v_tenant uuid;
    v_col    text;
BEGIN
    IF coalesce(current_setting('app.sync_apply', true), '') = 'on' THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD);
    ELSE
        v_row := to_jsonb(NEW);
    END IF;
    FOREACH v_col IN ARRAY TG_ARGV LOOP
        v_pk := v_pk || jsonb_build_object(v_col, v_row -> v_col);
    END LOOP;
    -- La ligne « tenant » est son propre périmètre ; les tables globales sont rattachées à la plateforme
    v_tenant := coalesce((v_row ->> 'tenant_id')::uuid,
                         CASE WHEN TG_TABLE_NAME = 'tenant' THEN (v_row ->> 'id')::uuid END,
                         '00000000-0000-0000-0000-000000000000'::uuid);
    INSERT INTO sync_change (tenant_id, table_name, op, pk, row)
    VALUES (v_tenant, TG_TABLE_NAME, left(TG_OP, 1),
            v_pk, CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE v_row END);
    RETURN NULL;
END
$$;

-- Attache le trigger de capture à une table, avec ses colonnes de clé primaire.
CREATE FUNCTION sync_track(p_table text) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    v_cols text;
BEGIN
    SELECT string_agg(quote_literal(a.attname), ', ' ORDER BY k.ordinality)
    INTO v_cols
    FROM pg_index i
    JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ordinality) ON true
    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
    WHERE i.indrelid = p_table::regclass AND i.indisprimary;
    IF v_cols IS NULL THEN
        RAISE EXCEPTION 'Table % has no primary key', p_table;
    END IF;
    EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_capture ON %I', p_table);
    EXECUTE format('CREATE TRIGGER trg_sync_capture AFTER INSERT OR UPDATE OR DELETE ON %I '
                   'FOR EACH ROW EXECUTE FUNCTION sync_capture(%s)', p_table, v_cols);
END
$$;

-- Ordre d'application : les parents avant les enfants (clés étrangères).
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    -- Propriété du cloud : référentiel national, abonnement, réseau
    ('product',                 'CLOUD', 'GLOBAL', NULL, 10),
    ('tenant',                  'CLOUD', 'TENANT', NULL, 11),
    ('pharmacy_network',        'CLOUD', 'GLOBAL', NULL, 12),
    ('network_membership',      'CLOUD', 'TENANT', NULL, 13),
    ('inter_pharmacy_transfer', 'CLOUD', 'GLOBAL', NULL, 14),
    ('guard_schedule',          'CLOUD', 'GLOBAL', NULL, 15),
    ('conversation',            'CLOUD', 'GLOBAL', NULL, 16),
    ('chat_message',            'CLOUD', 'GLOBAL', NULL, 17),
    -- Propriété du hub : toute l'exploitation de l'officine
    ('tenant_asset',            'HUB', 'TENANT', NULL, 20),
    ('pharmacy_config',         'HUB', 'TENANT', NULL, 21),
    ('app_role',                'HUB', 'TENANT', NULL, 22),
    ('role_permission',         'HUB', 'TENANT', NULL, 23),
    ('app_user',                'HUB', 'TENANT', NULL, 24),
    ('user_role',               'HUB', 'TENANT', NULL, 25),
    ('signing_key',             'HUB', 'TENANT', NULL, 26),
    ('tenant_sequence',         'HUB', 'TENANT', NULL, 27),
    ('terminal_heartbeat',      'HUB', 'TENANT', NULL, 28),
    ('customer',                'HUB', 'TENANT', NULL, 30),
    ('supplier',                'HUB', 'TENANT', NULL, 31),
    ('supplier_product',        'HUB', 'TENANT', NULL, 32),
    ('stock',                   'HUB', 'TENANT', NULL, 33),
    ('stock_lot',               'HUB', 'TENANT', NULL, 34),
    ('cash_session',            'HUB', 'TENANT', NULL, 35),
    ('sale',                    'HUB', 'TENANT', NULL, 36),
    ('sale_line',               'HUB', 'TENANT', NULL, 37),
    ('sale_line_allocation',    'HUB', 'TENANT', NULL, 38),
    ('sale_payment',            'HUB', 'TENANT', NULL, 39),
    ('stock_movement',          'HUB', 'TENANT', NULL, 40),
    ('cash_movement',           'HUB', 'TENANT', NULL, 41),
    ('customer_payment',        'HUB', 'TENANT', NULL, 42),
    ('purchase_order',          'HUB', 'TENANT', NULL, 43),
    ('purchase_order_line',     'HUB', 'TENANT', NULL, 44),
    ('goods_receipt',           'HUB', 'TENANT', NULL, 45),
    ('goods_receipt_line',      'HUB', 'TENANT', NULL, 46),
    ('account',                 'HUB', 'TENANT', NULL, 47),
    ('accounting_period',       'HUB', 'TENANT', NULL, 48),
    ('journal_entry',           'HUB', 'TENANT', NULL, 49),
    ('journal_line',            'HUB', 'TENANT', NULL, 50),
    -- Journal d'audit : chaque côté pousse ses propres événements
    ('audit_event',             'HUB', 'TENANT', 'origin', 60);

SELECT sync_track(table_name) FROM sync_table;

GRANT SELECT ON sync_table TO pharmacore_app;
GRANT SELECT, DELETE ON sync_change TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON sync_cursor TO pharmacore_app;
GRANT USAGE ON SEQUENCE sync_change_seq_seq TO pharmacore_app;

ALTER TABLE sync_change ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sync_change_read ON sync_change FOR SELECT TO pharmacore_app
    USING (tenant_id = app_current_tenant()
           OR tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
           OR app_is_platform_admin());
CREATE POLICY p_sync_change_purge ON sync_change FOR DELETE TO pharmacore_app
    USING (app_is_platform_admin());

ALTER TABLE sync_cursor ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sync_cursor ON sync_cursor TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

-- Position de purge : un pair dont le curseur est antérieur doit repartir
-- d'un instantané complet.
INSERT INTO node_setting (key, value) VALUES ('sync.purge_xid', '0') ON CONFLICT (key) DO NOTHING;

-- Clés étrangères différables : un lot répliqué est vérifié à la validation.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conrelid::regclass AS tbl, conname
             FROM pg_constraint
             WHERE contype = 'f' AND connamespace = 'public'::regnamespace
    LOOP
        EXECUTE format('ALTER TABLE %s ALTER CONSTRAINT %I DEFERRABLE INITIALLY IMMEDIATE', c.tbl, c.conname);
    END LOOP;
END
$$;

-- Le journal d'audit d'une pharmacie est répliqué par un processus
-- plateforme (hub → cloud → hub) : l'insertion est permise à l'admin
-- plateforme pour tout tenant. AuditTrail continue d'écrire ses propres
-- événements plateforme sous l'identifiant nul.
DROP POLICY p_audit_insert ON audit_event;
CREATE POLICY p_audit_insert ON audit_event FOR INSERT TO pharmacore_app
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());
