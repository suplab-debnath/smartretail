#!/usr/bin/env bash
# Pre-tool hook: guards against writing to generated or sensitive files.
# Receives tool input JSON on stdin. Exits 2 to block, 0 to allow.

INPUT=$(cat)

# Extract file_path from JSON
FILE_PATH=$(python3 -c "
import sys, json
data = json.load(sys.stdin)
fp = data.get('file_path') or data.get('tool_input', {}).get('file_path', '')
print(fp)
" <<< "$INPUT" 2>/dev/null) || FILE_PATH=""

if [ -z "$FILE_PATH" ]; then
  exit 0
fi

# Block: writing to openapi-generator output directories
if echo "$FILE_PATH" | grep -qE '/target/generated-sources/'; then
  echo "BLOCKED: Do not edit generated Java sources in target/generated-sources/."
  echo "Modify the OpenAPI YAML in src/main/resources/ and run: mvn generate-sources"
  exit 2
fi

# Block: writing to generated TypeScript client
if echo "$FILE_PATH" | grep -qE '/mfe/shared/api-client/src/generated/'; then
  echo "BLOCKED: Do not edit generated TypeScript clients in mfe/shared/api-client/src/generated/."
  echo "Modify the OpenAPI YAML and run: npm run generate-api"
  exit 2
fi

# Block: writing to Flyway migration files that already exist (immutability rule)
if echo "$FILE_PATH" | grep -qE '/db/migration/V[0-9]+__'; then
  # Check if the file already exists (modifying an existing migration is forbidden)
  if [ -f "$FILE_PATH" ]; then
    echo "BLOCKED: Flyway versioned migrations are immutable once created."
    echo "Create a new migration file (next version number) instead of editing $FILE_PATH."
    exit 2
  fi
fi

# Warn: writing to .env files
if echo "$FILE_PATH" | grep -qE '\.(env|env\.local|env\.aws|env\.prod)$'; then
  echo "WARNING: Writing to $FILE_PATH — ensure no real secrets are committed to git."
  echo ".env files are in .gitignore; verify before committing."
fi

exit 0
