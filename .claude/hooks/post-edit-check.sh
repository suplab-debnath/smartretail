#!/usr/bin/env bash
# Post-tool hook: lightweight sanity checks after file writes/edits.
# Non-blocking — exits 0 always but prints warnings to surface violations immediately.
INPUT=$(cat)
FILE_PATHS=$(python3 -c "
import sys, json
data = json.load(sys.stdin)
fp = data.get('file_path') or data.get('tool_input', {}).get('file_path', '')
if fp:
    print(fp)
edits = data.get('edits') or data.get('tool_input', {}).get('edits', [])
for e in edits:
    efp = e.get('file_path', '')
    if efp:
        print(efp)
" <<< "$INPUT" 2>/dev/null)

[ -z "$FILE_PATHS" ] && exit 0

while IFS= read -r FILE_PATH; do
  [ -z "$FILE_PATH" ] && continue
  [ ! -f "$FILE_PATH" ] && continue

  # ── Check 1: AWS import in domain package ─────────────────────────
  if echo "$FILE_PATH" | grep -qE '/domain/(model|usecase)/.*\.java$'; then
    if grep -qE 'import software\.amazon\.' "$FILE_PATH" 2>/dev/null; then
      echo "⚠️ ARCH VIOLATION: AWS import in domain class: $FILE_PATH"
      echo " Move the AWS call to adapter/outbound/ and use a port interface."
      echo " This WILL fail ArchUnit CI."
    fi
  fi

  # ── Check 2: @Autowired field injection ───────────────────────────
  if echo "$FILE_PATH" | grep -qE '\.java$'; then
    if grep -qE '@Autowired\s+(private|protected|public)' "$FILE_PATH" 2>/dev/null; then
      echo "⚠️ FORBIDDEN: @Autowired field injection in $FILE_PATH"
      echo " Use constructor injection only."
    fi
  fi

  # ── Check 3: TODO stubs in implementation files ───────────────────
  if echo "$FILE_PATH" | grep -qE '\.(java|ts|tsx)$'; then
    if grep -qE '^\s*(//|/\*)\s*TODO' "$FILE_PATH" 2>/dev/null; then
      echo "⚠️ TODO stub in $FILE_PATH — rule: no half-finished code (CLAUDE.md §6)."
    fi
  fi

  # ── Check 4: UPDATE purchase_orders without version check ─────────
  if echo "$FILE_PATH" | grep -qE '\.(java|sql)$'; then
    if grep -qiE 'UPDATE.*purchase_orders' "$FILE_PATH" 2>/dev/null; then
      if ! grep -qiE 'version\s*=\s*:' "$FILE_PATH" 2>/dev/null; then
        echo "⚠️ ARCH VIOLATION: UPDATE on purchase_orders without version check in $FILE_PATH"
        echo " Add: AND version = :expectedVersion"
      fi
    fi
  fi

  # ── Check 5: Cross-schema SQL JOIN ────────────────────────────────
  if echo "$FILE_PATH" | grep -qE '\.(java|sql)$'; then
    if grep -qiE 'JOIN\s+(sales|inventory|replenishment|forecasting|supplier|promotions)\.' \
      "$FILE_PATH" 2>/dev/null; then
      echo "⚠️ ARCH VIOLATION: Cross-schema SQL JOIN detected in $FILE_PATH"
      echo " Merge results in Java using separate queries instead."
    fi
  fi
done <<< "$FILE_PATHS"

exit 0
