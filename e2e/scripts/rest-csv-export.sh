#!/usr/bin/env bash
# T-E2E-04 - the four CSV exports: header row, row count, and the parameter
# column of runs.csv / requests.csv.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### T-E2E-04  CSV export"
bc_login admin

for name in runs incidents changes requests; do
  file="$OUT_DIR/export-$name.csv"
  status=$(bc_get admin "$file" "/batch-control/history/$name.csv")
  rows=$(( $(grep -c '' "$file") - 1 ))
  echo "--- GET /batch-control/history/$name.csv -> HTTP $status, $rows data rows"
  echo "    header: $(head -1 "$file")"
  echo "    first data row: $(sed -n 2p "$file")"
done

echo "--- does runs.csv carry the parameters of the approved run?"
grep -m1 'APPROVED_REQUEST' "$OUT_DIR/export-runs.csv" || echo "    no APPROVED_REQUEST row"
echo "--- does requests.csv carry the raw parameters?"
grep -m1 'DATE=' "$OUT_DIR/export-requests.csv" || echo "    no parameter column content"

echo "--- content type and download header of runs.csv:"
curl -sS -g -D - -o /dev/null -b "$OUT_DIR/cookies-admin.txt" -u "admin:$(bc_password admin)" \
  "$JENKINS_URL/batch-control/history/runs.csv" | grep -i 'content-type\|content-disposition\|^HTTP'
