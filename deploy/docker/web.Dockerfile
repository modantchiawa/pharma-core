# Frontend PharmaCore (PWA Angular) servi par Caddy, avec HTTPS automatique et relais de
# l'API et des WebSockets vers le backend. Contexte de construction : la racine du dépôt.
#
#   --build-arg BUILD_CONFIG=demo        encart « comptes de démonstration » sur l'écran de connexion
#   --build-arg BUILD_CONFIG=production  sans encart (officines réelles)
#   --build-arg GIT_SHA=<sha>            version affichée dans l'application
FROM node:24-bookworm-slim AS build
ARG BUILD_CONFIG=production
ARG GIT_SHA=local
ENV NG_CLI_ANALYTICS=false CI=true
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# Même origine que l'API derrière Caddy : URLs relatives.
RUN if [ "$BUILD_CONFIG" = "demo" ]; then \
      DEMO_API_URL=/api DEMO_WS_URL=/ws DEMO_VERSION="demo-$GIT_SHA" npm run build:demo; \
    else \
      npx ng build --configuration production; \
    fi

FROM caddy:2-alpine
COPY deploy/vps/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /build/dist/frontend/browser /srv/web
