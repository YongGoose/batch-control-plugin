#!/usr/bin/env bash
# Stops the container. JENKINS_HOME (the named volume) is kept - use reset.sh to wipe it.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
