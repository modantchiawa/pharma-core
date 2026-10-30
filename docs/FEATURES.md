# Périmètre fonctionnel — PharmaCore PGO

Référentiel des fonctions attendues d'un progiciel de gestion d'officine sur le marché ouest-africain
(Niger, Sénégal, Mali, Burkina, Côte d'Ivoire…), et état de PharmaCore face à ce référentiel.

## Comment lire ce document

* **Référentiel** : il reprend les familles de fonctions des PGO installés dans la région, dont
  Winpharma (éditeur français présent via des revendeurs) et les logiciels locaux. La fiche
  fonctionnelle publique de Winpharma ne concerne que la France (SESAM-Vitale, Dossier
  Pharmaceutique) ; la liste ci-dessous ne retient que ce qui a un sens au Niger. Aucune
  documentation publique n'a pu être trouvée pour le logiciel sénégalais cité : ses fonctions
  sont supposées équivalentes au socle habituel d'un PGO. **À confirmer avec les brochures et
  démonstrations des concurrents**, afin d'ajuster les colonnes « marché » de la matrice.
* **État** : ✅ livré et testé · 🟡 livré, à compléter (précisé) · ⬜ planifié (phase indiquée).

## 1. Matrice fonctionnelle

### Caisse et vente

| Fonction | Attendu sur le marché | PharmaCore |
|---|---|---|
| Vente ultra-rapide clavier / douchette, recherche par nom, DCI, CIP/EAN | Oui | ✅ |
| **Substitution** : équivalents de même DCI et dosage proposés quand le produit demandé est en rupture, avec prix et stock de l'officine, y compris hors ligne | Rare | ✅ |
| Fonctionnement **hors-ligne total** (coupure Internet) | Partiel (poste serveur local) | ✅ local-first : journal IndexedDB par poste + serveur local (hub) |
| Multi-postes synchronisés sur le réseau de l'officine | Oui | ✅ hub LAN, temps réel STOMP |
| Devise XOF sans décimales, arrondis | Oui | ✅ |
| Espèces, carte, chèque, **mobile money** (Airtel, Moov, Orange, Zamani, MyNITA, Wave), tiers payant | Oui | ✅ |
| **Vente à crédit** (ardoise) avec plafond par client et encaissement des créances | Oui | ✅ |
| Remises par ligne avec plafond par rôle | Oui | ✅ RBAC + ABAC |
| Ordonnances, prescripteur, ordonnancier (numérotation sans trou), stupéfiants | Oui | ✅ |
| **Registre des délivrances** (listes I, II, stupéfiants) avec export ; ordonnances renouvelables, délivrances suivies, rappels de renouvellement | Oui | ✅ |
| Tiers payant : assureurs, mutuelles, entreprises conventionnées ; polices par client (taux, plafond mensuel, validité) ; part assureur calculée à la vente ; **bordereaux** par organisme (génération, émission, export CSV, règlement partiel), encours par assureur | Oui | ✅ |
| Annulation / retour avec remise en stock dans le lot d'origine | Oui | ✅ |
| **Sessions de caisse** : fond, mouvements, dépenses, rapport X/Z, écarts | Oui | ✅ |
| Clôture **à l'aveugle** par coupures XOF, rapprochement par mode et opérateur mobile money, écart au-dessus du seuil soumis à approbation, rapports Z chaînés (inviolables) | Rare | ✅ |
| Annulation ou remboursement au-dessus d'un seuil (ou sur session fermée) soumis au **PIN d'un superviseur** | Partiel | ✅ |
| Rapprochement des paiements **mobile money et carte** avec les relevés opérateurs (confirmation, rejet → vente en revue), référence de transaction unique | Rare | ✅ |
| **Encaissements par caisse** : total et transactions de chaque mode et opérateur (espèces, MyNITA, Airtel Money, carte…) sur un jour ou une période, tous postes ou un seul, ventes annulées exclues, paiements rejetés au relevé mis à part | Rare | ✅ |
| **Confirmations mobile money en direct, sans Internet** : le SMS de l'opérateur reçu sur le téléphone de la pharmacie est transféré au serveur local (jeton), lu (montant, référence, entrant/sortant), diffusé à toutes les caisses par le réseau de l'officine et rattaché de lui-même au règlement en attente ; webhooks opérateurs par le cloud ; saisie sur n'importe quel poste avec file hors ligne | Non | ✅ |
| Ticket thermique **58 mm (type TPE) ou 80 mm** aux couleurs de la pharmacie, **facture A4** (montant en lettres, TVA, client, police) | Oui | ✅ impression navigateur ou ESC/POS direct (USB partagée, réseau 9100), réglages par poste, réimpression, tiroir-caisse |
| **Vigilance au comptoir** : interactions médicamenteuses (par DCI et par classe ATC), doublon de substance caché dans deux boîtes, allergies déclarées, grossesse, allaitement, enfant, personne âgée, pathologies chroniques (rein, foie, G6PD, asthme, diabète, HTA, épilepsie, ulcère, glaucome, drépanocytose), produit déjà délivré au même client dans la semaine ; contre-indication bloquante levée par un pharmacien avec motif (auditée, signalée au titulaire) ; interactions et doublons vérifiés hors ligne sur le poste | Non (ou module payant sous licence) | ✅ référentiel amorcé (≈ 250 règles), maintenu par l'éditeur (JSON, CSV), profil santé du client |
| **Posologie par pictogrammes** : saisie en un geste au comptoir (matin, midi, soir, coucher, durée, repas, consigne), imprimée sur le ticket et sur une **étiquette** lisible sans savoir lire (soleil levant, soleil, coucher, lune, nombre en gros), rappel de fin de traitement | Non | ✅ |
| **Facture certifiée DGI (SECeF)** | Obligatoire au Niger pour les assujettis TVA | 🟡 port de certification, file de retry, QR/code sur ticket ; connecteur DGI à réaliser avec les spécifications SECeF |
| Afficheur client, imprimante étiquettes | Oui | ⬜ phase 2 bis (étiquettes de prix) ; tiroir-caisse ✅ via ESC/POS |
| Fidélité (points) | Souvent | 🟡 points stockés ; règles d'attribution : phase 2 |

### Stocks et approvisionnement

| Fonction | Attendu | PharmaCore |
|---|---|---|
| Gestion par **lot et péremption (FEFO)** | Oui | ✅ |
| Alertes de péremption, produits dormants, ruptures | Oui | ✅ péremptions, écarts hors-ligne · 🟡 dormants : phase 2 |
| Seuils mini / quantité de réappro par produit | Oui | ✅ + jours de couverture et stock de sécurité |
| **Fiche de commande du lendemain** calculée (vitesse de vente, délai fournisseur, en commande, péremptions, ventes perdues) | Oui (souvent sur seuils seuls) | ✅ |
| Fiche de commande : **recherche** dans la fiche et **ajout de produits à la main** (produit exceptionnel, commande spéciale, achat en grande quantité) depuis tout le catalogue, fournisseur au choix | Oui | ✅ |
| **Saisonnalité** : les semaines à venir comparées à l'an dernier (paludisme en saison des pluies, Ramadan, rentrée) relèvent ou abaissent la quantité proposée, motif « saison haute » affiché | Non | ✅ dès un an d'historique |
| Fournisseurs (grossistes-répartiteurs), références, prix d'achat, colisage, minimum de commande | Oui | ✅ |
| Envoi de commande : texte WhatsApp / e-mail, impression, CSV | Oui | ✅ WhatsApp (wa.me), e-mail, bon de commande A4 imprimé · 🟡 PDF : phase 2 |
| Génération directe d'une commande par fournisseur depuis la fiche, brouillons mis à jour sans doublon | Partiel | ✅ |
| Suivi des commandes : retards, livraisons du jour, montant en attente, report de date, solde des reliquats | Partiel | ✅ |
| Réception de livraison lot par lot, mise à jour des prix, suivi des reliquats | Oui | ✅ depuis la commande, pré-remplie, scan GS1 |
| Inventaire (comptage, écarts, valorisation) | Oui | ✅ session d'inventaire **à l'aveugle** par scan ou saisie, attendu masqué au compteur, validation par un pharmacien, écarts audités |
| **Emplacements de rangement** (rayons, tiroirs, réfrigérateur 2-8 °C, coffre des stupéfiants, réserve, comptoir) : produit affiché avec son emplacement au comptoir, même hors ligne, rangement groupé, inventaire par emplacement, étiquettes de rayon A4 | Partiel (texte libre) | ✅ |
| **Miniatures des produits** : photo de la boîte prise au téléphone (recadrée et réduite sur le poste), affichée au comptoir, dans les stocks et les commandes, hors ligne comprise | Rare | ✅ |
| **Contrôle d'intégrité** nocturne du stock (lots recalculés depuis les mouvements, réservations), sortie automatique des lots périmés avec valeur au coût | Rare | ✅ |
| Lecture des codes **GS1 DataMatrix** (lot, péremption) au comptoir, à la réception et à l'inventaire | Partiel | ✅ |
| Transferts entre pharmacies d'un même groupe | Parfois | ✅ réservations réseau ; transfert intra-groupe : phase 2 |
| Catalogue national (CIP/DCI, prix indicatifs, monographies) maintenu par l'éditeur | Rare | ✅ + **import du référentiel** (CSV, Excel) par l'éditeur |
| **Migration depuis un autre logiciel** : import des produits et prix, du stock par lot, des clients avec leurs créances, des fournisseurs (CSV tout séparateur et encodage, Excel .xlsx), colonnes reconnues automatiquement, aperçu, essai à blanc, rapport ligne par ligne | Partiel (prestation payante) | ✅ |

### Comptabilité et gestion

| Fonction | Attendu | PharmaCore |
|---|---|---|
| Journaux de ventes, achats, caisse, banque générés automatiquement | Oui | ✅ SYSCOHADA, partie double garantie par la base |
| Balance, grand livre | Oui | ✅ balance · 🟡 grand livre par compte (données présentes, écran : phase 2) |
| **Compte de résultat** et **bilan** de gestion | Rare (export vers le comptable) | ✅ bilan simplifié + compte de résultat, stock valorisé au coût moyen |
| Clôture mensuelle, écritures diverses | Oui | ✅ |
| Export vers le comptable (CSV compatible tableur / Sage) | Oui | ✅ CSV |
| Tableaux de bord : CA par jour, marge brute, panier moyen, encaissements par mode, top produits, valeur et péremption du stock, créances, écarts de caisse, comparaison avec la période précédente | Oui | ✅ page Pilotage · 🟡 par heure et par vendeur : phase 3 |
| Déclaration TVA (collectée / déductible) | Oui | ✅ comptes 4431/4452 dans la balance |

### Organisation, sécurité, réseau

| Fonction | Attendu | PharmaCore |
|---|---|---|
| Utilisateurs, rôles, matrice de permissions personnalisable | Oui | ✅ 48 permissions, 5 rôles prédéfinis modifiables |
| Connexion mot de passe + **bascule caissier par PIN**, sans Internet | Oui | ✅ |
| **Verrouillage du poste inactif** (écran flouté, page et panier conservés, reprise par PIN), déconnexion après un délai réglé par la pharmacie, Ctrl+L | Partiel | ✅ synchronisé entre onglets, hors ligne compris |
| Journal d'audit inaltérable | Rare | ✅ chaîne SHA-256 |
| **Contrôle interne anti-fraude** : recomptage tournant à l'aveugle tiré selon le risque, double validation (seconde personne et PIN) des sorties, retours et annulations encaissées, réception à l'aveugle et séparation des tâches, indicateurs par employé avec signalement expliqué, registre des stupéfiants, alertes en direct et webhook signé | Rare | ✅ Gestion › Contrôle interne |
| **Sauvegardes** automatiques locales, USB et cloud | Oui | ✅ tâche planifiée + réplique cloud continue |
| **Suivi patients WhatsApp / SMS** (opt-in) : fin de traitement, ordonnance à renouveler, « prévenez-moi quand ce produit revient », rappel de créance, message libre ; envoi WhatsApp en un geste (lien pré-rempli), SMS automatique par passerelle HTTPS ; modèles par pharmacie | Non | ✅ |
| **Rapport du soir au titulaire** : chiffres du jour (CA et variation, ventes, panier, marge, encaissements par mode, écart de caisse, ruptures, péremptions, créances) à l'heure choisie, dans la cloche, le webhook et par SMS ; partage WhatsApp depuis le pilotage | Non | ✅ |
| Supervision éditeur multi-pharmacies | — | ✅ |
| **Postes nominatifs** : chaque poste de caisse garde son identifiant unique (préfixe des tickets) et reçoit un nom et un emplacement (« Caisse 1 · comptoir d'entrée »), affichés partout (menu, caisse, encaissements, serveur local, console), conservés sur le poste hors ligne | Oui | ✅ |
| **Version et mise à jour** : version affichée (numéro, commit, date de construction) dans le menu et à la connexion ; bandeau « nouvelle version disponible » dès qu'une version est publiée (service worker et `version.json`), mise à jour en un clic ; « Forcer la mise à jour » vide le cache de l'application sans toucher aux ventes en attente | Oui | ✅ |
| Personnalisation : logo, couleurs, mentions, ticket ; densité, contraste, taille de texte, mode sombre par poste | Partiel | ✅ |
| Réseau inter-pharmacies : disponibilité d'urgence, réservations, gardes, messagerie | Rare | ✅ (nécessite Internet) |
| **« Qui a ce produit ? »** sur plusieurs produits à la fois et **« qu'a cette pharmacie ? »** chez les partenaires | Non | ✅ |
| **Annuaire public** (patients, médecins) : disponibilité d'un produit par ville (niveau seulement, opt-in de l'officine), fiche de la pharmacie, itinéraire, WhatsApp | Non | ✅ page `/public`, API publique limitée en débit |
| **Pharmacies de garde partagées** : publication publique par réseau (opt-in du coordinateur), message du jour prêt pour WhatsApp/SMS | Non | ✅ |
| Multilingue (français ; haoussa, zarma, wolof) | Français seul | 🟡 français ; infrastructure i18n Angular : phase 3 |
| Installation Windows multi-postes | Oui | ✅ paquet hub (services Windows) + PWA installable |
| Imprimantes par poste : navigateur (58 mm, 80 mm, A4) ou thermique ESC/POS (partage Windows, réseau) | Oui | ✅ page Imprimantes, test d'impression |

## 2. Spécificités du marché retenues

| Sujet | Choix |
|---|---|
| Devise | XOF par défaut (sans décimales), devise par pharmacie |
| Fuseau | `Africa/Niamey` par défaut |
| Paiements | Mobile money avec opérateur tracé par transaction (rapprochement des relevés opérateurs) |
| Crédit | Ardoise plafonnée par client, encaissement partiel, créances dans le bilan |
| Fiscalité | TVA par produit (exonérations médicaments), facture certifiée SECeF prévue comme port |
| Comptabilité | SYSCOHADA révisé (espace OHADA) : plan minimal, journaux VT/AC/CA/BQ/OD |
| Énergie / réseau | Hub local, PostgreSQL en écriture synchrone (`fsync`, `full_page_writes`), reprise automatique des services, journal de ventes par poste, synchronisation différée sans limite de durée |
| Matériel | PC Windows courants (4 Go RAM suffisent pour le hub), douchette USB clavier, imprimante thermique 80 mm |
| Données de santé | Pseudonymisation des références patient, messagerie sans données identifiantes |

## 3. Feuille de route proposée

| Phase | Contenu | Objectif |
|---|---|---|
| **1 (livrée)** | Hub + cloud, caisse hors-ligne, stocks FEFO, commandes, comptabilité, sessions de caisse, crédit, réseau, supervision, paquet Windows | Pilotes dans 2–3 officines |
| **2 (livrée)** | Bordereaux tiers payant, tableau de bord, inventaire à l'aveugle et contrôle d'intégrité, caisse à l'aveugle et approbations, rapprochement mobile money, registre des ordonnances, substitution par DCI, annuaire public et gardes partagées | Parité et avance sur les PGO installés |
| **2 bis (livrée)** | Impression 58/80 mm et facture A4, ESC/POS direct et tiroir, importation de données et du référentiel | Migration des officines équipées |
| **2 ter** | Connecteur SECeF certifié, étiquettes de prix, grand livre, avoirs sur bordereaux émis, export PDF | Conformité fiscale complète |
| **3 (livrée)** | Vigilance au comptoir (interactions, allergies, profil du patient), posologie par pictogrammes, suivi patients WhatsApp/SMS, rapport du soir au titulaire, saisonnalité de la fiche de commande | Différenciation : ce que les PGO installés ne font pas |
| **4** | Multi-langue, application mobile du titulaire, commandes électroniques vers les grossistes (EDI/API), bourse aux produits à péremption proche entre pharmacies du réseau, base d'interactions sous licence éditeur (en plus de l'amorce) | Réseau et écosystème |
