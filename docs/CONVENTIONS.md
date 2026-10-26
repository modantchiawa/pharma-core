# Conventions de développement

Règles appliquées à tout nouveau module (backend, base, frontend). Elles complètent
`ARCHITECTURE.md`.

## Base de données (Flyway)

* Une migration par module, numérotée dans l'ordre d'arrivée. Jamais de modification d'une
  migration déjà fusionnée sur `main`.
* Toute table portant des données d'officine a une colonne `tenant_id uuid NOT NULL REFERENCES
  tenant (id)` et reçoit :
  * `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO pharmacore_app` (ou `SELECT, INSERT` seul
    pour une table en écriture seule : audit, mouvements, journal) ;
  * `ENABLE ROW LEVEL SECURITY` et la politique `p_tenant_isolation`
    (`tenant_id = app_current_tenant() OR app_is_platform_admin()`), cf. V4 ;
  * son enregistrement dans la synchronisation : `INSERT INTO sync_table (table_name, owner_side,
    scope, origin_column, apply_order)` puis `SELECT sync_track('<table>')`. `owner_side = 'HUB'`
    pour l'exploitation de l'officine, `'CLOUD'` pour le référentiel partagé ; `apply_order` respecte
    les clés étrangères (parents avant enfants).
* Toute clé étrangère est déclarée `DEFERRABLE INITIALLY IMMEDIATE` (la réplication valide un lot
  entier à la validation).
* Index obligatoire sur `(tenant_id, …)` pour les requêtes de liste et sur `(status, created_at)`
  pour toute file de traitement.
* Nouvelle permission : ajoutée à l'énumération `Permission`, insérée dans `permission` et attribuée
  aux rôles prédéfinis dans la même migration (le démarrage vérifie la cohérence).

## Backend

* Hexagonal : `domain` (records, interfaces scellées, règles pures, sans Spring), `application`
  (services transactionnels), `persistence` (entités JPA, dépôts), `web` (contrôleurs).
* Toute requête de lecture multi-lignes en SQL explicite (`JdbcClient` ou `@Query`), jamais de
  chargement paresseux en boucle.
* Paramètre SQL pouvant être `NULL` : cast explicite (`:param::uuid`, `:param::date`, `:param::text`).
* Mise à jour de solde ou de quantité : verrou (`@Version` ou `PESSIMISTIC_WRITE` via `StockLedger`).
* Chaque action métier écrit un événement d'audit (`AuditTrail.record`).
* Argent en centimes (`long`), devise de l'officine ; dates métier en `LocalDate`, instants en
  `Instant` ; `Clock` injecté.
* Erreurs métier : `BusinessRuleException(code, message)` → 422 Problem Details ; introuvable :
  `NotFoundException`.
* Pas de `synchronized` ; `ReentrantLock` si besoin. Pas de `// TODO`, pas de code de démonstration.
* Tests : JUnit 5 + AssertJ ; règles du domaine en unitaire ; parcours bout en bout en `*IT`
  (`IntegrationTest`, Testcontainers PostgreSQL) via `onboardPharmacy`, `createProduct`,
  `receiveLot`, `call`, `read`.

## Frontend

* Composants autonomes, `ChangeDetectionStrategy.OnPush`, signaux, `inject()`.
* Montants via `MoneyPipe` et `CurrencyService.parse` ; dates via `DatePipe`.
* Permissions : `permissionGuard` sur la route, `*appHasPermission` dans la vue, entrée de menu
  dans `shell.ts`.
* Fonctions réseau (annuaire, gardes, transferts, messagerie) : appels vers le cloud
  (`networkApiUrl`), tout le reste vers le serveur local (`apiUrl`).
* Types partagés dans `core/api/models.ts`. `npx prettier --write` avant de committer.
