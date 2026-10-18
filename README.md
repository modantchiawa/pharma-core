# PharmaCore — Progiciel de Gestion d'Officine multi-pharmacies

Plateforme SaaS multi-tenant pour officines : caisse utilisable hors-ligne, stocks par lot (FEFO),
réseau inter-pharmacies (disponibilité d'urgence, réservations, planning des gardes, messagerie
temps réel), personnalisation de l'identité visuelle par pharmacie et console de supervision
éditeur.

| Dossier | Contenu |
|---|---|
| [`backend/`](backend) | API Java 26 / Spring Boot 4.1.1 : isolation PostgreSQL RLS, OAuth2/JWT multi-émetteurs, ventes idempotentes, réseau, audit chaîné |
| [`frontend/`](frontend) | PWA Angular 22 (signals, zoneless) : caisse local-first sur IndexedDB (Dexie), synchronisation automatique, thème dynamique |
| [`deploy/`](deploy) | PostgreSQL avec les rôles de production, relais STOMP RabbitMQ |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture, diagrammes Mermaid, stratégie multi-tenant, synchronisation hors-ligne, sécurité |

## Démarrage rapide

```bash
cp deploy/.env.example deploy/.env          # renseigner les mots de passe
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d

cd backend && mvn verify                    # Java 26, Docker requis pour Testcontainers
cd frontend && npm ci && npm test && npm start
```

Les variables d'environnement et les choix d'architecture sont détaillés dans
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
