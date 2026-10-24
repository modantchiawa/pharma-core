# PharmaCore — Progiciel de Gestion d'Officine multi-pharmacies

Progiciel d'officine pour l'Afrique de l'Ouest : **serveur local par pharmacie (hub Windows)**
synchronisé avec le cloud, caisse multi-postes entièrement utilisable sans Internet, mobile
money et crédit client, stocks par lot (FEFO), fiche de commande du lendemain, comptabilité
SYSCOHADA (résultat, bilan), réseau inter-pharmacies, console de supervision éditeur.

| Dossier | Contenu |
|---|---|
| [`backend/`](backend) | API Java 26 / Spring Boot 4.1.1 : isolation PostgreSQL RLS, OAuth2/JWT multi-émetteurs, ventes idempotentes, réseau, audit chaîné |
| [`frontend/`](frontend) | PWA Angular 22 (signals, zoneless) : caisse local-first sur IndexedDB (Dexie), synchronisation automatique, thème dynamique |
| [`deploy/`](deploy) | PostgreSQL avec les rôles de production, relais STOMP RabbitMQ (cloud) |
| [`packaging/windows/`](packaging/windows) | Paquet d'installation du hub Windows (services, sauvegardes, restauration) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture hub + cloud, synchronisation par propriété des données, sécurité |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Périmètre fonctionnel comparé aux PGO du marché, feuille de route |
| [`docs/WINDOWS.md`](docs/WINDOWS.md) | Installation en officine, pannes (Internet, courant, matériel), sauvegardes |
| [`docs/UX.md`](docs/UX.md) | Principes d'interface et système de design personnalisable |

## Démarrage rapide

```bash
cp deploy/.env.example deploy/.env          # renseigner les mots de passe
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d

cd backend && mvn verify                    # Java 26, Docker requis pour Testcontainers
cd frontend && npm ci && npm test && npm start

# Lancer un hub local (profil hub) au lieu du cloud
PHARMACORE_MODE=HUB SPRING_PROFILES_ACTIVE=hub PHARMACORE_CLOUD_URL=http://localhost:8080 \
DB_URL=jdbc:postgresql://localhost:5433/pharmacore mvn -pl backend spring-boot:run
```

Les variables d'environnement et les choix d'architecture sont détaillés dans
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
