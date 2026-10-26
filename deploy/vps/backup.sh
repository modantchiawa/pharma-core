#!/usr/bin/env bash
# Sauvegarde complète : base PostgreSQL (format custom pg_dump, compressé), clé de signature du
# nœud, fichier .env. Rotation par ancienneté, copie distante facultative par rclone.
set -euo pipefail
INSTALL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${INSTALL_DIR}/deploy/vps/.env"
# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a
COMPOSE=(docker compose -f "${INSTALL_DIR}/deploy/vps/docker-compose.yml" --env-file "${ENV_FILE}")
BACKUP_DIR="${BACKUP_DIR:-/var/backups/pharmacore}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

mkdir -p "${BACKUP_DIR}"
"${COMPOSE[@]}" exec -T postgres pg_dump -U postgres -d pharmacore --format=custom --compress=6 > "${WORK}/pharmacore.dump"
"${COMPOSE[@]}" cp backend:/app/data/keys "${WORK}/keys" >/dev/null 2>&1 || mkdir -p "${WORK}/keys"
cp "${ENV_FILE}" "${WORK}/env"
tar -C "${WORK}" -czf "${BACKUP_DIR}/pharmacore-${STAMP}.tar.gz" pharmacore.dump keys env
chmod 600 "${BACKUP_DIR}/pharmacore-${STAMP}.tar.gz"
find "${BACKUP_DIR}" -name 'pharmacore-*.tar.gz' -mtime "+${KEEP_DAYS}" -delete

if [ -n "${BACKUP_RCLONE_REMOTE:-}" ] && command -v rclone >/dev/null 2>&1; then
  rclone copy "${BACKUP_DIR}/pharmacore-${STAMP}.tar.gz" "${BACKUP_RCLONE_REMOTE}" --quiet
fi
echo "Sauvegarde écrite : ${BACKUP_DIR}/pharmacore-${STAMP}.tar.gz ($(du -h "${BACKUP_DIR}/pharmacore-${STAMP}.tar.gz" | cut -f1))"
