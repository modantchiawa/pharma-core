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
| Ticket thermique **58 mm (type TPE) ou 80 mm** aux couleurs de la pharmacie, **facture A4** (montant en lettres, TVA, client, police) | Oui | ✅ impression navigateur ou ESC/POS direct (USB partagée, réseau 9100), réglages par poste, réimpression, tiroir-caisse |
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
| Fournisseurs (grossistes-répartiteurs), références, prix d'achat, colisage, minimum de commande | Oui | ✅ |
| Envoi de commande : texte WhatsApp / e-mail, impression, CSV | Oui | ✅ WhatsApp (wa.me), e-mail, bon de commande A4 imprimé · 🟡 PDF : phase 2 |
| Génération directe d'une commande par fournisseur depuis la fiche, brouillons mis à jour sans doublon | Partiel | ✅ |
| Suivi des commandes : retards, livraisons du jour, montant en attente, report de date, solde des reliquats | Partiel | ✅ |
| Réception de livraison lot par lot, mise à jour des prix, suivi des reliquats | Oui | ✅ depuis la commande, pré-remplie, scan GS1 |
| Inventaire (comptage, écarts, valorisation) | Oui | ✅ session d'inventaire **à l'aveugle** par scan ou saisie, attendu masqué au compteur, validation par un pharmacien, écarts audités |
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
| Utilisateurs, rôles, matrice de permissions personnalisable | Oui | ✅ 43 permissions, 5 rôles prédéfinis modifiables |
| Connexion mot de passe + **bascule caissier par PIN**, sans Internet | Oui | ✅ |
| Journal d'audit inaltérable | Rare | ✅ chaîne SHA-256 |
| **Sauvegardes** automatiques locales, USB et cloud | Oui | ✅ tâche planifiée + réplique cloud continue |
| Supervision éditeur multi-pharmacies | — | ✅ |
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
| **3** | Multi-langue, application mobile du titulaire (chiffres du jour, alertes), commandes électroniques vers les grossistes (EDI/API), base d'interactions médicamenteuses sous licence, SMS clients (ordonnance prête, rappel de traitement) | Différenciation |
