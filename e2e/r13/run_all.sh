#!/usr/bin/env bash
# e2e-13: fresh JENKINS_HOME (separate compose project, the shared volume is kept), then every driver in order.
# usage: PY=<venv python> r13/run_all.sh   (run from anywhere; needs ../target/batch-control.hpi and e2e/.env)
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:-python3}
docker compose -p bc-e2e13 down -v >/dev/null 2>&1 || true
docker compose -p bc-e2e13 up -d --build
for _ in $(seq 1 120); do curl -sf -o /dev/null http://localhost:8080/login && break; sleep 3; done
cd r13 && rm -rf out
for s in setup manage_roles assign_roles grant_overlay endpoints; do echo "== $s"; "$PY" "$s.py"; done
docker logs batch-control-e2e 2>&1 | grep -c SEVERE || true
