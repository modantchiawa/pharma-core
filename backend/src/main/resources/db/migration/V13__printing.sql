-- =====================================================================
-- V13 : impression des tickets et factures par poste (navigateur,
-- imprimante thermique ESC/POS réseau ou partagée).
-- =====================================================================
CREATE TABLE terminal_printer (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    terminal_id   varchar(40)  NOT NULL,
    mode          varchar(15)  NOT NULL CHECK (mode IN ('BROWSER', 'ESCPOS_NETWORK', 'ESCPOS_SHARED')),
    paper         varchar(5)   NOT NULL CHECK (paper IN ('MM58', 'MM80', 'A4')),
    -- hôte:port (réseau, 9100 par défaut) ou chemin / partage Windows \\PC\Imprimante
    address       varchar(255),
    width_chars   smallint     NOT NULL CHECK (width_chars IN (32, 42, 48)),
    copies        smallint     NOT NULL DEFAULT 1 CHECK (copies BETWEEN 1 AND 3),
    cut           boolean      NOT NULL DEFAULT true,
    drawer_kick   boolean      NOT NULL DEFAULT false,
    auto_print    boolean      NOT NULL DEFAULT true,
    invoice_paper varchar(5)   NOT NULL DEFAULT 'A4' CHECK (invoice_paper IN ('A4', 'NONE')),
    updated_by    uuid,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, terminal_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON terminal_printer TO pharmacore_app;

ALTER TABLE terminal_printer ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON terminal_printer TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());

INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('terminal_printer', 'HUB', 'TENANT', NULL, 29);
SELECT sync_track('terminal_printer');
