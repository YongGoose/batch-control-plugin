#!/usr/bin/env bash
# Shared helpers for the e2e scripts: authenticated, crumb-carrying curl.
#
# Source it, do not run it:  . "$(dirname "$0")/lib.sh"
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$E2E_DIR/out"
mkdir -p "$OUT_DIR"

if [ -f "$E2E_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$E2E_DIR/.env"
  set +a
else
  echo "e2e: $E2E_DIR/.env is missing - copy .env.example to .env" >&2
  exit 2
fi

JENKINS_URL="http://localhost:${BC_PORT:-8080}"

# Password for one of the three accounts.
bc_password() {
  case "$1" in
    admin)     printf '%s' "$BC_ADMIN_PASSWORD" ;;
    approver)  printf '%s' "$BC_APPROVER_PASSWORD" ;;
    requester) printf '%s' "$BC_REQUESTER_PASSWORD" ;;
    *) echo "e2e: unknown account '$1'" >&2; return 2 ;;
  esac
}

# Logs the user in and stores the session cookie. A crumb is bound to the
# session that issued it, so every POST must reuse the same cookie jar.
bc_login() {
  local user="$1"
  local jar="$OUT_DIR/cookies-$user.txt"
  rm -f "$jar"
  curl -sS -g -c "$jar" -u "$user:$(bc_password "$user")" \
    -o "$OUT_DIR/crumb-$user.json" -w '%{http_code}' \
    "$JENKINS_URL/crumbIssuer/api/json" > "$OUT_DIR/crumb-$user.status"
  local status
  status="$(cat "$OUT_DIR/crumb-$user.status")"
  if [ "$status" != "200" ]; then
    echo "e2e: crumb request for '$user' returned HTTP $status" >&2
    cat "$OUT_DIR/crumb-$user.json" >&2 || true
    return 1
  fi
  sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p' "$OUT_DIR/crumb-$user.json" > "$OUT_DIR/crumb-$user.txt"
}

bc_crumb() { cat "$OUT_DIR/crumb-$1.txt"; }

# GET as <user>. Prints the HTTP status on stdout; the body goes to $2.
bc_get() {
  local user="$1" body="$2" path="$3"
  curl -sS -g -b "$OUT_DIR/cookies-$user.txt" -u "$user:$(bc_password "$user")" \
    -o "$body" -w '%{http_code}' "$JENKINS_URL$path"
}

# POST as <user> with the crumb header. Remaining arguments are passed to curl
# (typically --data-urlencode pairs). Prints the HTTP status; body goes to $2.
bc_post() {
  local user="$1" body="$2" path="$3"
  shift 3
  curl -sS -g -b "$OUT_DIR/cookies-$user.txt" -u "$user:$(bc_password "$user")" \
    -H "Jenkins-Crumb: $(bc_crumb "$user")" \
    -o "$body" -w '%{http_code}' -X POST "$@" "$JENKINS_URL$path"
}

# Prints "HTTP <status>  <path>" and the first lines of the body - the shape the
# report quotes.
bc_report() {
  local label="$1" status="$2" body="$3" lines="${4:-5}"
  echo "--- $label -> HTTP $status"
  head -c 2000 "$body" | sed -n "1,${lines}p"
  echo
}

# Runs a Groovy script on the admin script console and prints the output. Used
# only to inspect or prepare server-side state that has no HTTP surface (for
# example emptying the approver list for T-E2E-08); never to perform the
# behaviour under test.
bc_script() {
  local file="$1"
  curl -sS -g -b "$OUT_DIR/cookies-admin.txt" -u "admin:$(bc_password admin)" \
    -H "Jenkins-Crumb: $(bc_crumb admin)" \
    --data-urlencode "script=$(cat "$file")" "$JENKINS_URL/scriptText"
}
