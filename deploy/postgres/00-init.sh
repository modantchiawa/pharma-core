#!/bin/sh
# Entry point of the official postgres image: forwards the secrets to init.sql as psql variables.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v owner_password="$PHARMACORE_OWNER_PASSWORD" \
  -v app_password="$PHARMACORE_APP_PASSWORD" \
  -f /docker-entrypoint-initdb.d/init.sql.inc
