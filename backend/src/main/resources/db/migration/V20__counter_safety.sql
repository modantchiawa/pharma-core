-- =====================================================================
-- V20 : vigilance pharmaceutique au comptoir.
--  * Référentiel des interactions et des précautions (grossesse,
--    allaitement, enfant, personne âgée, insuffisance rénale ou hépatique,
--    déficit en G6PD, asthme, diabète, hypertension, épilepsie, ulcère,
--    glaucome, drépanocytose). Propriété du cloud, comme le catalogue :
--    l'éditeur le maintient, chaque hub en reçoit une copie et le poste de
--    caisse le garde hors ligne. Une substance est une DCI normalisée
--    (majuscules sans accents) ou une classe ATC (« ATC:M01A »).
--    Identifiants déterministes : les mêmes lignes existent sur le hub et
--    le cloud avant même la première synchronisation.
--  * Profil santé du client : date de naissance, grossesse, allaitement,
--    allergies, pathologies chroniques. Jamais exposé au réseau ni au
--    public ; la messagerie et les alertes du titulaire n'en reçoivent rien.
--  * Dérogation : une contre-indication passée outre par un pharmacien
--    est enregistrée avec son motif, rattachée à la vente, et signalée au
--    titulaire (alerte du contrôle interne, sans donnée patient).
-- =====================================================================

INSERT INTO permission (code, category, description) VALUES
    ('SAFETY_OVERRIDE', 'SALES', 'Passer outre une contre-indication signalée au comptoir, avec motif');

INSERT INTO role_permission (role_id, permission_code, tenant_id)
SELECT r.id, 'SAFETY_OVERRIDE', r.tenant_id
FROM app_role r
WHERE r.code IN ('TITULAIRE', 'ADJOINT');

-- ---------------------------------------------------------------------
-- Référentiel (cloud, global)
-- ---------------------------------------------------------------------
CREATE TABLE drug_interaction (
    id          uuid PRIMARY KEY,
    kind        varchar(12)  NOT NULL CHECK (kind IN ('INTERACTION', 'CONTEXT')),
    -- DCI normalisée ou classe ATC préfixée « ATC: »
    substance   varchar(120) NOT NULL,
    -- INTERACTION : autre substance (substance <= counterpart, égalité = doublon de classe) ;
    -- CONTEXT : situation du patient
    counterpart varchar(120) NOT NULL,
    severity    varchar(16)  NOT NULL CHECK (severity IN ('CONTRAINDICATED', 'ADVISED_AGAINST', 'PRECAUTION')),
    advice      varchar(400) NOT NULL,
    source      varchar(120),
    active      boolean      NOT NULL DEFAULT true,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    UNIQUE (kind, substance, counterpart),
    CHECK (kind <> 'INTERACTION' OR substance <= counterpart),
    CHECK (kind <> 'CONTEXT' OR counterpart IN
        ('PREGNANCY', 'BREASTFEEDING', 'CHILD', 'ELDERLY', 'RENAL', 'HEPATIC', 'G6PD', 'ASTHMA', 'DIABETES',
         'HYPERTENSION', 'EPILEPSY', 'ULCER', 'GLAUCOMA', 'SICKLE_CELL'))
);
CREATE INDEX ix_drug_interaction_updated ON drug_interaction (updated_at, id);
CREATE INDEX ix_drug_interaction_substance ON drug_interaction (substance) WHERE active;
CREATE INDEX ix_drug_interaction_counterpart ON drug_interaction (counterpart) WHERE active;

GRANT SELECT, INSERT, UPDATE ON drug_interaction TO pharmacore_app;
ALTER TABLE drug_interaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_drug_interaction_read ON drug_interaction FOR SELECT TO pharmacore_app USING (true);
CREATE POLICY p_drug_interaction_insert ON drug_interaction FOR INSERT TO pharmacore_app
    WITH CHECK (app_is_platform_admin());
CREATE POLICY p_drug_interaction_update ON drug_interaction FOR UPDATE TO pharmacore_app
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- ---------------------------------------------------------------------
-- Amorce du référentiel : interactions établies (thésaurus des
-- interactions médicamenteuses, résumés des caractéristiques des produits)
-- et précautions selon le profil. L'éditeur la complète par import.
-- ---------------------------------------------------------------------
INSERT INTO drug_interaction (id, kind, substance, counterpart, severity, advice, source)
SELECT md5('INTERACTION' || least(a, b) || '|' || greatest(a, b))::uuid, 'INTERACTION', least(a, b), greatest(a, b),
       sev, adv, 'Amorce PharmaCore'
