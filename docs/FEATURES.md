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
| Tiers payant : organismes, taux de couverture, bordereaux de facturation aux assureurs | Oui | 🟡 enregistré par vente (compte 4112) ; bordereaux par organisme : phase 2 |
| Annulation / retour avec remise en stock dans le lot d'origine | Oui | ✅ |
| **Sessions de caisse** : fond, mouvements, dépenses, rapport X/Z, écarts | Oui | ✅ |
| Ticket thermique 80 mm aux couleurs de la pharmacie, facture A4 | Oui | ✅ ticket · 🟡 facture A4 : phase 2 |
| **Facture certifiée DGI (SECeF)** | Obligatoire au Niger pour les assujettis TVA | 🟡 port de certification, file de retry, QR/code sur ticket ; connecteur DGI à réaliser avec les spécifications SECeF |
| Afficheur client, tiroir-caisse, imprimante étiquettes | Oui | ⬜ phase 2 (coque Tauri pour ESC/POS brut et tiroir) |
| Fidélité (points) | Souvent | 🟡 points stockés ; règles d'attribution : phase 2 |

### Stocks et approvisionnement

| Fonction | Attendu | PharmaCore |
|---|---|---|
| Gestion par **lot et péremption (FEFO)** | Oui | ✅ |
| Alertes de péremption, produits dormants, ruptures | Oui | ✅ péremptions, écarts hors-ligne · 🟡 dormants : phase 2 |
| Seuils mini / quantité de réappro par produit | Oui | ✅ + jours de couverture et stock de sécurité |
| **Fiche de commande du lendemain** calculée (vitesse de vente, délai fournisseur, en commande, péremptions, ventes perdues) | Oui (souvent sur seuils seuls) | ✅ |
| Fournisseurs (grossistes-répartiteurs), références, prix d'achat, colisage, minimum de commande | Oui | ✅ |
| Envoi de commande : texte WhatsApp / e-mail, impression, CSV | Oui | ✅ texte · 🟡 PDF : phase 2 |
| Réception de livraison lot par lot, mise à jour des prix, suivi des reliquats | Oui | ✅ |
| Inventaire (comptage, écarts, valorisation) | Oui | ✅ ajustement par lot · 🟡 session d'inventaire guidée : phase 2 |
| Transferts entre pharmacies d'un même groupe | Parfois | ✅ réservations réseau ; transfert intra-groupe : phase 2 |
| Catalogue national (CIP/DCI, prix indicatifs, monographies) maintenu par l'éditeur | Rare | ✅ |

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
| Utilisateurs, rôles, matrice de permissions personnalisable | Oui | ✅ 32 permissions, 5 rôles prédéfinis modifiables |
| Connexion mot de passe + **bascule caissier par PIN**, sans Internet | Oui | ✅ |
| Journal d'audit inaltérable | Rare | ✅ chaîne SHA-256 |
| **Sauvegardes** automatiques locales, USB et cloud | Oui | ✅ tâche planifiée + réplique cloud continue |
| Supervision éditeur multi-pharmacies | — | ✅ |
| Personnalisation : logo, couleurs, mentions, ticket ; densité, contraste, taille de texte, mode sombre par poste | Partiel | ✅ |
| Réseau inter-pharmacies : disponibilité d'urgence, réservations, gardes, messagerie | Rare | ✅ (nécessite Internet) |
| Multilingue (français ; haoussa, zarma, wolof) | Français seul | 🟡 français ; infrastructure i18n Angular : phase 3 |
| Installation Windows multi-postes | Oui | ✅ paquet hub (services Windows) + PWA installable |

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
| **2** | Connecteur SECeF, bordereaux tiers payant, facture A4 PDF, tableaux de bord, inventaire guidé, étiquettes de prix, grand livre, coque Tauri (tiroir, ESC/POS) | Parité complète avec les PGO installés |
| **3** | Multi-langue, application mobile du titulaire (chiffres du jour, alertes), commandes électroniques vers les grossistes (EDI/API), base d'interactions médicamenteuses sous licence, SMS clients (ordonnance prête, rappel de traitement) | Différenciation |
