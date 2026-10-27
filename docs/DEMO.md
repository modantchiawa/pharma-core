# Environnement de démonstration et de recette

Un frontend sur **Vercel** et un backend conteneurisé (Render par défaut, ou tout hôte Docker) en
**mode démo** : deux pharmacies de Niamey préchargées, des comptes connus, une console éditeur
ouverte par mot de passe. Les données sont rechargées à chaque redéploiement avec une base vide.

> **Serveur dédié ou VPS** : le chemin recommandé est `docs/VPS.md` (`install.sh --demo`), qui
> déploie frontend, backend et base sur une seule machine avec HTTPS. Ce document détaille le mode
> démonstration lui-même et les variantes hébergées (Vercel + Render).

## Architecture de la démo

| Composant | Où | Rôle |
|---|---|---|
| Frontend (PWA Angular) | Vercel, dossier `frontend/` | Caisse, back-office, page publique `/public`, console éditeur `/platform` |
| Backend (Spring Boot 4, Java 26) | Render (web service Docker) ou `docker compose` | API, synchronisation, données de démo |
| PostgreSQL 17 | Render (base managée) ou conteneur | Rôles de production, RLS |

Le backend tourne en mode **CLOUD** : les pharmacies de démo sont « gérées dans le cloud », tout
est modifiable depuis le navigateur, sans serveur local. Le mode hub (serveur Windows par officine)
se démontre séparément avec `packaging/windows`.

## 0. Tester en local

### Tout en conteneurs (identique au serveur)

Prérequis : Docker Desktop (Windows, macOS) ou Docker Engine + plugin compose, 6 Go de RAM libres.

```bash
cp deploy/vps/.env.example deploy/vps/.env
# dans .env : SITE_ADDRESS=:80  PUBLIC_URL=http://localhost:8088  HTTP_PORT=8088  HTTPS_PORT=8443
#             WEB_BUILD_CONFIG=demo  DEMO_ENABLED=true  DEMO_PLATFORM_PASSWORD=<au choix>
docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env up -d --build
docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env logs -f backend   # attendre « Démonstration chargée »
```

Puis <http://localhost:8088>. Première construction : 5 à 10 minutes (Maven et npm). Arrêt :
`docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env down` ; remise à zéro
des données : ajouter `-v`.

### Mode développeur (rechargement à chaud)

Prérequis : Java 26, Maven 3.9, Node 24, Docker pour PostgreSQL.

```bash
cp deploy/.env.example deploy/.env                      # mots de passe de la base locale
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d postgres

cd backend
DEMO_ENABLED=true DEMO_PLATFORM_PASSWORD=demo \
DB_MIGRATION_PASSWORD=<DB_MIGRATION_PASSWORD du .env> DB_APP_PASSWORD=<DB_APP_PASSWORD du .env> \
mvn spring-boot:run                                     # http://localhost:8080, données de démo au premier démarrage

cd ../frontend && npm ci && npm run start:demo          # http://localhost:4200, API sur localhost:8080
```

Sous PowerShell, les variables se posent avec `$env:DEMO_ENABLED="true"` avant `mvn spring-boot:run`.
`npm start` (sans `:demo`) lance la configuration de développement classique, sans l'encart des
comptes de test ; les mêmes comptes fonctionnent en saisissant l'identifiant de pharmacie `sahel`.

