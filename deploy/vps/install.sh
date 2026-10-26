#!/usr/bin/env bash
# Installation de PharmaCore sur un VPS Ubuntu/Debian vierge (root ou sudo) :
# Docker, pare-feu, dépôt, secrets, premier démarrage, sauvegarde quotidienne.
#
#   curl -fsSL https://raw.githubusercontent.com/modantchiawa/pharma-core/main/deploy/vps/install.sh | sudo bash -s -- \
#       --domain pharmacore.example.com --email admin@example.com [--demo|--production] [--branch main]
#
# Sans --domain : accès HTTP par adresse IP (SITE_ADDRESS=:80), pour une recette interne.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/modantchiawa/pharma-core.git}"
INSTALL_DIR="${INSTALL_DIR:-/opt/pharmacore}"
BRANCH="main"
DOMAIN=""
EMAIL=""
MODE="demo"

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --email) EMAIL="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --demo) MODE="demo"; shift ;;
    --production) MODE="production"; shift ;;
    --dir) INSTALL_DIR="$2"; shift 2 ;;
    *) echo "Option inconnue : $1" >&2; exit 2 ;;
  esac
done

if [ "$(id -u)" -ne 0 ]; then
  echo "Lancez ce script en root (sudo)." >&2
  exit 1
fi

log() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
secret() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }

log "Paquets système"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl git gnupg ufw fail2ban >/dev/null

if ! command -v docker >/dev/null 2>&1; then
  log "Installation de Docker"
  install -m 0755 -d /etc/apt/keyrings
  . /etc/os-release
  curl -fsSL "https://download.docker.com/linux/${ID}/gpg" | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
  systemctl enable --now docker
fi

log "Pare-feu : SSH, HTTP, HTTPS"
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 443/udp >/dev/null
ufw --force enable >/dev/null
systemctl enable --now fail2ban >/dev/null 2>&1 || true

log "Dépôt dans ${INSTALL_DIR} (branche ${BRANCH})"
if [ -d "${INSTALL_DIR}/.git" ]; then
  git -C "${INSTALL_DIR}" fetch --depth 1 origin "${BRANCH}"
  git -C "${INSTALL_DIR}" checkout -q -B "${BRANCH}" "origin/${BRANCH}"
else
  git clone --depth 1 --branch "${BRANCH}" "${REPO_URL}" "${INSTALL_DIR}"
fi

ENV_FILE="${INSTALL_DIR}/deploy/vps/.env"
if [ ! -f "${ENV_FILE}" ]; then
  log "Secrets générés dans ${ENV_FILE}"
  cp "${INSTALL_DIR}/deploy/vps/.env.example" "${ENV_FILE}"
  if [ -n "${DOMAIN}" ]; then
    SITE_ADDRESS="${DOMAIN}"
    PUBLIC_URL="https://${DOMAIN}"
  else
    SITE_ADDRESS=":80"
    PUBLIC_URL="http://$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')"
  fi
  sed -i \
    -e "s|^SITE_ADDRESS=.*|SITE_ADDRESS=${SITE_ADDRESS}|" \
    -e "s|^PUBLIC_URL=.*|PUBLIC_URL=${PUBLIC_URL}|" \
    -e "s|^ACME_EMAIL=.*|ACME_EMAIL=${EMAIL}|" \
    -e "s|^POSTGRES_SUPERUSER_PASSWORD=.*|POSTGRES_SUPERUSER_PASSWORD=$(secret)|" \
    -e "s|^DB_MIGRATION_PASSWORD=.*|DB_MIGRATION_PASSWORD=$(secret)|" \
    -e "s|^DB_APP_PASSWORD=.*|DB_APP_PASSWORD=$(secret)|" \
    -e "s|^DEMO_PLATFORM_PASSWORD=.*|DEMO_PLATFORM_PASSWORD=$(secret | head -c 16)|" \
    "${ENV_FILE}"
  if [ "${MODE}" = "production" ]; then
    sed -i -e "s|^WEB_BUILD_CONFIG=.*|WEB_BUILD_CONFIG=production|" -e "s|^DEMO_ENABLED=.*|DEMO_ENABLED=false|" "${ENV_FILE}"
  fi
  chmod 600 "${ENV_FILE}"
else
  log "Fichier ${ENV_FILE} conservé"
fi

log "Construction et démarrage (plusieurs minutes la première fois)"
cd "${INSTALL_DIR}"
APP_VERSION="$(git rev-parse --short HEAD)"
sed -i "s|^APP_VERSION=.*|APP_VERSION=${APP_VERSION}|" "${ENV_FILE}"
if ! docker compose -f deploy/vps/docker-compose.yml --env-file "${ENV_FILE}" up -d --build --remove-orphans; then
  echo "Le démarrage a échoué : état des services et journal du backend ci-dessous." >&2
  docker compose -f deploy/vps/docker-compose.yml --env-file "${ENV_FILE}" ps >&2 || true
  docker compose -f deploy/vps/docker-compose.yml --env-file "${ENV_FILE}" logs --tail 80 backend >&2 || true
  echo "Après correction, relancez la même commande d'installation : elle reprend là où elle s'est arrêtée." >&2
  exit 1
fi

log "Sauvegarde quotidienne à 02:30 (cron) et scripts d'exploitation"
chmod +x deploy/vps/*.sh
cat > /etc/cron.d/pharmacore-backup <<EOF
30 2 * * * root ${INSTALL_DIR}/deploy/vps/backup.sh >> /var/log/pharmacore-backup.log 2>&1
EOF
ln -sf "${INSTALL_DIR}/deploy/vps/update.sh" /usr/local/bin/pharmacore-update
ln -sf "${INSTALL_DIR}/deploy/vps/backup.sh" /usr/local/bin/pharmacore-backup
ln -sf "${INSTALL_DIR}/deploy/vps/restore.sh" /usr/local/bin/pharmacore-restore

log "Attente du backend"
for _ in $(seq 1 60); do
  if docker compose -f deploy/vps/docker-compose.yml --env-file "${ENV_FILE}" ps --format '{{.Service}} {{.Health}}' | grep -q '^backend healthy'; then
    break
  fi
  sleep 5
done

PUBLIC_URL="$(grep '^PUBLIC_URL=' "${ENV_FILE}" | cut -d= -f2-)"
log "PharmaCore est en ligne : ${PUBLIC_URL}"
if grep -q '^DEMO_ENABLED=true' "${ENV_FILE}"; then
  echo "Mode démonstration : comptes titulaire@sahel.demo / caisse@sahel.demo (et lacouroussou), mot de passe $(grep '^DEMO_ACCOUNT_PASSWORD=' "${ENV_FILE}" | cut -d= -f2-), PIN $(grep '^DEMO_PIN=' "${ENV_FILE}" | cut -d= -f2-)"
  echo "Console éditeur : mot de passe $(grep '^DEMO_PLATFORM_PASSWORD=' "${ENV_FILE}" | cut -d= -f2-)"
fi
echo "Mise à jour : pharmacore-update · sauvegarde : pharmacore-backup · restauration : pharmacore-restore <archive>"
