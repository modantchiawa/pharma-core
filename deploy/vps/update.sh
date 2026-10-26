#!/usr/bin/env bash
# Mise à jour de PharmaCore sur le VPS : sauvegarde, récupération du code, reconstruction des
# images, redémarrage sans interruption de PostgreSQL, nettoyage des anciennes images.
#   pharmacore-update            # branche suivie
#   pharmacore-update v1.4.0     # tag ou branche précis
set -euo pipefail
INSTALL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${INSTALL_DIR}/deploy/vps/.env"
COMPOSE=(docker compose -f "${INSTALL_DIR}/deploy/vps/docker-compose.yml" --env-file "${ENV_FILE}")
REF="${1:-}"

cd "${INSTALL_DIR}"
echo "==> Sauvegarde avant mise à jour"
"${INSTALL_DIR}/deploy/vps/backup.sh"

echo "==> Code"
git fetch --depth 1 origin ${REF:+"${REF}"}
if [ -n "${REF}" ]; then
  git checkout -q -B deploy FETCH_HEAD
else
  git reset -q --hard FETCH_HEAD
fi
APP_VERSION="$(git rev-parse --short HEAD)"
sed -i "s|^APP_VERSION=.*|APP_VERSION=${APP_VERSION}|" "${ENV_FILE}"

echo "==> Construction des images ${APP_VERSION}"
"${COMPOSE[@]}" build --pull backend web

echo "==> Redémarrage (les migrations Flyway s'appliquent au démarrage du backend)"
"${COMPOSE[@]}" up -d --no-deps backend
for _ in $(seq 1 60); do
  if "${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' | grep -q '^backend healthy'; then
    break
  fi
  sleep 5
done
"${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' | grep -q '^backend healthy' || {
  echo "Le backend ne répond pas : journal ci-dessous" >&2
  "${COMPOSE[@]}" logs --tail 100 backend >&2
  exit 1
}
"${COMPOSE[@]}" up -d --no-deps web

echo "==> Nettoyage"
docker image prune -f --filter "until=168h" >/dev/null
echo "PharmaCore ${APP_VERSION} déployé."
