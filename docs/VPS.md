# Déploiement sur un serveur dédié ou VPS

Tout PharmaCore sur une machine : PostgreSQL, backend, frontend et HTTPS automatique, en
conteneurs Docker. Convient à la démonstration, à la recette et à une mise en production de taille
raisonnable (plusieurs dizaines d'officines) ; les serveurs locaux des officines (hubs Windows) se
synchronisent avec cette instance.

## Dimensionnement

| Usage | Machine | Disque |
|---|---|---|
| Démo et recette | 2 vCPU, 4 Go RAM | 40 Go SSD |
| Production jusqu'à ~50 officines | 4 vCPU, 8 Go RAM | 80 Go SSD + sauvegardes externes |

Ubuntu 22.04 ou 24.04 (Debian 12 fonctionne aussi). Ports ouverts : 22, 80, 443 (TCP et UDP pour
HTTP/3). Un nom de domaine pointant sur l'adresse IP (enregistrement A) donne un certificat
Let's Encrypt automatique ; sans domaine, l'accès se fait en HTTP par adresse IP, uniquement pour
une recette interne.

## Installation en une commande

```bash
curl -fsSL https://raw.githubusercontent.com/modantchiawa/pharma-core/main/deploy/vps/install.sh \
  | sudo bash -s -- --domain pharmacore.example.com --email admin@example.com --demo
```

Le script installe Docker, ouvre le pare-feu, clone le dépôt dans `/opt/pharmacore`, génère les
mots de passe dans `deploy/vps/.env` (droits 600), construit les images, démarre les services,
programme la sauvegarde quotidienne à 02 h 30 et crée les commandes `pharmacore-update`,
`pharmacore-backup`, `pharmacore-restore`. À la fin il affiche l'URL, les comptes de démonstration
et le mot de passe de la console éditeur.

Options : `--production` (pas de données de démo, écran de connexion sans encart de test,
console éditeur par OpenID Connect), `--branch <nom>`, `--dir <chemin>`. Sans `--domain`, le site
écoute en HTTP sur l'adresse IP.

### Installation manuelle

```bash
git clone https://github.com/modantchiawa/pharma-core.git /opt/pharmacore && cd /opt/pharmacore
cp deploy/vps/.env.example deploy/vps/.env && nano deploy/vps/.env      # domaine, mots de passe, mode
docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env up -d --build
```

## Ce qui tourne

| Service | Image | Rôle | Exposition |
|---|---|---|---|
| `web` | Caddy 2 + PWA construite | HTTPS, fichiers statiques, relais `/api`, `/ws`, `/actuator/health` vers le backend | 80, 443 |
| `backend` | `deploy/docker/backend.Dockerfile` (Java 26, ZGC) | API, synchronisation des hubs, migrations Flyway, données de démo | interne |
| `postgres` | PostgreSQL 17 | données, rôles de production (`pharmacore_owner`, `pharmacore_app`, `pharmacore_network_reader`), RLS | interne |

Volumes : `pgdata` (base), `keys` (clé de signature des jetons : la perdre invalide les sessions et
les appairages), `caddy_data` (certificats). Les journaux des conteneurs tournent (20 Mo × 5).

## Variables (`deploy/vps/.env`)

| Variable | Rôle |
|---|---|
| `SITE_ADDRESS` | domaine (HTTPS automatique) ou `:80` |
| `PUBLIC_URL` | origine autorisée par l'API ; identique à l'URL du site |
| `ACME_EMAIL` | contact Let's Encrypt |
| `POSTGRES_SUPERUSER_PASSWORD`, `DB_MIGRATION_PASSWORD`, `DB_APP_PASSWORD` | secrets de la base |
| `WEB_BUILD_CONFIG` | `demo` ou `production` (frontend) |
| `DEMO_ENABLED`, `DEMO_PLATFORM_PASSWORD`, `DEMO_ACCOUNT_PASSWORD`, `DEMO_PIN` | mode démonstration (voir `docs/DEMO.md`) |
| `OIDC_PLATFORM_ISSUER`, `OIDC_PHARMACY_ISSUER` | fournisseur d'identité en production |
| `PG_SHARED_BUFFERS`, `PG_MAX_CONNECTIONS`, `DB_POOL_SIZE`, `JAVA_RAM_PERCENT` | réglages mémoire (4 Go : valeurs par défaut ; 8 Go : `512MB`, `120`, `30`, `60`) |
| `BACKUP_DIR`, `BACKUP_KEEP_DAYS`, `BACKUP_RCLONE_REMOTE` | sauvegardes |

Après modification du `.env` : `docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env up -d`
(reconstruire avec `--build` si `WEB_BUILD_CONFIG` change).

## Exploitation

| Besoin | Commande |
|---|---|
| Mettre à jour (sauvegarde, code, images, redémarrage) | `pharmacore-update` ou `pharmacore-update v1.4.0` |
| Sauvegarder maintenant | `pharmacore-backup` → `/var/backups/pharmacore/pharmacore-<date>.tar.gz` |
| Restaurer | `pharmacore-restore /var/backups/pharmacore/pharmacore-<date>.tar.gz` |
| État des services | `docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env ps` |
| Journaux du backend | `docker compose -f deploy/vps/docker-compose.yml --env-file deploy/vps/.env logs -f --tail 200 backend` |
| Santé | `curl -fsS https://<domaine>/actuator/health` |
| Remise à zéro de la démo | `docker compose … down -v` puis `up -d` (la démo se recharge sur base vide) |

Les sauvegardes contiennent la base, la clé de signature et le `.env` : copiez-les hors du serveur
(`BACKUP_RCLONE_REMOTE` vers un stockage S3 compatible, ou `scp` quotidien).

## Hubs des officines

Dans `packaging/windows/install-hub.ps1`, l'URL du cloud est celle du VPS
(`https://pharmacore.example.com`). Le code d'appairage se génère depuis la console éditeur
(supervision › pharmacie › code d'appairage). Les hubs ne nécessitent aucun port entrant : ils
appellent le VPS en HTTPS.

## Passage en production

1. Installer avec `--production` ou passer `WEB_BUILD_CONFIG=production` et `DEMO_ENABLED=false`,
   puis `pharmacore-update`.
2. Brancher un fournisseur OpenID Connect pour la console éditeur (`OIDC_PLATFORM_ISSUER`,
   réalm avec le rôle `SUPER_ADMIN` dans la revendication `platform_roles`) ; les comptes des
   officines restent locaux (mot de passe + PIN).
3. Créer les pharmacies depuis la console, distribuer les codes d'appairage, installer les hubs.
4. Vérifier la sauvegarde distante et tester une restauration sur une machine de recette.
5. Surveiller `/actuator/health` et `/actuator/prometheus` (interne) avec l'outil de votre choix.

## Dépannage

| Symptôme | Piste |
|---|---|
| Certificat non délivré | DNS pas encore propagé ou port 80 fermé ; `docker compose … logs web` |
| Installation interrompue sur « dependency failed to start » | relancer la même commande d'installation (elle conserve le `.env` et reprend) ; le script affiche le journal du backend en cas d'échec |
| `backend` reste `starting` | mots de passe de la base modifiés après la première initialisation ; journal du backend |
| 409 « hub-managed » sur une écriture | l'officine est gérée par son hub : l'opération se fait au comptoir, pas depuis le cloud |
| Page blanche après mise à jour | vider le cache du service worker (recharger deux fois) ; `ngsw.json` est servi sans cache |
