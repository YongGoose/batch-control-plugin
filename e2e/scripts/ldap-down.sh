#!/usr/bin/env bash
# Stops and removes the OpenLDAP container of compose.ldap.yml. Jenkins keeps running.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.yml -f compose.ldap.yml rm -sf openldap