FROM (VALUES
    -- anticoagulants oraux
    ('ACENOCOUMAROL', 'MICONAZOLE', 'CONTRAINDICATED', 'Hémorragies graves (même en gel buccal) : association contre-indiquée.'),
    ('MICONAZOLE', 'WARFARINE', 'CONTRAINDICATED', 'Hémorragies graves (même en gel buccal) : association contre-indiquée.'),
    ('FLUINDIONE', 'MICONAZOLE', 'CONTRAINDICATED', 'Hémorragies graves (même en gel buccal) : association contre-indiquée.'),
    ('ATC:B01AA', 'ATC:M01A', 'ADVISED_AGAINST', 'Risque hémorragique majoré (anticoagulant + AINS) : à éviter, préférer le paracétamol.'),
    ('ACIDE ACETYLSALICYLIQUE', 'ATC:B01AA', 'ADVISED_AGAINST', 'Risque hémorragique majoré : aspirine à dose anti-inflammatoire déconseillée sous anticoagulant.'),
    ('ATC:B01AA', 'METRONIDAZOLE', 'ADVISED_AGAINST', 'Augmentation de l''effet anticoagulant : surveiller l''INR, préférer une autre molécule.'),
    ('ATC:B01AA', 'FLUCONAZOLE', 'ADVISED_AGAINST', 'Augmentation de l''effet anticoagulant : surveiller l''INR.'),
    ('ATC:B01AA', 'SULFAMETHOXAZOLE', 'ADVISED_AGAINST', 'Augmentation de l''effet anticoagulant (cotrimoxazole) : surveiller l''INR.'),
    ('ATC:B01AA', 'RIFAMPICINE', 'PRECAUTION', 'Diminution de l''effet anticoagulant : contrôle de l''INR à l''instauration et à l''arrêt.'),
    ('ATC:B01AA', 'ATC:N06AB', 'PRECAUTION', 'Risque hémorragique accru (antidépresseur ISRS + anticoagulant).'),
    -- AINS
    ('ATC:M01A', 'ATC:M01A', 'ADVISED_AGAINST', 'Deux anti-inflammatoires non stéroïdiens : risque ulcéreux et hémorragique sans gain d''efficacité.'),
    ('ACIDE ACETYLSALICYLIQUE', 'IBUPROFENE', 'ADVISED_AGAINST', 'Majoration du risque ulcéreux et hémorragique ; l''ibuprofène peut annuler l''effet antiagrégant de l''aspirine.'),
    ('ATC:M01A', 'METHOTREXATE', 'ADVISED_AGAINST', 'Toxicité hématologique du méthotrexate majorée par les AINS.'),
    ('ATC:M01A', 'LITHIUM', 'ADVISED_AGAINST', 'Augmentation de la lithémie (toxicité) : à éviter.'),
    ('ATC:C09', 'ATC:M01A', 'PRECAUTION', 'Insuffisance rénale aiguë possible (IEC ou sartan + AINS), surtout chez le sujet âgé ou déshydraté.'),
    ('ATC:C03', 'ATC:M01A', 'PRECAUTION', 'Insuffisance rénale aiguë possible (diurétique + AINS) : hydrater, surveiller.'),
    ('ATC:H02', 'ATC:M01A', 'PRECAUTION', 'Risque ulcéreux et hémorragique digestif (corticoïde + AINS).'),
    ('ATC:M01A', 'ATC:N06AB', 'PRECAUTION', 'Risque hémorragique accru (ISRS + AINS).'),
    -- méthotrexate
    ('METHOTREXATE', 'TRIMETHOPRIME', 'CONTRAINDICATED', 'Toxicité hématologique grave (aplasie) : association contre-indiquée.'),
    ('METHOTREXATE', 'SULFAMETHOXAZOLE', 'CONTRAINDICATED', 'Toxicité hématologique grave avec le cotrimoxazole : association contre-indiquée.'),
    -- statines et inhibiteurs enzymatiques
    ('ERYTHROMYCINE', 'SIMVASTATINE', 'CONTRAINDICATED', 'Rhabdomyolyse : association contre-indiquée.'),
    ('CLARITHROMYCINE', 'SIMVASTATINE', 'CONTRAINDICATED', 'Rhabdomyolyse : association contre-indiquée.'),
    ('KETOCONAZOLE', 'SIMVASTATINE', 'CONTRAINDICATED', 'Rhabdomyolyse : association contre-indiquée.'),
    ('ITRACONAZOLE', 'SIMVASTATINE', 'CONTRAINDICATED', 'Rhabdomyolyse : association contre-indiquée.'),
    ('ATORVASTATINE', 'CLARITHROMYCINE', 'ADVISED_AGAINST', 'Risque de rhabdomyolyse : suspendre la statine pendant le traitement.'),
    ('ATORVASTATINE', 'ERYTHROMYCINE', 'ADVISED_AGAINST', 'Risque de rhabdomyolyse : suspendre la statine pendant le traitement.'),
    -- macrolides, azolés et allongement du QT
    ('CLARITHROMYCINE', 'ERGOTAMINE', 'CONTRAINDICATED', 'Ergotisme (nécrose des extrémités) : association contre-indiquée.'),
    ('ERGOTAMINE', 'ERYTHROMYCINE', 'CONTRAINDICATED', 'Ergotisme (nécrose des extrémités) : association contre-indiquée.'),
    ('CLARITHROMYCINE', 'COLCHICINE', 'CONTRAINDICATED', 'Toxicité mortelle de la colchicine : association contre-indiquée.'),
    ('COLCHICINE', 'ERYTHROMYCINE', 'CONTRAINDICATED', 'Toxicité mortelle de la colchicine : association contre-indiquée.'),
    ('DOMPERIDONE', 'KETOCONAZOLE', 'CONTRAINDICATED', 'Troubles du rythme (allongement du QT) : association contre-indiquée.'),
    ('DOMPERIDONE', 'ERYTHROMYCINE', 'CONTRAINDICATED', 'Troubles du rythme (allongement du QT) : association contre-indiquée.'),
    ('DOMPERIDONE', 'FLUCONAZOLE', 'ADVISED_AGAINST', 'Troubles du rythme (allongement du QT) : à éviter.'),
    ('CIPROFLOXACINE', 'TIZANIDINE', 'CONTRAINDICATED', 'Hypotension et sédation sévères : association contre-indiquée.'),
    ('CIPROFLOXACINE', 'THEOPHYLLINE', 'ADVISED_AGAINST', 'Surdosage en théophylline (convulsions) : à éviter.'),
    ('ERYTHROMYCINE', 'THEOPHYLLINE', 'PRECAUTION', 'Augmentation de la théophyllinémie : surveiller.'),
    ('CARBAMAZEPINE', 'CLARITHROMYCINE', 'ADVISED_AGAINST', 'Surdosage en carbamazépine : à éviter.'),
    ('CARBAMAZEPINE', 'ERYTHROMYCINE', 'ADVISED_AGAINST', 'Surdosage en carbamazépine : à éviter.'),
    ('KETOCONAZOLE', 'RIFAMPICINE', 'CONTRAINDICATED', 'Inefficacité des deux traitements : association contre-indiquée.'),
    ('DISULFIRAME', 'METRONIDAZOLE', 'CONTRAINDICATED', 'Confusion, délire : association contre-indiquée.'),
    ('ATC:J01FA', 'HYDROXYZINE', 'PRECAUTION', 'Allongement du QT : prudence, surtout chez le sujet âgé.'),
    -- antipaludiques
    ('ARTEMETHER', 'HALOFANTRINE', 'CONTRAINDICATED', 'Troubles du rythme (allongement du QT) : association contre-indiquée.'),
    ('HALOFANTRINE', 'QUININE', 'CONTRAINDICATED', 'Troubles du rythme (allongement du QT) : association contre-indiquée.'),
    ('MEFLOQUINE', 'QUININE', 'ADVISED_AGAINST', 'Convulsions et troubles du rythme : respecter un délai de 12 h après la quinine.'),
    ('AMODIAQUINE', 'EFAVIRENZ', 'CONTRAINDICATED', 'Hépatotoxicité sévère : association contre-indiquée.'),
    ('ARTEMETHER', 'ATC:J01FA', 'PRECAUTION', 'Allongement du QT possible (artéméther + macrolide).'),
    ('ARTEMETHER', 'KETOCONAZOLE', 'PRECAUTION', 'Augmentation des concentrations d''artéméther-luméfantrine : prudence.'),
    ('ATC:A10BB', 'QUININE', 'PRECAUTION', 'Hypoglycémie majorée (quinine + sulfamide hypoglycémiant) : surveiller la glycémie.'),
    ('ATC:A02A', 'CHLOROQUINE', 'PRECAUTION', 'Absorption de la chloroquine diminuée par les antiacides : espacer de 4 h.'),
    ('CHLOROQUINE', 'MEFLOQUINE', 'ADVISED_AGAINST', 'Risque de convulsions : à éviter.'),
    -- antidiabétiques
    ('GLIBENCLAMIDE', 'MICONAZOLE', 'CONTRAINDICATED', 'Hypoglycémie sévère, voire coma : association contre-indiquée.'),
    ('GLICLAZIDE', 'MICONAZOLE', 'CONTRAINDICATED', 'Hypoglycémie sévère, voire coma : association contre-indiquée.'),
    ('GLIMEPIRIDE', 'MICONAZOLE', 'CONTRAINDICATED', 'Hypoglycémie sévère, voire coma : association contre-indiquée.'),
    ('ATC:A10BB', 'FLUCONAZOLE', 'PRECAUTION', 'Hypoglycémie majorée : surveiller la glycémie.'),
    ('ATC:A10B', 'RIFAMPICINE', 'PRECAUTION', 'Diminution de l''effet hypoglycémiant : surveiller la glycémie.'),
    ('ATC:A10B', 'ATC:H02', 'PRECAUTION', 'Hyperglycémie sous corticoïde : adapter le traitement antidiabétique.'),
    ('ATC:A10B', 'ATC:C07', 'PRECAUTION', 'Les bêtabloquants masquent les signes d''hypoglycémie.'),
    -- cardiologie
    ('ATC:C07', 'VERAPAMIL', 'ADVISED_AGAINST', 'Bradycardie, bloc auriculo-ventriculaire, défaillance cardiaque : à éviter.'),
    ('ATC:C07', 'DILTIAZEM', 'ADVISED_AGAINST', 'Bradycardie, bloc auriculo-ventriculaire : à éviter.'),
    ('PROPRANOLOL', 'SALBUTAMOL', 'CONTRAINDICATED', 'Bronchospasme (bêtabloquant non cardiosélectif) : association contre-indiquée chez l''asthmatique.'),
    ('ATC:C07AA', 'SALBUTAMOL', 'ADVISED_AGAINST', 'Bronchospasme (bêtabloquant non cardiosélectif) : à éviter.'),
    ('ISOSORBIDE', 'SILDENAFIL', 'CONTRAINDICATED', 'Chute de tension potentiellement mortelle (dérivé nitré) : association contre-indiquée.'),
    ('SILDENAFIL', 'TRINITRINE', 'CONTRAINDICATED', 'Chute de tension potentiellement mortelle (dérivé nitré) : association contre-indiquée.'),
    ('ATC:C09', 'SPIRONOLACTONE', 'PRECAUTION', 'Hyperkaliémie : surveiller le potassium et la fonction rénale.'),
    ('CHLORURE DE POTASSIUM', 'SPIRONOLACTONE', 'ADVISED_AGAINST', 'Hyperkaliémie potentiellement mortelle : à éviter sauf hypokaliémie documentée.'),
    ('ATC:C09', 'CHLORURE DE POTASSIUM', 'ADVISED_AGAINST', 'Hyperkaliémie : à éviter sauf hypokaliémie documentée.'),
    ('AMIODARONE', 'DIGOXINE', 'PRECAUTION', 'Surdosage en digoxine : réduire la dose, surveiller.'),
    ('DIGOXINE', 'VERAPAMIL', 'PRECAUTION', 'Surdosage en digoxine et bradycardie : surveiller.'),
    ('CLARITHROMYCINE', 'DIGOXINE', 'PRECAUTION', 'Surdosage en digoxine : surveiller.'),
    ('CLOPIDOGREL', 'OMEPRAZOLE', 'ADVISED_AGAINST', 'Diminution de l''effet antiagrégant : préférer le pantoprazole.'),
    ('CLOPIDOGREL', 'ESOMEPRAZOLE', 'ADVISED_AGAINST', 'Diminution de l''effet antiagrégant : préférer le pantoprazole.'),
    -- neurologie, psychiatrie, antalgiques
    ('LEVODOPA', 'METOCLOPRAMIDE', 'CONTRAINDICATED', 'Antagonisme (aggravation du Parkinson) : association contre-indiquée.'),
    ('ATC:N06AB', 'TRAMADOL', 'ADVISED_AGAINST', 'Syndrome sérotoninergique et convulsions : à éviter.'),
    ('CARBAMAZEPINE', 'TRAMADOL', 'PRECAUTION', 'Diminution de l''effet antalgique du tramadol.'),
    ('ATC:N02A', 'ATC:N05B', 'PRECAUTION', 'Dépression respiratoire et sédation majorées (opioïde + anxiolytique).'),
    ('ATC:N02A', 'ATC:N05CD', 'PRECAUTION', 'Dépression respiratoire et sédation majorées (opioïde + hypnotique).'),
    ('ACIDE VALPROIQUE', 'LAMOTRIGINE', 'PRECAUTION', 'Risque cutané grave (syndrome de Lyell) : augmentation très progressive de la lamotrigine.'),
    ('LAMOTRIGINE', 'VALPROATE', 'PRECAUTION', 'Risque cutané grave (syndrome de Lyell) : augmentation très progressive de la lamotrigine.'),
    -- inducteurs enzymatiques et contraception
    ('ATC:G03A', 'CARBAMAZEPINE', 'ADVISED_AGAINST', 'Échec de la contraception hormonale (inducteur enzymatique) : autre méthode nécessaire.'),
    ('ATC:G03A', 'RIFAMPICINE', 'ADVISED_AGAINST', 'Échec de la contraception hormonale (inducteur enzymatique) : autre méthode nécessaire.'),
    ('ATC:G03A', 'PHENOBARBITAL', 'ADVISED_AGAINST', 'Échec de la contraception hormonale (inducteur enzymatique) : autre méthode nécessaire.'),
    ('ATC:G03A', 'PHENYTOINE', 'ADVISED_AGAINST', 'Échec de la contraception hormonale (inducteur enzymatique) : autre méthode nécessaire.'),
    ('ATC:G03A', 'EFAVIRENZ', 'ADVISED_AGAINST', 'Échec possible de la contraception hormonale : autre méthode nécessaire.'),
    ('NEVIRAPINE', 'RIFAMPICINE', 'ADVISED_AGAINST', 'Diminution des concentrations de névirapine (échec du traitement) : à éviter.'),
    ('ALLOPURINOL', 'AZATHIOPRINE', 'ADVISED_AGAINST', 'Toxicité hématologique grave : réduire l''azathioprine au quart de la dose.'),
    -- absorption : espacer les prises
    ('ATC:A02A', 'ATC:J01AA', 'PRECAUTION', 'Absorption de la tétracycline diminuée par les antiacides : espacer les prises de 2 h.'),
    ('ATC:A02A', 'ATC:J01M', 'PRECAUTION', 'Absorption de la quinolone diminuée par les antiacides : espacer les prises de 2 h.'),
    ('ATC:B03A', 'ATC:J01AA', 'PRECAUTION', 'Absorption de la tétracycline diminuée par le fer : espacer les prises de 2 h.'),
    ('ATC:B03A', 'ATC:J01M', 'PRECAUTION', 'Absorption de la quinolone diminuée par le fer : espacer les prises de 2 h.'),
    ('ATC:A12A', 'ATC:J01AA', 'PRECAUTION', 'Absorption de la tétracycline diminuée par le calcium : espacer les prises de 2 h.'),
    ('ATC:A12A', 'ATC:J01M', 'PRECAUTION', 'Absorption de la quinolone diminuée par le calcium : espacer les prises de 2 h.'),
    ('ATC:A02A', 'ATC:B03A', 'PRECAUTION', 'Absorption du fer diminuée par les antiacides : espacer les prises de 2 h.')
) AS v (a, b, sev, adv);

