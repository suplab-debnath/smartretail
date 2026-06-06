#!/bin/bash
# run-flyway-aws.sh — delegates to the shared ECS run-task migration script.
set -euo pipefail
exec "$(dirname "$0")/run-flyway-ecs.sh" "${1:-dev}"
