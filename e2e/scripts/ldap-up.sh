#!/usr/bin/env bash
# Starts the OpenLDAP directory of compose.ldap.yml (e2e-05 check 2) next to the running Jenkins.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
mkdir -p out/ldap
sed -e "s|@BC_ADMIN_PASSWORD@|$BC_ADMIN_PASSWORD|" -e "s|@BC_REQUESTER_PASSWORD@|$BC_REQUESTER_PASSWORD|" \
    -e "s|@BC_APPROVER_PASSWORD@|$BC_APPROVER_PASSWORD|" -e "s|@BC_OTHER_PASSWORD@|$BC_OTHER_PASSWORD|" \
    ldap/bootstrap.ldif.template > out/ldap/bootstrap.ldif
chmod 644 out/ldap/bootstrap.ldif
docker compose -f docker-compose.yml -f compose.ldap.yml up -d openldap
for _ in $(seq 1 60); do
  if docker exec batch-control-e2e-ldap ldapsearch -x -H ldap://localhost:1389 -b dc=e2e,dc=local -D cn=admin,dc=e2e,dc=local -w "$BC_OTHER_PASSWORD" '(uid=lrequester)' uid >/dev/null 2>&1; then
    echo 'e2e: openldap up'; exit 0
  fi
  sleep 2
done
echo 'e2e: openldap TIMED OUT' >&2; docker logs --tail 40 batch-control-e2e-ldap >&2; exit 1
