#!/usr/bin/env bash
# HTML-level checks for the rows whose acceptance criterion is "X is shown on
# screen". The visual confirmation is done in a browser; these assertions prove
# the markup carries the data, so a browser pass only has to confirm layout.
#
#   T-E2E-02  job page: "Request Run" instead of "Build Now"
#   T-E2E-05  approver decision screen carries job, requester, reason, raw
#             parameters and the job's recent runs on one page
#   T-E2E-07  dashboard row of an approved run links to the request detail
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### T-E2E-02 / T-E2E-05 / T-E2E-07"

bc_login requester
bc_login approver
bc_login admin

# ---------------------------------------------------------------- T-E2E-02
for job in batch-daily batch-pipeline batch-cron; do
  status=$(bc_get requester "$OUT_DIR/screens-$job.html" "/job/$job/")
  echo "--- GET /job/$job/ (requester) -> HTTP $status"
  printf '    "Request Run" present: %s\n' "$(grep -c 'Request Run' "$OUT_DIR/screens-$job.html" || true)"
  printf '    "Build Now" present:   %s\n' "$(grep -c 'Build Now' "$OUT_DIR/screens-$job.html" || true)"
  printf '    link to the request form: %s\n' \
    "$(grep -o 'href="/job/'"$job"'/batch-control[^"]*"' "$OUT_DIR/screens-$job.html" | sort -u | tr '\n' ' ')"
done

# ---------------------------------------------------------------- T-E2E-05
# A fresh PENDING request with a reason and parameters, read by the approver.
json='{"reason":"T-E2E-05 rerun after the upstream feed was corrected","approver":"approver","parameter":[{"name":"DATE","value":"2026-09-20"},{"name":"MODE","value":"partial"}]}'
status=$(bc_post requester "$OUT_DIR/screens-create.html" "/job/batch-daily/batch-control/submit" \
        -D "$OUT_DIR/screens-create.headers" --data-urlencode "json=$json")
path=$(grep -i '^location:' "$OUT_DIR/screens-create.headers" | tail -1 \
        | sed -e 's/^[Ll]ocation: *//' -e 's#^https*://[^/]*##' | tr -d '\r')
echo "--- created $path -> HTTP $status"
status=$(bc_get approver "$OUT_DIR/screens-decision.html" "$path")
echo "--- GET $path (approver) -> HTTP $status"
for needle in 'batch-daily' 'requester' 'T-E2E-05 rerun after the upstream feed was corrected' 'DATE' '2026-09-20' 'MODE' 'partial' 'approve' 'reject'; do
  if grep -q -- "$needle" "$OUT_DIR/screens-decision.html"; then r=YES; else r=NO; fi
  printf '    carries %-60s %s\n' "'$needle'" "$r"
done
echo "    recent runs of the job on the same page (build links):"
grep -o 'href="[^"]*/job/batch-daily/[0-9]*/*"' "$OUT_DIR/screens-decision.html" | sort -u | head -5 \
  || echo "      none found"

# ---------------------------------------------------------------- T-E2E-07
status=$(bc_get admin "$OUT_DIR/screens-dashboard.html" "/batch-control/dashboard/")
echo "--- GET /batch-control/dashboard/ (admin) -> HTTP $status"
echo "    APPROVED_REQUEST rows: $(grep -c 'APPROVED_REQUEST' "$OUT_DIR/screens-dashboard.html" || true)"
echo "    links to request details: $(grep -o 'href="[^"]*/batch-control/requests/[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/*"' "$OUT_DIR/screens-dashboard.html" | sort -u | head -3 | tr '\n' ' ')"
first=$(grep -o '/batch-control/requests/[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/' "$OUT_DIR/screens-dashboard.html" | head -1)
if [ -n "$first" ]; then
  status=$(bc_get admin "$OUT_DIR/screens-followed.html" "$first")
  echo "--- following the first dashboard request link $first -> HTTP $status"
  echo "    the detail page names the same id: $(grep -c "$(basename "$first")" "$OUT_DIR/screens-followed.html" || true) occurrences"
fi
