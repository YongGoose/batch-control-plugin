#!/usr/bin/env bash
set -a; . "$(dirname "$0")/../.env"; set +a  # passwords from e2e/.env (never committed)
# e2e-04 Scenario 6, REST and CLI refusals. Prints status code and a text excerpt of each body.
set -uo pipefail
B=http://localhost:8080
OUT="$(dirname "$0")/out"; mkdir -p "$OUT"
pw() { case "$1" in admin) echo $BC_ADMIN_PASSWORD;; requester) echo $BC_REQUESTER_PASSWORD;; approver-1|approver-2) echo $BC_APPROVER_PASSWORD;; *) echo $BC_OTHER_PASSWORD;; esac; }
post() { # post <user> <path> [curl args...]
  local u="$1" p="$2"; shift 2
  local jar; jar=$(mktemp)
  local crumb; crumb=$(curl -s -c "$jar" -u "$u:$(pw "$u")" "$B/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
  curl -s -b "$jar" -o "$OUT/body.txt" -w '%{http_code}' -u "$u:$(pw "$u")" -H "$crumb" -X POST "$@" "$B$p"
  rm -f "$jar"
}
excerpt() { sed -e 's/<[^>]*>/ /g' "$OUT/body.txt" | tr -s ' \n\t' ' ' | cut -c1-"${1:-400}"; echo; }
queue() { curl -s -u admin:$BC_ADMIN_PASSWORD "$B/queue/api/json?tree=items%5Btask%5Bname%5D%5D" ; echo; }

echo "== requester POST /job/fresh-daily/build"
echo "HTTP $(post requester /job/fresh-daily/build)"; excerpt
echo "== requester POST /job/fresh-daily/buildWithParameters?DATE=2026-12-31"
echo "HTTP $(post requester '/job/fresh-daily/buildWithParameters?DATE=2026-12-31&MODE=FULL')"; excerpt
echo "== requester POST /job/fresh-daily/build with Accept: application/json"
echo "HTTP $(post requester /job/fresh-daily/build -H 'Accept: application/json')"; excerpt 300
echo "== anonymous GET /job/fresh-token/build?token=fresh-tok"
echo "HTTP $(curl -s -o "$OUT/body.txt" -w '%{http_code}' "$B/job/fresh-token/build?token=fresh-tok")"; excerpt
echo "== anonymous GET /buildByToken/build?job=fresh-token&token=fresh-tok"
echo "HTTP $(curl -s -o "$OUT/body.txt" -w '%{http_code}' "$B/buildByToken/build?job=fresh-token&token=fresh-tok")"; excerpt
echo "== nobc POST /job/fresh-daily/build (Item/Build, no Batch Control)"
echo "HTTP $(post nobc /job/fresh-daily/build)"; excerpt
echo "== admin POST /job/fresh-daily/build (administrator)"
echo "HTTP $(post admin /job/fresh-daily/build)"; excerpt
echo "== CLI build as requester"
docker exec batch-control-e2e sh -c '[ -f /tmp/jenkins-cli.jar ] || curl -sSf -o /tmp/jenkins-cli.jar http://localhost:8080/jnlpJars/jenkins-cli.jar'
docker exec -i batch-control-e2e java -jar /tmp/jenkins-cli.jar -s http://localhost:8080/ -http -auth "requester:$(pw requester)" build fresh-daily -p DATE=2026-12-30 2>&1 | head -8; echo "exit ${PIPESTATUS[0]}"
echo "== queue after all attempts"; queue
echo "== builds of fresh-daily and fresh-token"
curl -s -u admin:$BC_ADMIN_PASSWORD "$B/job/fresh-daily/api/json?tree=builds%5Bnumber%5D"; echo
curl -s -u admin:$BC_ADMIN_PASSWORD "$B/job/fresh-token/api/json?tree=builds%5Bnumber%5D"; echo
echo "== requester POST /job/fresh-token/build (no parameters)"
echo "HTTP $(post requester /job/fresh-token/build)"; excerpt
echo "== nobc POST /job/fresh-token/build (Item/Build, no Batch Control)"
echo "HTTP $(post nobc /job/fresh-token/build)"; excerpt
echo "== admin POST /job/fresh-token/build"
echo "HTTP $(post admin /job/fresh-token/build)"; excerpt
echo "== requester POST /job/fresh-daily/build with json (the form's payload)"
echo "HTTP $(post requester /job/fresh-daily/build --data-urlencode 'json={"parameter":[{"name":"DATE","value":"2026-12-29"},{"name":"MODE","value":"FULL"}]}')"; excerpt
echo "== builds of fresh-token"; curl -s -u admin:$BC_ADMIN_PASSWORD "$B/job/fresh-token/api/json?tree=builds%5Bnumber%5D"; echo
