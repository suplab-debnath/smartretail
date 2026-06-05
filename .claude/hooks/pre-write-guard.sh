#!/usr/bin/env bash
# Pre-tool hook: guards against writing to generated or sensitive files.
# Receives tool input JSON on stdin. Exits 2 to block, 0 to allow.

INPUT=$(cat)
# Extract file path(s) — handles Write/Edit (single file_path) and MultiEdit (edits array)
FILE_PATH=$(python3 -c "
import sys, json
data = json.load(sys.stdin)
# Single-file tools: Write, Edit
fp = data.get('file_path') or data.get('tool_input', {}).get('file_path', '')
if fp:
    print(fp)
    sys.exit(0)
# MultiEdit: iterate edits array and check each path
edits = data.get('edits') or data.get('tool_input', {}).get('edits', [])
for e in edits:
    efp = e.get('file_path', '')
    if efp:
        print(efp)
" <<< "$INPUT" 2>/dev/null) || FILE_PATH=""

# For MultiEdit, FILE_PATH may contain multiple lines — check each
while IFS= read -r FP; do
  [ -z "$FP" ] && continue

  # Block: writing to openapi-generator output directories
  if echo "$FP" | grep -qE '/target/generated-sources/'; then
    echo "BLOCKED: Do not edit generated Java sources in target/generated-sources/."
    echo "Modify the OpenAPI YAML in src/main/resources/ and run: mvn generate-sources"
    exit 2
  fi

  # Block: writing to generated TypeScript client
  if echo "$FP" | grep -qE '/mfe/shared/api-client/src/generated/'; then
    echo "BLOCKED: Do not edit generated TypeScript clients in mfe/shared/api-client/src/generated/."
    echo "Modify the OpenAPI YAML and run: npm run generate-api"
    exit 2
  fi

  # Block: writing to Flyway migration files that already exist (immutability rule)
  if echo "$FP" | grep -qE '/db/migration/V[0-9]+__'; then
    # Check if the file already exists (modifying an existing migration is forbidden)
    if [ -f "$FP" ]; then
      echo "BLOCKED: Flyway versioned migrations are immutable once created."
      echo "Create a new migration file (next version number) instead of editing $FP."
      exit 2
    fi
  fi

  # Warn: writing to .env files
  if echo "$FP" | grep -qE '\.(env|env\.local|env\.aws|env\.prod)$'; then
    echo "WARNING: Writing to $FP — ensure no real secrets are committed to git."
    echo ".env files are in .gitignore; verify before committing."
  fi
done <<< "$FILE_PATH"
exit 0
