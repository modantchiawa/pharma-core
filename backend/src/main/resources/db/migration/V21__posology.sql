-- =====================================================================
-- V21 : posologie saisie au comptoir, par ligne de vente.
--  * {"morning":1,"noon":0,"evening":1,"bedtime":0,"durationDays":7,
--     "withFood":true,"instructions":"..."} ; NULL quand rien n'a été saisi.
--  * Imprimée sur le ticket et sur l'étiquette à pictogrammes remise au
--    patient ; la durée sert aux rappels de fin de traitement.
--  * Colonne facultative : un hub d'une version antérieure réplique ses
--    lignes sans elle pendant une mise à jour.
-- =====================================================================
ALTER TABLE sale_line ADD COLUMN posology jsonb;
