#!/usr/bin/env bash
# Stops the container and deletes JENKINS_HOME, so the next up.sh bootstraps
# accounts, configuration and sample jobs from scratch.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down -v
rm -f out/cookies-*.txt out/crumb-*
echo "e2e: volume removed; next up.sh starts from a clean JENKINS_HOME"
