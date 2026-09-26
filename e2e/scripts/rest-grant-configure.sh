#!/usr/bin/env bash
# T-E2E-03 / T-E2E-06 - JIT change control end to end:
#   requester asks for CONFIGURE on batch-daily for 1 minute
#   -> approver approves
#   -> requester saves the job configuration successfully
#   -> the window expires
#   -> the same save is refused, and we look at what the refusal tells the user.
#
# The 1-minute duration is offered by init.groovy.d/10-batch-control-config.groovy
# precisely so this scenario does not have to wait out the 15-minute default.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

SCOPE="${1:-batch-daily}"

echo "### T-E2E-03 / T-E2E-06  CONFIGURE grant, 1 minute"

bc_login requester
bc_login approver
bc_login admin

# --- 0. baseline: without a grant the requester may not configure the job
status=$(bc_get requester "$OUT_DIR/grant-configure-before.html" "/job/$SCOPE/configure")
echo "--- GET /job/$SCOPE/configure as requester BEFORE any grant -> HTTP $status (expected 403)"

# --- 1. create the grant request
status=$(bc_post requester "$OUT_DIR/grant-create.html" "/batch-control/grants/create" \
        --data-urlencode "scopeType=JOB" \
        --data-urlencode "scopeFullName=$SCOPE" \
        --data-urlencode "actions=CONFIGURE" \
        --data-urlencode "durationMinutes=1" \
        --data-urlencode "reason=T-E2E-03 fix the cron expression of $SCOPE" \
        --data-urlencode "approver=approver")
echo "--- POST /batch-control/grants/create (requester) -> HTTP $status"

# doCreate redirects to the list, so the id comes off the list page (newest first).
bc_get requester "$OUT_DIR/grant-list.html" "/batch-control/grants/" > /dev/null
# The list links to each request with a relative href ("<id>/"); ids start with
# a timestamp, so the newest one sorts last whatever the list order is.
GRANT_ID=$(grep -o 'href="[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/"' "$OUT_DIR/grant-list.html" \
        | sed -e 's#href="##' -e 's#/"##' | sort | tail -1)
echo "grant request id = $GRANT_ID"
if [ -z "$GRANT_ID" ]; then echo "e2e: no grant request id found on the list page" >&2; exit 1; fi

# --- 2. approve it
status=$(bc_post approver "$OUT_DIR/grant-approve.html" "/batch-control/grants/$GRANT_ID/approve" \
        --data-urlencode "comment=T-E2E-03 approved, 1 minute window")
echo "--- POST /batch-control/grants/$GRANT_ID/approve (approver) -> HTTP $status"
GRANT_APPROVED_AT=$(date +%s)

# --- 3. inside the window the requester may configure the job
status=$(bc_get requester "$OUT_DIR/grant-configure-inside.html" "/job/$SCOPE/configure")
echo "--- GET /job/$SCOPE/configure as requester INSIDE the window -> HTTP $status (expected 200)"

bc_get admin "$OUT_DIR/grant-config.xml" "/job/$SCOPE/config.xml" > /dev/null
sed -i "s#<description>[^<]*</description>#<description>Changed by requester under grant $GRANT_ID</description>#" "$OUT_DIR/grant-config.xml"
status=$(bc_post requester "$OUT_DIR/grant-save-inside.html" "/job/$SCOPE/config.xml" \
        -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/grant-config.xml")
echo "--- POST /job/$SCOPE/config.xml as requester INSIDE the window -> HTTP $status (expected 200)"

bc_get admin "$OUT_DIR/grant-config-after.xml" "/job/$SCOPE/config.xml" > /dev/null
echo "description now: $(grep -o '<description>[^<]*</description>' "$OUT_DIR/grant-config-after.xml" | head -1)"

# --- 4. wait for the window to close (no timer involved: the first permission
#        check after the expiry instant must already refuse)
elapsed=$(( $(date +%s) - GRANT_APPROVED_AT ))
wait_for=$(( 70 - elapsed ))
if [ "$wait_for" -gt 0 ]; then
  echo "--- waiting ${wait_for}s for the 1-minute window to expire"
  sleep "$wait_for"
fi

# --- 5. the same operations must now be refused
status=$(bc_get requester "$OUT_DIR/grant-configure-expired.html" "/job/$SCOPE/configure")
echo "--- GET /job/$SCOPE/configure as requester AFTER expiry -> HTTP $status (expected 403)"

sed -i "s#<description>[^<]*</description>#<description>Attempted after expiry</description>#" "$OUT_DIR/grant-config.xml"
status=$(bc_post requester "$OUT_DIR/grant-save-expired.html" "/job/$SCOPE/config.xml" \
        -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/grant-config.xml")
echo "--- POST /job/$SCOPE/config.xml as requester AFTER expiry -> HTTP $status (expected 403)"

bc_get admin "$OUT_DIR/grant-config-final.xml" "/job/$SCOPE/config.xml" > /dev/null
echo "description after the refused save: $(grep -o '<description>[^<]*</description>' "$OUT_DIR/grant-config-final.xml" | head -1)"

# --- 6. T-E2E-06: what does the refusal tell the user?
echo "--- refusal body (first 400 bytes of the configure page):"
head -c 400 "$OUT_DIR/grant-configure-expired.html"; echo
echo "--- does the refusal mention the grant / expiry / a re-request link?"
for needle in 'expired' 'grant' 'batch-control/grants' 'Request' 'permission'; do
  if grep -q -i "$needle" "$OUT_DIR/grant-configure-expired.html"; then
    echo "  contains '$needle': YES"
  else
    echo "  contains '$needle': NO"
  fi
done

# --- 7. the change made under the grant must be linked to the grant id
status=$(bc_get admin "$OUT_DIR/grant-changes.csv" "/batch-control/history/changes.csv")
echo "--- GET /batch-control/history/changes.csv (admin) -> HTTP $status"
grep "$GRANT_ID" "$OUT_DIR/grant-changes.csv" | head -3 || echo "  no change record carries the grant id"
