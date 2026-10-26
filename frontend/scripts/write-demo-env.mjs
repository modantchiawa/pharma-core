// Writes src/environments/environment.demo.ts from the deployment's variables, so the same
// build command serves Vercel previews, the QA environment and local demos:
//   DEMO_API_URL   base URL of the backend API   (default: Render URL for builds, http://localhost:8080/api for `npm run start:demo`)
//   DEMO_WS_URL    STOMP endpoint                (default: derived from DEMO_API_URL, wss://…/ws)
//   DEMO_VERSION   label shown in the UI         (default: VERCEL_GIT_COMMIT_SHA short, or "demo")
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const apiUrl = (
  process.env.DEMO_API_URL ??
  (process.env.npm_lifecycle_event === 'start:demo'
    ? 'http://localhost:8080/api'
    : 'https://pharmacore-demo-api.onrender.com/api')
).replace(/\/$/, '');
const wsUrl =
  process.env.DEMO_WS_URL ?? apiUrl.replace(/^http/, 'ws').replace(/\/api$/, '') + '/ws';
const version =
  process.env.DEMO_VERSION ??
  (process.env.VERCEL_GIT_COMMIT_SHA
    ? `demo-${process.env.VERCEL_GIT_COMMIT_SHA.slice(0, 7)}`
    : 'demo');

const content = `// Généré par scripts/write-demo-env.mjs au moment du build : ne pas modifier à la main.
export const environment = {
  production: true,
  demo: true,
  appVersion: ${JSON.stringify(version)},
  apiUrl: ${JSON.stringify(apiUrl)},
  wsUrl: ${JSON.stringify(wsUrl)},
  oidc: {
    pharmacy: { authority: '', clientId: '' },
    platform: { authority: '', clientId: '' },
    scope: 'openid profile email',
  },
};
`;
const target = resolve(here, '../src/environments/environment.demo.ts');
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, content);
console.log(`environment.demo.ts → api ${apiUrl}, ws ${wsUrl}, version ${version}`);
