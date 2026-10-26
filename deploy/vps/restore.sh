#!/usr/bin/env bash
# Restauration d'une archive produite par backup.sh : la base est recréée à l'identique, la clé
# de signature remise en place (les postes et hubs appairés restent valides).
#   pharmacore-restore /var/backups/pharmacore/pharmacore-20261026-023000.tar.gz
set -euo pipefail
ARCHIVE="${1:?archive .tar.gz à restaurer}"
INSTALL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${INSTALL_DIR}/deploy/vps/.env"
COMPOSE=(docker compose -f "${INSTALL_DIR}/deploy/vps/docker-compose.yml" --env-file "${ENV_FILE}")
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

read -r -p "Cette opération remplace toutes les données actuelles par ${ARCHIVE}. Continuer ? (oui/non) " answer
[ "${answer}" = "oui" ] || { echo "Abandon."; exit 1; }

tar -C "${WORK}" -xzf "${ARCHIVE}"
echo "==> Arrêt du backend et du web"
"${COMPOSE[@]}" stop web backend

echo "==> Base de données"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'pharmacore' AND pid <> pg_backend_pid();" >/dev/null
"${COMPOSE[@]}" exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS pharmacore;" -c "CREATE DATABASE pharmacore OWNER pharmacore_owner;"
"${COMPOSE[@]}" exec -T postgres pg_restore -U postgres -d pharmacore --no-owner --role=pharmacore_owner --exit-on-error < "${WORK}/pharmacore.dump"

echo "==> Clé de signature"
if [ -d "${WORK}/keys" ] && [ -n "$(ls -A "${WORK}/keys" 2>/dev/null)" ]; then
  "${COMPOSE[@]}" run --rm --no-deps -v "${WORK}/keys:/restore:ro" --entrypoint sh backend -c 'cp -f /restore/* /app/data/keys/'
fi

echo "==> Redémarrage"
"${COMPOSE[@]}" up -d backend web
echo "Restauration terminée."
