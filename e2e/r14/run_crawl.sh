#!/usr/bin/env bash
# Runs the crawl for every role, first with the new job page, then classic (flag per account).
cd "$(dirname "$0")"
PY=${PY:-python3}
ROLES="admin requester reqonly approver-1 manager nobc"
rm -f out/crawl.jsonl
$PY set_flag.py true $ROLES
for r in $ROLES; do $PY crawl.py "$r" new; done
$PY set_flag.py false $ROLES
for r in $ROLES; do $PY crawl.py "$r" classic; done
$PY set_flag.py true $ROLES
echo CRAWL-DONE
