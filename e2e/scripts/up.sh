#!/usr/bin/env bash
# Builds the image (first run only), starts Jenkins and waits until it answers.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "e2e: .env is missing - cp .env.example .env and set the passwords" >&2
  exit 2
fi
if [ ! -f ../target/batch-control.hpi ]; then
  echo "e2e: ../target/batch-control.hpi is missing - run 'mvn -ntp clean package -DskipTests' first" >&2
  exit 2
fi

docker compose up -d --build

printf 'e2e: waiting for Jenkins'
for _ in $(seq 1 120); do
  if curl -sSf -o /dev/null "http://localhost:$(grep -E '^BC_PORT=' .env | cut -d= -f2 || echo 8080)/login" 2>/dev/null; then
    echo ' up'
    exit 0
  fi
  printf '.'
  sleep 2
done
echo ' TIMED OUT' >&2
docker compose logs --tail 80 jenkins >&2
exit 1
