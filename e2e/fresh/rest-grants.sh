#!/usr/bin/env bash
set -a; . "$(dirname "$0")/../.env"; set +a  # passwords from e2e/.env (never committed)
# e2e-04 Scenario 4 REST paths: createItem under a name-restricted CREATE window, forged self-approver.
set -uo pipefail
B=http://localhost:8080
OUT="$(dirname "$0")/out"; mkdir -p "$OUT"
post() { local u="$1" pw="$2" p="$3"; shift 3; local jar; jar=$(mktemp)
  local crumb; crumb=$(curl -s -c "$jar" -u "$u:$pw" "$B/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
  curl -s -b "$jar" -o "$OUT/body.txt" -w '%{http_code}' -u "$u:$pw" -H "$crumb" -X POST "$@" "$B$p"; rm -f "$jar"; }
excerpt() { sed -e 's/<[^>]*>/ /g' "$OUT/body.txt" | tr -s ' \n\t' ' ' | cut -c1-500; echo; }
XML='<project><builders/><publishers/></project>'
echo "== requester createItem fresh-folder/evil-name (window allows /fresh-ok-.*/)"
echo "HTTP $(post requester $BC_REQUESTER_PASSWORD '/job/fresh-folder/createItem?name=evil-name' -H 'Content-Type: application/xml' --data "$XML")"; excerpt
echo "exists? $(curl -s -o /dev/null -w '%{http_code}' -u admin:$BC_ADMIN_PASSWORD $B/job/fresh-folder/job/evil-name/api/json)"
echo "== requester createItem fresh-folder/fresh-ok-rest"
echo "HTTP $(post requester $BC_REQUESTER_PASSWORD '/job/fresh-folder/createItem?name=fresh-ok-rest' -H 'Content-Type: application/xml' --data "$XML")"; excerpt
echo "== requester creates a run request naming itself as approver (forged form post)"
echo "HTTP $(post requester $BC_REQUESTER_PASSWORD '/job/fresh-daily/batch-control/submit' --data-urlencode 'reason=self approval attempt' --data-urlencode 'approvers=requester' --data-urlencode 'json={"reason":"self approval attempt","approvers":"requester"}')"; excerpt
echo "== requester grant request naming itself as approver"
