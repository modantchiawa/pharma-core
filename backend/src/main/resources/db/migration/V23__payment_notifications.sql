-- =====================================================================
-- V23 : confirmations de paiement mobile money reçues en direct.
--  * La confirmation d'un paiement Airtel / Moov / Orange / Zamani / NITA /
--    Wave arrive sur le téléphone de la pharmacie (SMS) ou par webhook de
--    l'opérateur. Elle est reçue par le serveur local (SMS transféré par le
--    téléphone sur le réseau de l'officine, ou saisie sur n'importe quel
--    poste), rapprochée des règlements en attente et diffusée à tous les
--    postes par le temps réel du hub : tout cela sans Internet.
--  * Jeton d'ingestion par pharmacie (haché), présenté par le téléphone ou
--    l'opérateur ; motifs d'analyse des SMS propres à la pharmacie.
--  * Webhooks reçus par le cloud : table propriété du cloud, répliquée au
--    hub, qui les traite à son tour (une cause = une confirmation).
-- Montants en unités mineures de la devise (francs entiers pour le XOF).
-- =====================================================================

CREATE TABLE payment_channel_settings (
    tenant_id          uuid PRIMARY KEY REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- SHA-256 du jeton présenté par le téléphone (SMS transférés) et par les webhooks ; jamais le jeton
    ingest_token_hash  varchar(64),
    ingest_token_hint  varchar(4),
    token_created_at   timestamptz,
    auto_confirm       boolean     NOT NULL DEFAULT true,
    match_window_hours smallint    NOT NULL DEFAULT 48 CHECK (match_window_hours BETWEEN 1 AND 720),
    -- [{"provider":"AIRTEL_MONEY","pattern":"..."}] : expressions avec groupes nommés amount, reference, phone
    patterns           jsonb       NOT NULL DEFAULT '[]',
    updated_by         uuid,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0
);

CREATE TABLE payment_notification (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    source        varchar(10)  NOT NULL CHECK (source IN ('SMS', 'WEBHOOK', 'MANUAL')),
    provider      varchar(30),
    amount_cents  bigint       CHECK (amount_cents IS NULL OR amount_cents > 0),
    reference     varchar(64),
    payer_phone   varchar(30),
    raw_text      varchar(1000),
    -- Entrant (le client a payé) ou autre (envoi, solde, publicité) : seuls les entrants se rapprochent
    incoming      boolean      NOT NULL DEFAULT true,
    status        varchar(10)  NOT NULL CHECK (status IN ('UNMATCHED', 'MATCHED', 'IGNORED')),
    payment_id    uuid REFERENCES sale_payment (id) DEFERRABLE INITIALLY IMMEDIATE,
    matched_at    timestamptz,
    matched_by    uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    -- Une même confirmation ne compte qu'une fois : "SMS:<empreinte>", "WEBHOOK:<id>", "MANUAL:<id du poste>"
    dedupe_key    varchar(120) NOT NULL,
    recorded_by   uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    terminal_id   varchar(40),
    received_at   timestamptz  NOT NULL,
    created_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX ix_payment_notification_feed ON payment_notification (tenant_id, received_at DESC);
CREATE INDEX ix_payment_notification_open ON payment_notification (tenant_id, provider, amount_cents)
    WHERE status = 'UNMATCHED' AND incoming;
CREATE INDEX ix_payment_notification_payment ON payment_notification (payment_id) WHERE payment_id IS NOT NULL;

-- Webhooks reçus par le cloud (propriété du cloud, répliqués au hub)
CREATE TABLE payment_webhook_event (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    provider    varchar(30),
    payload     text         NOT NULL,
    received_at timestamptz  NOT NULL
);
CREATE INDEX ix_payment_webhook_event_feed ON payment_webhook_event (tenant_id, received_at DESC);

-- Un règlement peut porter d'où vient sa confirmation
ALTER TABLE sale_payment ADD COLUMN confirmation_source varchar(12)
    CHECK (confirmation_source IN ('STATEMENT', 'NOTIFICATION'));

GRANT SELECT, INSERT, UPDATE ON payment_channel_settings TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON payment_notification TO pharmacore_app;
GRANT SELECT, INSERT ON payment_webhook_event TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['payment_channel_settings', 'payment_notification', 'payment_webhook_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Réglages et confirmations : hub (après les règlements, 39, et les utilisateurs, 24) ;
-- webhooks : cloud, par pharmacie, tirés par le hub.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('payment_channel_settings', 'HUB',   'TENANT', NULL, 74),
    ('payment_notification',     'HUB',   'TENANT', NULL, 74),
    ('payment_webhook_event',    'CLOUD', 'TENANT', NULL, 19);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('payment_channel_settings', 'payment_notification', 'payment_webhook_event');
