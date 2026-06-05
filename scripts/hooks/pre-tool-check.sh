#!/usr/bin/env bash
# SmartRetail — Copilot preToolUse hook
# Receives tool context via environment variables:
#   COPILOT_TOOL_NAME  — name of the tool about to be called (e.g. "runCommand", "editFiles")
#   COPILOT_TOOL_INPUT — JSON string of the tool's input parameters
#
# Exit codes:
#   0  → allow the tool call to proceed
#   2  → block the tool call (Copilot will not execute and will surface the error message)

set -euo pipefail

TOOL="${COPILOT_TOOL_NAME:-}"
INPUT="${COPILOT_TOOL_INPUT:-}"

# ──────────────────────────────────────────────────────────────────────────────
# BLOCK 1: Protect immutable Flyway migrations from in-place edits
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "runCommand" ]]; then
  if echo "$INPUT" | grep -qE '"(vim|nano|emacs|sed -i|perl -i)[^"]*V[0-9]+__'; then
    echo "BLOCKED: Attempting to edit an immutable Flyway migration file." >&2
    echo "  Create V{next}__description.sql instead of modifying an existing migration." >&2
    exit 2
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# BLOCK 2: Protect generated OpenAPI source files from direct edits
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "editFiles" ]]; then
  if echo "$INPUT" | grep -qE '(target/generated-sources/openapi|mfe/shared/api-client/src/generated)'; then
    echo "BLOCKED: Generated source files must not be edited directly." >&2
    echo "  Edit the OpenAPI YAML first, then run: mvn generate-sources -pl backend/services/{service}" >&2
    exit 2
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# BLOCK 3: Prevent force-pushes to protected branches
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "runCommand" ]]; then
  if echo "$INPUT" | grep -qE 'git push.*(--force|-f).*(main|master|release)'; then
    echo "BLOCKED: Force-push to a protected branch (main / master / release) is not permitted." >&2
    exit 2
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# BLOCK 4: Prevent destructive rm on source directories
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "runCommand" ]]; then
  if echo "$INPUT" | grep -qE 'rm\s+-rf?\s+.*(backend|mfe|environments|scripts|infra|docs|migrations)'; then
    echo "BLOCKED: Destructive rm -rf on a protected source directory." >&2
    exit 2
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# BLOCK 5: Prevent dangerous DDL commands outside local/test context
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "runCommand" ]]; then
  if echo "$INPUT" | grep -qiE '(DROP\s+TABLE|DROP\s+SCHEMA|TRUNCATE\s+TABLE)'; then
    PROFILE="${SPRING_PROFILES_ACTIVE:-}"
    if [[ "$PROFILE" != "local" && "$PROFILE" != "test" ]]; then
      echo "BLOCKED: Destructive DDL (DROP TABLE / TRUNCATE) detected outside local/test profile." >&2
      echo "  If this is intentional, set SPRING_PROFILES_ACTIVE=local and confirm manually." >&2
      exit 2
    fi
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# WARN: CDK destroy without explicit confirmation
# ──────────────────────────────────────────────────────────────────────────────
if [[ "$TOOL" == "runCommand" ]]; then
  if echo "$INPUT" | grep -qE 'cdk destroy'; then
    echo "BLOCKED: 'cdk destroy' must be run manually — use the AWS Deployer agent for guided teardown." >&2
    exit 2
  fi
fi

# All checks passed — allow the tool call
exit 0
