-- =====================================================================
-- V22 : suivi des patients par WhatsApp ou SMS, et rapport du soir.
--  * Consentement explicite du client (opt-in) et canal préféré.
--  * File de messages (hub) : fin de traitement (durée de la posologie),
--    renouvellement d'ordonnance dû, produit attendu arrivé, rappel de
--    créance, message libre. Un message WhatsApp est envoyé par l'équipe
--    d'un clic (lien wa.me pré-rempli) puis marqué envoyé ; un SMS part
--    seul par la passerelle HTTP de la pharmacie quand elle est réglée.
--  * Produits attendus : le client demande à être prévenu quand un produit
--    en rupture revient ; la réception du stock génère le message.
--  * Réglages par pharmacie : activation, modèles, heure d'envoi,
--    passerelle SMS. Le rapport du soir (alerte DAILY_DIGEST) y est réglé.
-- =====================================================================

INSERT INTO permission (code, category, description) VALUES
    ('FOLLOWUP_VIEW',     'SALES', 'Consulter et envoyer les messages de suivi des patients (WhatsApp, SMS)'),
    ('FOLLOWUP_SETTINGS', 'ADMIN', 'Régler le suivi des patients : modèles, rappels, passerelle SMS, rapport du soir');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, 'FOLLOWUP_VIEW', r.tenant_id
FROM app_role r
WHERE r.code IN ('TITULAIRE', 'ADJOINT', 'CAISSIER', 'PREPARATEUR');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, 'FOLLOWUP_SETTINGS', r.tenant_id
FROM app_role r
WHERE r.code = 'TITULAIRE';

-- Consentement : jamais de message sans accord explicite du client
ALTER TABLE customer ADD COLUMN messaging_consent boolean NOT NULL DEFAULT false;
ALTER TABLE customer ADD COLUMN preferred_channel varchar(8) NOT NULL DEFAULT 'WHATSAPP'
    CHECK (preferred_channel IN ('WHATSAPP', 'SMS'));

CREATE TABLE followup_settings (
    tenant_id                  uuid PRIMARY KEY REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    enabled                    boolean      NOT NULL DEFAULT true,
    treatment_end_enabled      boolean      NOT NULL DEFAULT true,
    renewal_enabled            boolean      NOT NULL DEFAULT true,
    renewal_days_ahead         smallint     NOT NULL DEFAULT 3 CHECK (renewal_days_ahead BETWEEN 0 AND 30),
    product_wish_enabled       boolean      NOT NULL DEFAULT true,
    credit_reminder_enabled    boolean      NOT NULL DEFAULT false,
    credit_reminder_after_days smallint     NOT NULL DEFAULT 30 CHECK (credit_reminder_after_days BETWEEN 1 AND 365),
    credit_reminder_every_days smallint     NOT NULL DEFAULT 14 CHECK (credit_reminder_every_days BETWEEN 1 AND 365),
    -- {"TREATMENT_END": "...", "RENEWAL_DUE": "...", ...} ; une clé absente = modèle par défaut
    templates                  jsonb        NOT NULL DEFAULT '{}',
    sms_gateway_url            varchar(500),
    sms_gateway_token          varchar(200),
    sms_sender                 varchar(11),
    -- Rapport du soir au titulaire : alerte DAILY_DIGEST à l'heure dite, et SMS au numéro donné
    digest_enabled             boolean      NOT NULL DEFAULT true,
    digest_hour                smallint     NOT NULL DEFAULT 21 CHECK (digest_hour BETWEEN 0 AND 23),
    digest_phone               varchar(30),
    updated_by                 uuid,
    updated_at                 timestamptz  NOT NULL DEFAULT now(),
    version                    bigint       NOT NULL DEFAULT 0
);

CREATE TABLE customer_message (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    customer_id  uuid         NOT NULL REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    kind         varchar(20)  NOT NULL CHECK (kind IN
                     ('TREATMENT_END', 'RENEWAL_DUE', 'PRODUCT_AVAILABLE', 'CREDIT_REMINDER', 'CUSTOM')),
    channel      varchar(8)   NOT NULL CHECK (channel IN ('WHATSAPP', 'SMS')),
    phone        varchar(30)  NOT NULL,
    body         varchar(600) NOT NULL,
    status       varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    -- Une même cause ne produit qu'un message : "TREATMENT_END:<ligne>", "RENEWAL:<ordonnance>:<date>", ...
    dedupe_key   varchar(120) NOT NULL,
    source_type  varchar(30),
    source_id    uuid,
    created_at   timestamptz  NOT NULL,
    created_by   uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    attempts     smallint     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error   varchar(300),
    sent_at      timestamptz,
    sent_by      uuid REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    version      bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX ix_customer_message_queue ON customer_message (tenant_id, status, created_at DESC);
CREATE INDEX ix_customer_message_customer ON customer_message (tenant_id, customer_id, created_at DESC);
CREATE INDEX ix_customer_message_sms ON customer_message (status, next_attempt_at) WHERE channel = 'SMS';

CREATE TABLE customer_product_wish (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    customer_id  uuid        NOT NULL REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    product_id   uuid        NOT NULL REFERENCES product (id) DEFERRABLE INITIALLY IMMEDIATE,
    quantity     smallint    NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 999),
    status       varchar(10) NOT NULL CHECK (status IN ('WAITING', 'NOTIFIED', 'CANCELLED')),
    requested_by uuid        NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    requested_at timestamptz NOT NULL,
    notified_at  timestamptz,
    message_id   uuid REFERENCES customer_message (id) DEFERRABLE INITIALLY IMMEDIATE,
    version      bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_customer_product_wish_waiting ON customer_product_wish (tenant_id, product_id) WHERE status = 'WAITING';
CREATE INDEX ix_customer_product_wish_customer ON customer_product_wish (tenant_id, customer_id, requested_at DESC);

GRANT SELECT, INSERT, UPDATE ON followup_settings TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON customer_message TO pharmacore_app;
GRANT SELECT, INSERT, UPDATE ON customer_product_wish TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['followup_settings', 'customer_message', 'customer_product_wish']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Après le client (30), les utilisateurs (24) et le produit (10) ; le souhait après le message.
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('followup_settings',     'HUB', 'TENANT', NULL, 72),
    ('customer_message',      'HUB', 'TENANT', NULL, 72),
    ('customer_product_wish', 'HUB', 'TENANT', NULL, 73);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('followup_settings', 'customer_message', 'customer_product_wish');
