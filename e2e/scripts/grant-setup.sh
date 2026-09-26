#!/usr/bin/env bash
# Arranges an APPROVED, still-active CONFIGURE grant so a browser pass can look
# at the job configuration screen inside a permission window.
#
#   usage: grant-setup.sh [job-full-name] [minutes]
set -euo pipefail
. "$(dirname "$0")/lib.sh"

JOB="${1:-batch-daily}"
MINUTES="${2:-30}"

bc_login requester
bc_login approver

status=$(bc_post requester "$OUT_DIR/setup-grant-create.html" "/batch-control/grants/create" \
        --data-urlencode "scopeType=JOB" \
        --data-urlencode "scopeFullName=$JOB" \
        --data-urlencode "actions=CONFIGURE" \
        --data-urlencode "durationMinutes=$MINUTES" \
        --data-urlencode "reason=visual pass: look at the job configuration screen under a grant" \
        --data-urlencode "approver=approver")
echo "create -> HTTP $status"
bc_get requester "$OUT_DIR/setup-grant-list.html" "/batch-control/grants/" > /dev/null
id=$(grep -o 'href="[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/"' "$OUT_DIR/setup-grant-list.html" \
        | sed -e 's#href="##' -e 's#/"##' | sort | tail -1)
status=$(bc_post approver "$OUT_DIR/setup-grant-approve.html" "/batch-control/grants/$id/approve" \
        --data-urlencode "comment=approved for the visual pass")
echo "approve -> HTTP $status"
echo "grant $id is active on $JOB for $MINUTES minutes (actions: CONFIGURE)"
