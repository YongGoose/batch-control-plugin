#!/usr/bin/env bash
# Permission enforcement under the REAL authorization strategy
# (BatchControl wrapping matrix-auth), not the test harness:
#
#   a) approver submits a run request     -> must be 403, no request created
#   b) requester approves a request       -> must be 403, request stays PENDING,
#                                            no build is started
#   c) requester reads the history screen -> must be 403 (no ViewHistory)
#
# Covers the negative half of T-E2E-01/03 and re-checks T-SEC-15 outside the
# integration tests.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### permission enforcement (matrix-auth + BatchControl wrapper)"

bc_login requester
bc_login approver
bc_login admin

count_requests() {
  bc_get admin "$OUT_DIR/perm-requests.csv" "/batch-control/history/requests.csv" > /dev/null
  # minus the header row
  echo $(( $(grep -c '' "$OUT_DIR/perm-requests.csv") - 1 ))
}
count_builds() {
  bc_get admin "$OUT_DIR/perm-builds.json" "/job/batch-daily/api/json?tree=builds[number]" > /dev/null
  grep -o '"number":[0-9]*' "$OUT_DIR/perm-builds.json" | wc -l | tr -d ' '
}

before_requests=$(count_requests)
before_builds=$(count_builds)
echo "state before: $before_requests run requests, $before_builds builds of batch-daily"

# --- a) approver has APPROVE but not REQUEST
json='{"reason":"approver tries to request","approver":"approver","parameter":[{"name":"DATE","value":"2026-01-01"},{"name":"MODE","value":"full"}]}'
status=$(bc_post approver "$OUT_DIR/perm-a.html" "/job/batch-daily/batch-control/submit" \
        --data-urlencode "json=$json")
echo "--- (a) POST submit as approver -> HTTP $status (expected 403)"
grep -o -i 'Request is missing[^<]*\|is missing the [^<]*permission[^<]*\|Access Denied[^<]*' "$OUT_DIR/perm-a.html" | head -2

# also the GET of the form must be refused (getTarget gate)
status_get=$(bc_get approver "$OUT_DIR/perm-a-get.html" "/job/batch-daily/batch-control/")
echo "--- (a) GET the request form as approver -> HTTP $status_get (expected 403)"

# --- b) requester creates a request, then tries to approve it himself
json='{"reason":"self-approval attempt","approver":"approver","parameter":[{"name":"DATE","value":"2026-01-02"},{"name":"MODE","value":"partial"}]}'
status=$(bc_post requester "$OUT_DIR/perm-b-create.html" "/job/batch-daily/batch-control/submit" \
        -D "$OUT_DIR/perm-b-create.headers" --data-urlencode "json=$json")
path=$(grep -i '^location:' "$OUT_DIR/perm-b-create.headers" | tail -1 \
        | sed -e 's/^[Ll]ocation: *//' -e 's#^https*://[^/]*##' | tr -d '\r')
echo "--- (b) requester created $path -> HTTP $status"

status=$(bc_post requester "$OUT_DIR/perm-b.html" "${path}approve" --data-urlencode "comment=let me approve my own request")
echo "--- (b) POST approve as requester -> HTTP $status (expected 403)"
grep -o -i 'Request is missing[^<]*\|is missing the [^<]*permission[^<]*\|Access Denied[^<]*' "$OUT_DIR/perm-b.html" | head -2

# --- c) requester has no ViewHistory
status=$(bc_get requester "$OUT_DIR/perm-c.html" "/batch-control/history/")
echo "--- (c) GET /batch-control/history/ as requester -> HTTP $status (expected 403)"
status=$(bc_get requester "$OUT_DIR/perm-c2.csv" "/batch-control/history/runs.csv")
echo "--- (c) GET /batch-control/history/runs.csv as requester -> HTTP $status (expected 403)"

# --- server state must be unchanged apart from the one request (b) legitimately created
sleep 5
after_requests=$(count_requests)
after_builds=$(count_builds)
echo "state after: $after_requests run requests (expected $((before_requests + 1))), $after_builds builds (expected $before_builds)"
echo "--- status of the request from (b), read as admin:"
bc_get admin "$OUT_DIR/perm-b-final.html" "$path" > /dev/null
grep -o -i 'PENDING\|APPROVED\|EXECUTED\|REJECTED' "$OUT_DIR/perm-b-final.html" | sort -u | tr '\n' ' '
echo
