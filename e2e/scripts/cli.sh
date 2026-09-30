#!/usr/bin/env bash
# jenkins-cli.jar as <user> (checklist 1.5): scripts/cli.sh <user> <command> [args...]
# Runs inside the Jenkins container (the CLI needs Java 21, which the image has),
# with the jar the instance itself serves at /jnlpJars/jenkins-cli.jar.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
user="$1"; shift
docker exec batch-control-e2e sh -c '[ -f /tmp/jenkins-cli.jar ] || curl -sSf -o /tmp/jenkins-cli.jar http://localhost:8080/jnlpJars/jenkins-cli.jar'
exec docker exec -i batch-control-e2e java -jar /tmp/jenkins-cli.jar -s http://localhost:8080/ -http -auth "$user:$(bc_password "$user")" "$@"
