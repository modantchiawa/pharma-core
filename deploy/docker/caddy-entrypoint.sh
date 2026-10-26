#!/bin/sh
# Contact Let's Encrypt facultatif : une adresse invalide (« noreply@localhost », domaine sans
# point) fait refuser le compte ACME et aucun certificat n'est délivré. Sans adresse valide, le
# certificat est demandé sans e-mail de contact.
set -eu
case "${ACME_EMAIL:-}" in
  *@*.*) ACME_EMAIL_DIRECTIVE="email ${ACME_EMAIL}" ;;
  *) ACME_EMAIL_DIRECTIVE="" ;;
esac
export ACME_EMAIL_DIRECTIVE
exec "$@"
