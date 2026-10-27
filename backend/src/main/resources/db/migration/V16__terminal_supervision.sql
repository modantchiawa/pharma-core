-- =====================================================================
-- V16 : supervision des postes de caisse et des comptes.
--
-- * Les postes signalent désormais le nombre de ventes rejetées par le
--   serveur qui attendent une décision du pharmacien. Colonne facultative :
--   un poste ou un hub d'une version antérieure n'envoie pas la valeur
--   (NULL = inconnu) et la réplication générique (jsonb_populate_record)
--   reste compatible dans les deux sens pendant une mise à jour.
-- * La dernière connexion d'un compte est lue dans le journal d'audit
--   (événements LOGIN et PIN_LOGIN, répliqués du hub vers le cloud) :
--   index partiel pour ne pas parcourir tout le journal de la pharmacie.
-- =====================================================================

ALTER TABLE terminal_heartbeat ADD COLUMN rejected_sales integer CHECK (rejected_sales >= 0);

CREATE INDEX ix_audit_event_user_login ON audit_event (tenant_id, resource_id, occurred_at DESC)
    WHERE resource_type = 'USER' AND action IN ('LOGIN', 'PIN_LOGIN');
