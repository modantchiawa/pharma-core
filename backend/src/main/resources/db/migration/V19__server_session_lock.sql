-- =====================================================================
-- V19 : verrouillage des postes appliqué par le serveur.
--  * Une session de connexion (mot de passe ou PIN) regroupe les jetons de
--    rafraîchissement successifs d'un poste. Le serveur que le poste
--    appelle (le hub en mode hub, sinon le cloud) y tient la dernière
--    activité et le verrouillage ; chaque jeton d'accès porte son
--    identifiant (revendication sid). Tant que la session est verrouillée,
--    aucune donnée n'est servie (423) ; au délai de déconnexion elle est
--    fermée et ses jetons révoqués.
--  * Table locale au nœud, comme auth_session : jamais synchronisée. Un
--    jeton n'est vérifié que par le nœud qui l'a émis ; répliquer l'activité
--    de chaque poste toutes les 30 s ne servirait à aucun des deux côtés.
--  * L'activité est écrite au plus toutes les 30 s par session (cache en
--    mémoire), les changements d'état (verrouillage, déverrouillage, fin)
--    immédiatement : sur un cloud à plusieurs instances, la base fait foi.
-- =====================================================================
CREATE TABLE login_session (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    user_id          uuid        NOT NULL REFERENCES app_user (id),
    terminal_id      varchar(40) NOT NULL,
    started_at       timestamptz NOT NULL DEFAULT now(),
    last_activity_at timestamptz NOT NULL,
    locked_at        timestamptz,
    lock_reason      varchar(10) CHECK (lock_reason IN ('MANUAL', 'IDLE', 'SERVER')),
    ended_at         timestamptz,
    end_reason       varchar(20) CHECK (end_reason IN ('LOGOUT', 'IDLE_LOGOUT', 'UNLOCK_FAILURES')),
    CHECK ((locked_at IS NULL) = (lock_reason IS NULL)),
    CHECK ((ended_at IS NULL) = (end_reason IS NULL))
);
CREATE INDEX ix_login_session_user ON login_session (tenant_id, user_id) WHERE ended_at IS NULL;

-- Les jetons émis avant cette version n'ont pas de session : le prochain
-- rafraîchissement leur en ouvre une.
ALTER TABLE auth_session ADD COLUMN login_session_id uuid REFERENCES login_session (id) ON DELETE CASCADE;
CREATE INDEX ix_auth_session_login ON auth_session (login_session_id) WHERE login_session_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON login_session TO pharmacore_app;

ALTER TABLE login_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant_isolation ON login_session TO pharmacore_app
    USING (tenant_id = app_current_tenant() OR app_is_platform_admin())
    WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin());