Tests automatisés : `cd backend && mvn verify` (Testcontainers démarre PostgreSQL tout seul, dont
`DemoIT` qui rejoue l'amorçage de la démo) et `cd frontend && npm test`.

## 1. Déployer le backend

### Option A — Render (blueprint)

1. Sur Render, **New › Blueprint**, choisir ce dépôt : `render.yaml` crée le service
   `pharmacore-demo-api` et la base `pharmacore-demo-db`. Le premier déploiement échoue au
   démarrage tant que les rôles PostgreSQL n'existent pas : c'est attendu.
2. Récupérer l'**External Database URL** de la base, puis créer les rôles (une fois) :

   ```bash
   psql "<External Database URL>" \
     -v owner_password='<mot de passe migration>' \
     -v app_password='<mot de passe application>' \
     -f deploy/demo/init-roles.sql
   ```

3. Dans le service, renseigner les variables marquées `sync: false` :

   | Variable | Valeur |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<hôte>:5432/pharmacore?sslmode=require` (hôte interne de la base) |
   | `DB_MIGRATION_PASSWORD` | `<mot de passe migration>` |
   | `DB_APP_PASSWORD` | `<mot de passe application>` |
   | `ALLOWED_ORIGINS` | l'URL Vercel du frontend, par exemple `https://pharmacore-demo.vercel.app` (plusieurs valeurs séparées par des virgules, prévisualisations comprises) |
   | `DEMO_PLATFORM_PASSWORD` | générée par Render : à lire dans l'onglet Environment et à transmettre aux testeurs |

4. **Manual Deploy › Deploy latest commit**. Au démarrage, Flyway crée le schéma, puis le mode
   démo charge les deux pharmacies (une vingtaine de secondes). Vérifier :
   `https://<service>.onrender.com/actuator/health` puis
   `https://<service>.onrender.com/api/public/directory/pharmacies?city=Niamey`.

Le disque `pharmacore-keys` conserve la clé de signature des jetons entre deux redéploiements ; sans
lui, les sessions ouvertes seraient invalidées à chaque déploiement.

### Option B — Docker Compose (VPS, poste de recette)

```bash
cp deploy/demo/.env.example deploy/demo/.env      # mots de passe, ALLOWED_ORIGINS
docker compose -f deploy/demo/docker-compose.yml --env-file deploy/demo/.env up -d --build
```

Le backend écoute sur `http://<hôte>:8080`. Pour servir aussi le frontend depuis ce conteneur,
construire la PWA (`cd frontend && DEMO_API_URL=http://<hôte>:8080/api npm run build:demo`) et
monter `frontend/dist/frontend/browser` sur `/app/web` (variable `WEB_DIR`).

## 2. Déployer le frontend sur Vercel

1. **Add New › Project**, importer le dépôt, **Root Directory = `frontend`**. Le fichier
   `frontend/vercel.json` fixe la commande de build (`npm run build:demo`), le dossier de sortie
   (`dist/frontend/browser`), la réécriture SPA et les en-têtes du service worker.
2. Variables d'environnement du projet (Production et Preview) :

   | Variable | Valeur |
   |---|---|
   | `DEMO_API_URL` | `https://<service>.onrender.com/api` |
   | `DEMO_WS_URL` | `wss://<service>.onrender.com/ws` (facultatif, déduit de `DEMO_API_URL`) |

3. Déployer. Chaque pull request produit une URL de prévisualisation : ajouter le domaine
   `https://*.vercel.app` ou l'URL exacte à `ALLOWED_ORIGINS` du backend pour que les
   prévisualisations puissent appeler l'API.

Le script `frontend/scripts/write-demo-env.mjs` génère `environment.demo.ts` au build ; en local,
`DEMO_API_URL=http://localhost:8080/api npm run build:demo` produit la même PWA.

## 3. Comptes de démonstration

| Rôle | Pharmacie (identifiant) | E-mail | Mot de passe | PIN |
|---|---|---|---|---|
| Titulaire | `sahel` | `titulaire@sahel.demo` | `Demo2026!` | `1234` |
| Caissier | `sahel` | `caisse@sahel.demo` | `Demo2026!` | `1234` |
| Adjoint | `sahel` | `adjoint@sahel.demo` | `Demo2026!` | `1234` |
| Préparatrice | `sahel` | `preparatrice@sahel.demo` | `Demo2026!` | `1234` |
| Titulaire | `lacouroussou` | `titulaire@lacouroussou.demo` | `Demo2026!` | `1234` |
| Caissier | `lacouroussou` | `caisse@lacouroussou.demo` | `Demo2026!` | `1234` |
| Console éditeur | — | bouton « Console éditeur » | `DEMO_PLATFORM_PASSWORD` | — |

L'écran de connexion affiche ces comptes dans un encart « Comptes de démonstration » ; un
clic pré-remplit le formulaire. Le mot de passe des comptes se change avec `DEMO_ACCOUNT_PASSWORD`,
le PIN avec `DEMO_PIN`.

## 4. Données préchargées

* 27 produits du référentiel avec DCI, dosage et prix XOF (paracétamol, amoxicilline,
  artéméther-luméfantrine, metformine, amlodipine, tramadol…), dont des équivalents de même DCI.
* Par pharmacie : stock par lot (quelques lots périmant sous 60 jours, quelques produits en rupture
  pour la substitution), seuils de réapprovisionnement, deux grossistes (Laborex Niger, Copharni)
  avec références et prix d'achat, trois clients dont une cliente à crédit assurée à 80 % par
  « NIA Assurances », deux semaines de ventes (espèces, Airtel Money, Zamani Cash, une vente à
  crédit, des ordonnances), une session de caisse ouverte sur le poste `DEMO-1`, dix emplacements
  (rayons A à C, tiroir des injectables, réfrigérateur pour l'insuline, coffre pour le tramadol,
  comptoir, réserve) où chaque produit est rangé, et des photos de boîte pour les 16 produits
  génériques les plus vendus (images dessinées pour la démonstration, sans marque ni laboratoire).
* Postes de caisse dans chaque état de synchronisation : à `sahel`, `DEMO-1` synchronisé, `CAISSE-2`
  avec 3 ventes en attente, `CAISSE-3` hors ligne depuis 2 jours (1 vente bloquée sur le poste) et
  `BUREAU` jamais synchronisé ; à `lacouroussou`, `DEMO-1` synchronisé et `CAISSE-2` hors ligne. Les
  postes « allumés » renvoient un signal chaque minute pour que le tableau reste le même pendant
  toute la démo. Chaque navigateur qui ouvre la caisse apparaît en plus comme un poste (identifiant
  aléatoire) : la console et la page « Serveur local » permettent de les oublier une fois hors ligne.
* Abonnements : `sahel` en formule Pro payée sept mois d'avance (trois règlements), `lacouroussou`
  en formule Essentiel dont la période payée se termine dans cinq jours (bandeau « Abonnement : plus
  que … jours » dès la connexion).
* Contrôle interne (`sahel`) : l'adjoint a annulé 6 de ses 27 ventes du soir 25 minutes après
  encaissement (chacune sous le seuil, le taux le signale), déclaré trois sorties de stock (périmé,
  perte, vol contre-signé par la titulaire), vendu à 23 h 20 hors garde ; la caisse `DEMO-2` d'hier a
  été clôturée 2 500 F court ; le contrôle du jour d'hier a trouvé 2 gélules de tramadol manquantes
  au coffre. Les alertes correspondantes sont dans la cloche.
* Réseau « Gardes Niamey · groupe I » avec les deux pharmacies, gardes de nuit de la semaine et garde
  du dimanche, publication publique activée, fiches publiques avec coordonnées GPS.

## 5. Parcours de recette

| # | Scénario | Attendu |
|---|---|---|
| 1 | Connexion caissier `sahel`, vente de 2 produits en espèces, ticket | Vente créée, stock décrémenté FEFO, ticket 80 mm imprimable |
| 2 | Couper le réseau (DevTools › Offline), vendre, rétablir | Vente conservée, synchronisée, visible dans Caisse |
| 3 | Bascule par PIN entre caissier et titulaire | Deux taps, sans mot de passe |
| 4 | Vente à crédit pour Mme Hadiza Moussa au-delà du plafond | Refus `CREDIT_LIMIT_EXCEEDED` |
| 5 | Vente avec tiers payant NIA 80 % | Part assureur calculée, reste à payer, bordereau générable dans Tiers payant |
| 6 | Produit en rupture au comptoir › Équivalents | Substituts de même DCI avec stock et prix |
| 7 | Clôture de caisse par coupures avec un écart | Rapprochement par mode, approbation demandée au-delà du seuil |
| 8 | Annulation d'une vente au-dessus du seuil par le caissier | PIN du titulaire exigé |
| 9 | Stocks › Inventaire : compter un rayon, valider en titulaire | Écarts appliqués, mouvements `COUNT_ADJUSTMENT` |
| 10 | Commandes › Fiche du lendemain › « Générer les commandes » (tous fournisseurs) | Un brouillon par fournisseur (Copharni signalé sous son minimum), thermomètre « sans fournisseur » à associer ; regénérer met à jour les brouillons |
| 10 bis | Commandes › Suivi : commande Copharni en retard de 2 j › Réceptionner une partie, puis Solder | Frise créée → envoyée → prévue → réception → soldée ; lots en stock, achat comptabilisé ; alerte « en retard » sur Pilotage |
| 11 | Compta › Résultat et bilan du mois | Chiffres cohérents avec Pilotage |
| 12 | Import › essai à blanc d'un CSV de clients puis import | Rapport ligne par ligne, pas de doublon au second passage |
| 13 | Page publique `/public` : « paracetamol », Niamey | Les deux pharmacies, niveau sans quantité ; gardes du soir |
| 14 | Réseau › « Qui l'a ? » depuis `lacouroussou` | Stock de `sahel` plafonné à 20 |
| 15 | Console éditeur : liste des pharmacies, import du référentiel (essai à blanc) | Deux pharmacies actives, aperçu du fichier |
| 15 bis | Console éditeur › clic sur « Pharmacie du Sahel » | Fiche : serveur local, abonnement, 4 postes (synchronisé, 3 ventes en attente, hors ligne depuis 2 j, jamais synchronisé), comptes avec rôles et dernière connexion ; « Oublier » sur le poste hors ligne |
| 15 ter | Titulaire `sahel` › Administration › Serveur local | Mêmes postes, « ce poste » repéré, oubli des postes hors ligne sans vente en attente |
| 16 | Stock et achats › Emplacements : ouvrir B2, renommer A1, désactiver un rayon non vide | Produits par case ; l'emplacement renommé apparaît au comptoir après synchronisation ; désactivation refusée tant que les produits ne sont pas déplacés |
| 17 | Console éditeur › Abonnements : `lacouroussou` › règlement de 3 mois en mobile money | Montant prérempli (formule × mois), période prolongée depuis la fin de la période payée, licence réémise, bandeau disparu chez la pharmacie |
| 18 | Console éditeur : suspendre `sahel`, puis vendre au comptoir et consulter le stock et l'ordonnancier ; réactiver | Vente refusée (bandeau rouge, bouton désactivé, 423 côté serveur), consultation et exports ouverts ; tout revient à la réactivation |
| 19 | Stocks › clic sur un produit sans photo › « Prendre une photo » (mobile) ou glisser une image | Photo recadrée au carré ; miniature visible au comptoir, dans les stocks et les commandes, puis hors ligne |
| 20 | Rôles et droits › délais 1 min / 5 min ; panier en cours, ne plus toucher au poste | Avertissement à 30 s, écran verrouillé, panier intact après le PIN `1234` ; « Changer d'utilisateur » vers le caissier garde le panier ; 5 codes faux ferment la session |
| 20 bis | Comptoir avec un panier › cadenas (ou Ctrl L), puis outils de développement : supprimer l'écran verrouillé, rejouer `fetch('…/api/v1/inventory/stocks', {headers: {Authorization: 'Bearer <jeton copié avant>'}})`, effacer `pharmacore.locked` et recharger | Page vide derrière la carte (aucune donnée dans le DOM), jeton absent de l'onglet, **423** `session-locked` sur toute API (le rafraîchissement ne donne qu'un jeton « verrouillé »), l'écran revient après rechargement ; PIN `1234` : page et panier restaurés, journal `SESSION_LOCKED` / `SESSION_UNLOCKED` dans l'audit |
| 21 | Gestion › Contrôle interne en titulaire `sahel` | Dr Ibrahim Souley signalé (annulations après paiement, taux, hors horaires) avec « pourquoi ce signalement » et les tickets en cause ; onglet Stupéfiants : solde théorique du tramadol, inventaire −2 avec les deux signatures |
| 22 | Pilotage › Contrôle du jour › Démarrer, compter, valider | Comptage à l'aveugle des produits tirés ; sur un stupéfiant, la validation demande l'e-mail et le PIN d'une seconde personne (`adjoint@sahel.demo`) |
| 23 | Stocks › Sortie sur Augmentin (vol, 2 boîtes) en titulaire | Seconde personne exigée au-delà de 10 000 F au coût ; la titulaire elle-même est refusée, l'adjoint est accepté ; alerte poussée dans la cloche |

Les anomalies se signalent avec l'URL, le compte utilisé, l'heure (UTC) et, si possible, le numéro de
ticket ; l'audit (`AUDIT_VIEW`) permet de retrouver chaque action.

## 6. Remettre la démo à zéro

Render : **Databases › pharmacore-demo-db › Danger zone › Reset**, rejouer `init-roles.sql`, puis
redéployer. Compose : `docker compose -f deploy/demo/docker-compose.yml down -v && … up -d --build`.

## Limites de la démo

* Le mode démo ne doit jamais être activé sur un nœud de production : il expose une entrée par mot
  de passe à la console éditeur et des comptes publics.
* Vercel ne relaie pas les WebSockets : la messagerie temps réel passe directement par
  `DEMO_WS_URL` (le backend).
* L'instance Render gratuite ou de démarrage s'endort après inactivité : la première requête peut
  prendre une trentaine de secondes.
* L'impression ESC/POS directe suppose une imprimante joignable depuis le serveur ; en démo, utiliser
  le mode navigateur (par défaut).
