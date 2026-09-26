#!/usr/bin/env bash
# T-E2E-01 - requester asks for a run of batch-daily, approver approves, the
# build actually runs, and the dashboard links the run to the request id.
#
# Every step prints the HTTP status and the part of the response the report quotes.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

DATE="${1:-$(date +%Y-%m-%d)}"
MODE="${2:-full}"
REASON="${3:-T-E2E-01 month-end reprocessing}"

echo "### T-E2E-01  run request -> approval -> build"

bc_login requester
bc_login approver
bc_login admin

# --- 1. the request form is reachable for the requester
status=$(bc_get requester "$OUT_DIR/e2e01-form.html" "/job/batch-daily/batch-control/")
bc_report "GET /job/batch-daily/batch-control/ (requester)" "$status" "$OUT_DIR/e2e01-form.html" 3

# --- 2. submit. doSubmit() reads req.getSubmittedForm(), so the payload is the
#        single `json` parameter a Jenkins form would post.
json=$(printf '{"reason":"%s","approver":"approver","parameter":[{"name":"DATE","value":"%s"},{"name":"MODE","value":"%s"}]}' \
        "$REASON" "$DATE" "$MODE")
status=$(bc_post requester "$OUT_DIR/e2e01-submit.html" "/job/batch-daily/batch-control/submit" \
        -D "$OUT_DIR/e2e01-submit.headers" --data-urlencode "json=$json")
echo "--- POST /job/batch-daily/batch-control/submit (requester) -> HTTP $status"
grep -i '^location:' "$OUT_DIR/e2e01-submit.headers" || true

# Jenkins answers with a context-relative Location here; strip a scheme and
# host as well so the script keeps working behind a reverse proxy.
REQUEST_PATH=$(grep -i '^location:' "$OUT_DIR/e2e01-submit.headers" | tail -1 \
        | sed -e 's/^[Ll]ocation: *//' -e 's#^https*://[^/]*##' | tr -d '\r')
REQUEST_ID=$(basename "$REQUEST_PATH")
if [ -z "$REQUEST_ID" ] || [ "$REQUEST_ID" = "/" ]; then
  echo "e2e: could not read the request id out of the submit redirect" >&2
  exit 1
fi
echo "request id = $REQUEST_ID"
printf '%s' "$REQUEST_ID" > "$OUT_DIR/e2e01-request-id.txt"

# --- 3. the approver sees the pending request (T-E2E-05 reads the same page)
status=$(bc_get approver "$OUT_DIR/e2e01-detail-approver.html" "$REQUEST_PATH")
bc_report "GET $REQUEST_PATH (approver)" "$status" "$OUT_DIR/e2e01-detail-approver.html" 3

# --- 4. approve
status=$(bc_post approver "$OUT_DIR/e2e01-approve.html" "${REQUEST_PATH}approve" \
        --data-urlencode "comment=T-E2E-01 approved by e2e script")
echo "--- POST ${REQUEST_PATH}approve (approver) -> HTTP $status"

# --- 5. the build must actually run
echo "--- waiting for the build to finish on batch-daily"
for _ in $(seq 1 30); do
  bc_get admin "$OUT_DIR/e2e01-build.json" \
    "/job/batch-daily/lastBuild/api/json?tree=number,result,building,actions[causes[shortDescription],parameters[name,value]]" > /dev/null
  if grep -q '"building":false' "$OUT_DIR/e2e01-build.json"; then
    break
  fi
  sleep 2
done
status=$(bc_get admin "$OUT_DIR/e2e01-job.json" "/job/batch-daily/api/json?tree=builds[number,result]")
echo "--- GET /job/batch-daily/api/json -> HTTP $status"
echo "builds: $(tr ',' '\n' < "$OUT_DIR/e2e01-job.json" | grep -o '"number":[0-9]*' | tr '\n' ' ')"
status=$(bc_get admin "$OUT_DIR/e2e01-build.json" \
  "/job/batch-daily/lastBuild/api/json?tree=number,result,building,actions[causes[shortDescription],parameters[name,value]]")
echo "--- GET /job/batch-daily/lastBuild/api/json -> HTTP $status"
tr ',' '\n' < "$OUT_DIR/e2e01-build.json" | grep -o '"number":[0-9]*\|"result":"[A-Z]*"\|"building":[a-z]*' | tr '\n' ' '
echo
echo "cause:     $(grep -o '"shortDescription":"[^"]*"' "$OUT_DIR/e2e01-build.json" | head -3 | tr '\n' ' ')"
echo "parameters: $(grep -o '"name":"[^"]*","value":"[^"]*"' "$OUT_DIR/e2e01-build.json" | tr '\n' ' ')"

# --- 6. the request id must be visible on the dashboard
status=$(bc_get admin "$OUT_DIR/e2e01-dashboard.html" "/batch-control/dashboard/")
echo "--- GET /batch-control/dashboard/ (admin) -> HTTP $status"
if grep -q "$REQUEST_ID" "$OUT_DIR/e2e01-dashboard.html"; then
  echo "dashboard contains the request id: YES"
  grep -o "APPROVED_REQUEST" "$OUT_DIR/e2e01-dashboard.html" | head -1
else
  echo "dashboard contains the request id: NO"
fi

# --- 7. and the request itself must be APPROVED/EXECUTED
status=$(bc_get admin "$OUT_DIR/e2e01-detail-final.html" "$REQUEST_PATH")
echo "--- GET $REQUEST_PATH (admin, after the run) -> HTTP $status"
grep -o -i 'EXECUTED\|APPROVED\|PENDING' "$OUT_DIR/e2e01-detail-final.html" | sort -u | tr '\n' ' '
echo