INSERT INTO drug_interaction (id, kind, substance, counterpart, severity, advice, source)
SELECT md5('CONTEXT' || s || '|' || c)::uuid, 'CONTEXT', s, c, sev, adv, 'Amorce PharmaCore'
FROM (VALUES
    -- grossesse
    ('MISOPROSTOL', 'PREGNANCY', 'CONTRAINDICATED', 'Contractions utérines, avortement : contre-indiqué pendant la grossesse.'),
    ('ISOTRETINOINE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations graves : contre-indiqué, contraception obligatoire.'),
    ('ACITRETINE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations graves : contre-indiqué, contraception obligatoire.'),
    ('METHOTREXATE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations et avortement : contre-indiqué.'),
    ('THALIDOMIDE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations graves : contre-indiqué.'),
    ('WARFARINE', 'PREGNANCY', 'CONTRAINDICATED', 'Embryopathie et hémorragies : contre-indiqué, relais par héparine.'),
    ('ACENOCOUMAROL', 'PREGNANCY', 'CONTRAINDICATED', 'Embryopathie et hémorragies : contre-indiqué, relais par héparine.'),
    ('FLUINDIONE', 'PREGNANCY', 'CONTRAINDICATED', 'Embryopathie et hémorragies : contre-indiqué, relais par héparine.'),
    ('ATC:C09', 'PREGNANCY', 'CONTRAINDICATED', 'Toxicité fœtale rénale (IEC, sartan) : contre-indiqué aux 2e et 3e trimestres, déconseillé au 1er.'),
    ('ATC:M01A', 'PREGNANCY', 'ADVISED_AGAINST', 'AINS contre-indiqué à partir du 6e mois (toxicité fœtale), déconseillé avant : préférer le paracétamol.'),
    ('ACIDE ACETYLSALICYLIQUE', 'PREGNANCY', 'ADVISED_AGAINST', 'Contre-indiqué à partir du 6e mois à dose antalgique : préférer le paracétamol.'),
    ('ATC:J01AA', 'PREGNANCY', 'CONTRAINDICATED', 'Coloration des dents et atteinte osseuse du fœtus : tétracycline contre-indiquée aux 2e et 3e trimestres.'),
    ('ATC:J01M', 'PREGNANCY', 'ADVISED_AGAINST', 'Quinolone déconseillée pendant la grossesse (atteinte cartilagineuse).'),
    ('ATC:C10AA', 'PREGNANCY', 'CONTRAINDICATED', 'Statine contre-indiquée pendant la grossesse.'),
    ('RIBAVIRINE', 'PREGNANCY', 'CONTRAINDICATED', 'Tératogène : contre-indiqué.'),
    ('PRIMAQUINE', 'PREGNANCY', 'CONTRAINDICATED', 'Hémolyse fœtale : contre-indiqué.'),
    ('MEBENDAZOLE', 'PREGNANCY', 'ADVISED_AGAINST', 'Déconseillé au 1er trimestre.'),
    ('ALBENDAZOLE', 'PREGNANCY', 'ADVISED_AGAINST', 'Déconseillé au 1er trimestre (tératogène chez l''animal).'),
    ('EFAVIRENZ', 'PREGNANCY', 'ADVISED_AGAINST', 'Déconseillé au 1er trimestre : avis du prescripteur.'),
    ('ARTEMETHER', 'PREGNANCY', 'ADVISED_AGAINST', 'Déconseillé au 1er trimestre sauf absence d''alternative : avis médical.'),
    ('MEFLOQUINE', 'PREGNANCY', 'PRECAUTION', 'Possible si nécessaire : avis médical.'),
    ('METRONIDAZOLE', 'PREGNANCY', 'PRECAUTION', 'Éviter au 1er trimestre si une alternative existe.'),
    ('CODEINE', 'PREGNANCY', 'PRECAUTION', 'Dépression respiratoire du nouveau-né en fin de grossesse : usage bref.'),
    ('DIAZEPAM', 'PREGNANCY', 'PRECAUTION', 'Hypotonie et sevrage du nouveau-né : éviter en fin de grossesse.'),
    ('CARBAMAZEPINE', 'PREGNANCY', 'ADVISED_AGAINST', 'Malformations (tube neural) : avis du prescripteur, acide folique.'),
    ('ACIDE VALPROIQUE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations et troubles du développement : contre-indiqué sauf absence d''alternative.'),
    ('VALPROATE', 'PREGNANCY', 'CONTRAINDICATED', 'Malformations et troubles du développement : contre-indiqué sauf absence d''alternative.'),
    ('PHENYTOINE', 'PREGNANCY', 'ADVISED_AGAINST', 'Malformations : avis du prescripteur.'),
    ('LITHIUM', 'PREGNANCY', 'ADVISED_AGAINST', 'Malformations cardiaques : avis du prescripteur.'),
    ('ERGOTAMINE', 'PREGNANCY', 'CONTRAINDICATED', 'Contractions utérines, vasoconstriction : contre-indiqué.'),
    ('DANAZOL', 'PREGNANCY', 'CONTRAINDICATED', 'Virilisation du fœtus féminin : contre-indiqué.'),
    ('FINASTERIDE', 'PREGNANCY', 'CONTRAINDICATED', 'Anomalies génitales du fœtus masculin : contre-indiqué, ne pas manipuler les comprimés écrasés.'),
    ('KETOCONAZOLE', 'PREGNANCY', 'ADVISED_AGAINST', 'Déconseillé par voie orale pendant la grossesse.'),
    ('FLUCONAZOLE', 'PREGNANCY', 'ADVISED_AGAINST', 'Malformations à forte dose : déconseillé au 1er trimestre.'),
    ('AMIODARONE', 'PREGNANCY', 'CONTRAINDICATED', 'Hypothyroïdie fœtale : contre-indiqué.'),
    -- allaitement
    ('CODEINE', 'BREASTFEEDING', 'CONTRAINDICATED', 'Dépression respiratoire du nourrisson : contre-indiqué.'),
    ('TRAMADOL', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Passage dans le lait : déconseillé.'),
    ('ATC:J01M', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Quinolone déconseillée pendant l''allaitement.'),
    ('ATC:J01AA', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Tétracycline déconseillée pendant l''allaitement.'),
    ('AMIODARONE', 'BREASTFEEDING', 'CONTRAINDICATED', 'Hypothyroïdie du nourrisson : contre-indiqué.'),
    ('LITHIUM', 'BREASTFEEDING', 'CONTRAINDICATED', 'Toxicité pour le nourrisson : contre-indiqué.'),
    ('METHOTREXATE', 'BREASTFEEDING', 'CONTRAINDICATED', 'Toxicité pour le nourrisson : contre-indiqué.'),
    ('PRIMAQUINE', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Hémolyse possible du nourrisson (G6PD) : déconseillé.'),
    ('CHLORAMPHENICOL', 'BREASTFEEDING', 'CONTRAINDICATED', 'Toxicité médullaire du nourrisson : contre-indiqué.'),
    ('ERGOTAMINE', 'BREASTFEEDING', 'CONTRAINDICATED', 'Ergotisme du nourrisson, baisse de la lactation : contre-indiqué.'),
    ('ACIDE ACETYLSALICYLIQUE', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Déconseillé à dose antalgique répétée (syndrome de Reye, saignements).'),
    ('METRONIDAZOLE', 'BREASTFEEDING', 'PRECAUTION', 'Passage dans le lait : traitement bref, goût du lait modifié.'),
    ('ISOTRETINOINE', 'BREASTFEEDING', 'CONTRAINDICATED', 'Contre-indiqué pendant l''allaitement.'),
    ('DIAZEPAM', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Sédation du nourrisson : déconseillé.'),
    ('BROMOCRIPTINE', 'BREASTFEEDING', 'ADVISED_AGAINST', 'Arrête la lactation : vérifier que c''est l''objectif.'),
    -- enfant (moins de 12 ans)
    ('ACIDE ACETYLSALICYLIQUE', 'CHILD', 'ADVISED_AGAINST', 'Syndrome de Reye en cas d''infection virale : préférer le paracétamol.'),
    ('CODEINE', 'CHILD', 'CONTRAINDICATED', 'Dépression respiratoire : contre-indiqué avant 12 ans.'),
    ('TRAMADOL', 'CHILD', 'ADVISED_AGAINST', 'Déconseillé avant 12 ans (dépression respiratoire).'),
    ('ATC:J01M', 'CHILD', 'ADVISED_AGAINST', 'Quinolone déconseillée chez l''enfant (atteinte des cartilages) sauf indication spécialisée.'),
    ('ATC:J01AA', 'CHILD', 'CONTRAINDICATED', 'Tétracycline contre-indiquée avant 8 ans (coloration définitive des dents).'),
    ('LOPERAMIDE', 'CHILD', 'ADVISED_AGAINST', 'Contre-indiqué avant 2 ans, déconseillé avant 6 ans : réhydratation orale d''abord.'),
    ('METOCLOPRAMIDE', 'CHILD', 'ADVISED_AGAINST', 'Troubles neurologiques (dyskinésies) : contre-indiqué avant 1 an, déconseillé avant 18 ans.'),
    ('PROMETHAZINE', 'CHILD', 'ADVISED_AGAINST', 'Dépression respiratoire : contre-indiqué avant 2 ans.'),
    ('DEXTROMETHORPHANE', 'CHILD', 'ADVISED_AGAINST', 'Antitussif contre-indiqué avant 2 ans, déconseillé avant 6 ans.'),
    ('DIPHENHYDRAMINE', 'CHILD', 'ADVISED_AGAINST', 'Déconseillé avant 6 ans (sédation, excitation paradoxale).'),
    ('DOMPERIDONE', 'CHILD', 'PRECAUTION', 'Dose au poids chez l''enfant, durée brève.'),
    ('MEFLOQUINE', 'CHILD', 'PRECAUTION', 'Dose au poids, à éviter avant 3 mois ou 5 kg.'),
    -- personne âgée (65 ans et plus)
    ('ATC:M01A', 'ELDERLY', 'PRECAUTION', 'Insuffisance rénale et hémorragie digestive plus fréquentes : dose minimale, durée brève.'),
    ('ATC:N05B', 'ELDERLY', 'PRECAUTION', 'Chutes et confusion : demi-dose, durée brève.'),
    ('DIAZEPAM', 'ELDERLY', 'ADVISED_AGAINST', 'Demi-vie longue : chutes et confusion, préférer une benzodiazépine courte.'),
    ('HYDROXYZINE', 'ELDERLY', 'PRECAUTION', 'Effets anticholinergiques et QT : dose réduite.'),
    ('METOCLOPRAMIDE', 'ELDERLY', 'PRECAUTION', 'Troubles neurologiques : dose réduite.'),
    ('DIGOXINE', 'ELDERLY', 'PRECAUTION', 'Surdosage fréquent : dose réduite selon la fonction rénale.'),
    ('GLIBENCLAMIDE', 'ELDERLY', 'ADVISED_AGAINST', 'Hypoglycémies prolongées : préférer le gliclazide.'),
    ('TRAMADOL', 'ELDERLY', 'PRECAUTION', 'Confusion, chutes : dose réduite.'),
    ('PROMETHAZINE', 'ELDERLY', 'PRECAUTION', 'Effets anticholinergiques : confusion, rétention urinaire.'),
    ('CODEINE', 'ELDERLY', 'PRECAUTION', 'Constipation, confusion : dose réduite.'),
    -- insuffisance rénale
    ('METFORMINE', 'RENAL', 'ADVISED_AGAINST', 'Acidose lactique : contre-indiqué si clairance < 30 ml/min, dose réduite entre 30 et 60.'),
    ('ATC:M01A', 'RENAL', 'ADVISED_AGAINST', 'Aggravation de l''insuffisance rénale : à éviter.'),
    ('LITHIUM', 'RENAL', 'ADVISED_AGAINST', 'Accumulation toxique : avis du prescripteur.'),
    ('ATC:J01G', 'RENAL', 'ADVISED_AGAINST', 'Néphrotoxicité et ototoxicité des aminosides : adaptation stricte des doses.'),
    ('SPIRONOLACTONE', 'RENAL', 'ADVISED_AGAINST', 'Hyperkaliémie : à éviter.'),
    ('CHLORURE DE POTASSIUM', 'RENAL', 'ADVISED_AGAINST', 'Hyperkaliémie : à éviter.'),
    ('ATC:C09', 'RENAL', 'PRECAUTION', 'Surveillance du potassium et de la créatinine.'),
    ('ALLOPURINOL', 'RENAL', 'PRECAUTION', 'Dose à réduire selon la clairance (toxicité cutanée).'),
    ('COLCHICINE', 'RENAL', 'ADVISED_AGAINST', 'Toxicité accrue : contre-indiqué en insuffisance rénale sévère.'),
    ('ATC:A02A', 'RENAL', 'PRECAUTION', 'Accumulation d''aluminium ou de magnésium : usage occasionnel seulement.'),
    ('ACICLOVIR', 'RENAL', 'PRECAUTION', 'Dose à réduire selon la clairance, hydrater.'),
    ('METHOTREXATE', 'RENAL', 'CONTRAINDICATED', 'Toxicité grave : contre-indiqué en insuffisance rénale sévère.'),
    ('TENOFOVIR', 'RENAL', 'PRECAUTION', 'Néphrotoxicité : adapter l''intervalle des prises.'),
    ('NITROFURANTOINE', 'RENAL', 'CONTRAINDICATED', 'Inefficace et toxique : contre-indiqué si clairance < 45 ml/min.'),
    ('DIGOXINE', 'RENAL', 'PRECAUTION', 'Accumulation : dose réduite, surveillance.'),
    ('GLIBENCLAMIDE', 'RENAL', 'ADVISED_AGAINST', 'Hypoglycémies prolongées : contre-indiqué en insuffisance rénale sévère.'),
    -- insuffisance hépatique
    ('PARACETAMOL', 'HEPATIC', 'PRECAUTION', 'Ne pas dépasser 3 g par jour.'),
    ('KETOCONAZOLE', 'HEPATIC', 'CONTRAINDICATED', 'Hépatotoxicité : contre-indiqué par voie orale.'),
    ('ACIDE VALPROIQUE', 'HEPATIC', 'CONTRAINDICATED', 'Hépatite grave : contre-indiqué.'),
    ('VALPROATE', 'HEPATIC', 'CONTRAINDICATED', 'Hépatite grave : contre-indiqué.'),
    ('METHOTREXATE', 'HEPATIC', 'CONTRAINDICATED', 'Hépatotoxicité : contre-indiqué.'),
    ('AMODIAQUINE', 'HEPATIC', 'ADVISED_AGAINST', 'Hépatotoxicité : à éviter.'),
    ('RIFAMPICINE', 'HEPATIC', 'PRECAUTION', 'Surveillance des transaminases.'),
    ('ISONIAZIDE', 'HEPATIC', 'PRECAUTION', 'Surveillance des transaminases.'),
    ('ATC:C10AA', 'HEPATIC', 'ADVISED_AGAINST', 'Statine contre-indiquée en cas d''affection hépatique évolutive.'),
    ('ERYTHROMYCINE', 'HEPATIC', 'PRECAUTION', 'Hépatite cholestatique possible : prudence.'),
    ('FLUCONAZOLE', 'HEPATIC', 'PRECAUTION', 'Surveillance hépatique.'),
    ('NEVIRAPINE', 'HEPATIC', 'ADVISED_AGAINST', 'Hépatotoxicité sévère : avis du prescripteur.'),
    ('ALBENDAZOLE', 'HEPATIC', 'PRECAUTION', 'Surveillance hépatique en traitement prolongé.'),
    ('ATC:M01A', 'HEPATIC', 'PRECAUTION', 'Rétention hydrosodée et saignements : prudence.'),
    ('CARBAMAZEPINE', 'HEPATIC', 'PRECAUTION', 'Surveillance hépatique.'),
    ('TRAMADOL', 'HEPATIC', 'PRECAUTION', 'Espacer les prises (métabolisme ralenti).'),
    -- déficit en G6PD
    ('PRIMAQUINE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('TAFENOQUINE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('DAPSONE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('NITROFURANTOINE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('ATC:J01E', 'G6PD', 'ADVISED_AGAINST', 'Hémolyse possible (sulfamide) : à éviter.'),
    ('SULFADOXINE', 'G6PD', 'ADVISED_AGAINST', 'Hémolyse possible (sulfamide) : à éviter.'),
    ('CHLOROQUINE', 'G6PD', 'PRECAUTION', 'Hémolyse possible à forte dose : prudence.'),
    ('QUININE', 'G6PD', 'PRECAUTION', 'Hémolyse possible : prudence.'),
    ('ACIDE NALIDIXIQUE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('BLEU DE METHYLENE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë : contre-indiqué.'),
    ('RASBURICASE', 'G6PD', 'CONTRAINDICATED', 'Hémolyse aiguë et méthémoglobinémie : contre-indiqué.'),
    ('ATC:J01M', 'G6PD', 'PRECAUTION', 'Hémolyse possible (quinolone) : prudence.'),
    ('ACIDE ACETYLSALICYLIQUE', 'G6PD', 'PRECAUTION', 'Hémolyse possible à forte dose : prudence.'),
    ('ATC:A10BB', 'G6PD', 'PRECAUTION', 'Hémolyse possible (sulfamide hypoglycémiant) : prudence.'),
    -- asthme
    ('PROPRANOLOL', 'ASTHMA', 'CONTRAINDICATED', 'Bronchospasme (bêtabloquant non cardiosélectif) : contre-indiqué.'),
    ('ATC:C07AA', 'ASTHMA', 'CONTRAINDICATED', 'Bronchospasme (bêtabloquant non cardiosélectif) : contre-indiqué.'),
    ('ATC:C07', 'ASTHMA', 'ADVISED_AGAINST', 'Bronchospasme possible même avec un bêtabloquant cardiosélectif : avis du prescripteur.'),
    ('TIMOLOL', 'ASTHMA', 'ADVISED_AGAINST', 'Bronchospasme possible même en collyre : à éviter.'),
    ('ACIDE ACETYLSALICYLIQUE', 'ASTHMA', 'PRECAUTION', 'Crise d''asthme chez les patients intolérants à l''aspirine.'),
    ('ATC:M01A', 'ASTHMA', 'PRECAUTION', 'Crise d''asthme chez les patients intolérants aux AINS.'),
    -- diabète
    ('ATC:H02', 'DIABETES', 'PRECAUTION', 'Hyperglycémie sous corticoïde : surveiller la glycémie.'),
    ('ATC:C07', 'DIABETES', 'PRECAUTION', 'Les bêtabloquants masquent les signes d''hypoglycémie.'),
    ('ATC:C03A', 'DIABETES', 'PRECAUTION', 'Diurétique thiazidique : hyperglycémie possible.'),
    ('QUININE', 'DIABETES', 'PRECAUTION', 'Hypoglycémie : surveiller la glycémie.'),
    ('ATC:J01M', 'DIABETES', 'PRECAUTION', 'Troubles de la glycémie sous quinolone : surveiller.'),
    -- hypertension
    ('ATC:M01A', 'HYPERTENSION', 'PRECAUTION', 'Élévation de la pression artérielle et antagonisme des antihypertenseurs : durée brève.'),
    ('PSEUDOEPHEDRINE', 'HYPERTENSION', 'ADVISED_AGAINST', 'Poussée hypertensive (vasoconstricteur) : déconseillé.'),
    ('PHENYLEPHRINE', 'HYPERTENSION', 'PRECAUTION', 'Vasoconstricteur : prudence, usage bref.'),
    ('ATC:H02', 'HYPERTENSION', 'PRECAUTION', 'Rétention hydrosodée : surveiller la pression artérielle.'),
    ('ATC:G03A', 'HYPERTENSION', 'PRECAUTION', 'Contraceptif œstroprogestatif : surveiller la pression artérielle.'),
    ('ERGOTAMINE', 'HYPERTENSION', 'ADVISED_AGAINST', 'Vasoconstriction : déconseillé.'),
    -- épilepsie
    ('TRAMADOL', 'EPILEPSY', 'ADVISED_AGAINST', 'Abaisse le seuil épileptogène : à éviter.'),
    ('BUPROPION', 'EPILEPSY', 'CONTRAINDICATED', 'Convulsions : contre-indiqué.'),
    ('MEFLOQUINE', 'EPILEPSY', 'CONTRAINDICATED', 'Convulsions : contre-indiqué.'),
    ('CHLOROQUINE', 'EPILEPSY', 'PRECAUTION', 'Abaisse le seuil épileptogène : prudence.'),
    ('ATC:J01M', 'EPILEPSY', 'PRECAUTION', 'Quinolone : abaisse le seuil épileptogène.'),
    ('ATC:N05A', 'EPILEPSY', 'PRECAUTION', 'Neuroleptique : abaisse le seuil épileptogène.'),
    ('THEOPHYLLINE', 'EPILEPSY', 'PRECAUTION', 'Convulsions en cas de surdosage.'),
    ('METOCLOPRAMIDE', 'EPILEPSY', 'PRECAUTION', 'Peut favoriser les crises.'),
    -- ulcère gastro-duodénal
    ('ATC:M01A', 'ULCER', 'CONTRAINDICATED', 'AINS contre-indiqué en cas d''ulcère évolutif (perforation, hémorragie).'),
    ('ACIDE ACETYLSALICYLIQUE', 'ULCER', 'ADVISED_AGAINST', 'Hémorragie digestive : déconseillé, préférer le paracétamol.'),
    ('ATC:H02', 'ULCER', 'ADVISED_AGAINST', 'Corticoïde : aggravation de l''ulcère, protection gastrique nécessaire.'),
    ('CHLORURE DE POTASSIUM', 'ULCER', 'PRECAUTION', 'Ulcérations digestives : prendre au cours des repas avec un grand verre d''eau.'),
    ('ATC:B01AA', 'ULCER', 'PRECAUTION', 'Hémorragie digestive : surveillance.'),
    ('ATC:N06AB', 'ULCER', 'PRECAUTION', 'ISRS : risque de saignement digestif.'),
    -- glaucome à angle fermé
    ('ATC:R06A', 'GLAUCOMA', 'ADVISED_AGAINST', 'Antihistaminique anticholinergique : crise de glaucome aigu.'),
    ('ATROPINE', 'GLAUCOMA', 'CONTRAINDICATED', 'Crise de glaucome aigu : contre-indiqué.'),
    ('SCOPOLAMINE', 'GLAUCOMA', 'CONTRAINDICATED', 'Crise de glaucome aigu : contre-indiqué.'),
    ('ATC:N06AA', 'GLAUCOMA', 'ADVISED_AGAINST', 'Antidépresseur tricyclique : crise de glaucome aigu.'),
    ('ATC:A03B', 'GLAUCOMA', 'ADVISED_AGAINST', 'Antispasmodique anticholinergique : crise de glaucome aigu.'),
    ('ATC:H02', 'GLAUCOMA', 'PRECAUTION', 'Corticoïde : élévation de la pression intra-oculaire.'),
    ('PSEUDOEPHEDRINE', 'GLAUCOMA', 'ADVISED_AGAINST', 'Crise de glaucome aigu : déconseillé.'),
    ('TOPIRAMATE', 'GLAUCOMA', 'PRECAUTION', 'Glaucome aigu rapporté : surveillance.'),
    -- drépanocytose
    ('ATC:M01A', 'SICKLE_CELL', 'PRECAUTION', 'Atteinte rénale fréquente dans la drépanocytose : AINS à dose minimale, hydrater.'),
    ('ATC:G03A', 'SICKLE_CELL', 'PRECAUTION', 'Contraceptif œstroprogestatif : risque thrombotique, préférer un progestatif seul.')
) AS v (s, c, sev, adv);

-- ---------------------------------------------------------------------
-- Profil santé du client (hub)
-- ---------------------------------------------------------------------
CREATE TABLE customer_health_profile (
    customer_id   uuid PRIMARY KEY REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    birth_date    date,
    sex           varchar(1)   CHECK (sex IN ('F', 'M')),
    weight_kg     smallint     CHECK (weight_kg BETWEEN 1 AND 300),
    pregnant      boolean      NOT NULL DEFAULT false,
    breastfeeding boolean      NOT NULL DEFAULT false,
    -- ["PENICILLINS", "SULFONAMIDES", "IODE"] : familles connues ou substance en texte libre
    allergies     jsonb        NOT NULL DEFAULT '[]',
    -- ["DIABETES", "HYPERTENSION", ...] : situations du référentiel
    conditions    jsonb        NOT NULL DEFAULT '[]',
    notes         varchar(500),
    updated_by    uuid,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_customer_health_profile_tenant ON customer_health_profile (tenant_id, updated_at DESC);

-- ---------------------------------------------------------------------
-- Dérogations à une contre-indication (écriture seule)
-- ---------------------------------------------------------------------
CREATE TABLE dispensing_safety_override (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id) DEFERRABLE INITIALLY IMMEDIATE,
    sale_id        uuid         NOT NULL REFERENCES sale (id) DEFERRABLE INITIALLY IMMEDIATE,
    customer_id    uuid         REFERENCES customer (id) DEFERRABLE INITIALLY IMMEDIATE,
    alert_key      varchar(300) NOT NULL,
    kind           varchar(20)  NOT NULL CHECK (kind IN
                       ('INTERACTION', 'DUPLICATE_SUBSTANCE', 'ALLERGY', 'CONTEXT', 'RECENT_DISPENSING')),
    severity       varchar(16)  NOT NULL CHECK (severity IN ('CONTRAINDICATED', 'ADVISED_AGAINST', 'PRECAUTION')),
    title          varchar(150) NOT NULL,
    detail         varchar(400),
    reason         varchar(255) NOT NULL,
    decided_by     uuid         NOT NULL REFERENCES app_user (id) DEFERRABLE INITIALLY IMMEDIATE,
    decided_at     timestamptz  NOT NULL
);
CREATE INDEX ix_dispensing_safety_override_period ON dispensing_safety_override (tenant_id, decided_at DESC);
CREATE INDEX ix_dispensing_safety_override_sale ON dispensing_safety_override (tenant_id, sale_id);

-- Alertes du titulaire : nouvelles natures
ALTER TABLE control_alert DROP CONSTRAINT control_alert_kind_check;
ALTER TABLE control_alert ADD CONSTRAINT control_alert_kind_check CHECK (kind IN
    ('LARGE_ADJUSTMENT', 'CANCELLATION_AFTER_PAYMENT', 'CASH_VARIANCE', 'COUNT_DISCREPANCY',
     'OUTSIDE_HOURS', 'PIN_FAILURES', 'RECEIPT_DISCREPANCY', 'SAFETY_OVERRIDE', 'DAILY_DIGEST', 'TEST'));

-- ---------------------------------------------------------------------
-- Droits, isolation, synchronisation
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON customer_health_profile TO pharmacore_app;
GRANT SELECT, INSERT ON dispensing_safety_override TO pharmacore_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer_health_profile', 'dispensing_safety_override']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY p_tenant_isolation ON %I TO pharmacore_app '
            'USING (tenant_id = app_current_tenant() OR app_is_platform_admin()) '
            'WITH CHECK (tenant_id = app_current_tenant() OR app_is_platform_admin())', t);
    END LOOP;
END
$$;

-- Référentiel après les tables cloud existantes (17) ; profil après le
-- client (30) ; dérogation après la vente (36) et les utilisateurs (24).
INSERT INTO sync_table (table_name, owner_side, scope, origin_column, apply_order) VALUES
    ('drug_interaction',           'CLOUD', 'GLOBAL', NULL, 18),
    ('customer_health_profile',    'HUB',   'TENANT', NULL, 70),
    ('dispensing_safety_override', 'HUB',   'TENANT', NULL, 71);
SELECT sync_track(table_name) FROM sync_table
WHERE table_name IN ('drug_interaction', 'customer_health_profile', 'dispensing_safety_override');
