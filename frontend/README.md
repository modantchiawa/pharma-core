# PharmaCore — PWA Angular 22

Caisse et back-office de l'officine, conçus *local-first* :

* chaque vente est d'abord validée dans IndexedDB (journal + décrément de stock dans une même
  transaction Dexie), puis synchronisée par `SaleOutboxService` (lots, Web Locks, backoff
  exponentiel, réponses `ACCEPTED` / `DUPLICATE` / `REJECTED` / `RETRY`) ;
* le catalogue national et les prix de la pharmacie sont tirés en incrémental par
  `CatalogSyncService` ;
* le service worker (`src/pwa/sw.js`) étend `ngsw` avec Background Sync ;
* `BrandingService` applique les couleurs, le logo et les mentions de la pharmacie via des signals
  et des variables CSS consommées par Tailwind 4.

```bash
npm ci
npm start          # http://localhost:4200 (API attendue sur http://localhost:8080)
npm test           # Vitest
npm run build      # build production + service worker
```

Node.js ≥ 22.22.3 est requis par Angular CLI 22. Configuration OIDC et URLs :
`src/environments/`.
