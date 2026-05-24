#!/usr/bin/env bash
# Merges per-MFE lcov.info files with path prefixing + robust error handling.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/../.."
OUT_DIR="$ROOT/dist/coverage/frontend"
HTML_DIR="$OUT_DIR/html"

MFE_DIRS=(store-manager sc-planner executive supplier demo)

mkdir -p "$OUT_DIR" "$HTML_DIR"

MERGED="$OUT_DIR/lcov.info"
> "$MERGED"

FOUND=0
for mfe in "${MFE_DIRS[@]}"; do
  lcov_file="$ROOT/mfe/$mfe/coverage/lcov.info"
  if [[ -f "$lcov_file" ]]; then
    echo "  + mfe/$mfe/coverage/lcov.info"
    # Prefix paths: store-manager/src/... etc.
    sed "s|SF:|SF:$mfe/|g" "$lcov_file" >> "$MERGED"
    FOUND=$((FOUND + 1))
  else
    echo "! WARNING: $lcov_file not found"
  fi
done

if [[ $FOUND -eq 0 ]]; then
  echo "ERROR: No lcov.info files found."
  exit 1
fi

echo "Merged $FOUND lcov.info files → $MERGED"

# Summary
if command -v lcov &>/dev/null; then
  echo -e "\nCoverage Summary:"
  lcov --summary "$MERGED" 2>&1 | grep -E "(lines|functions|branches)" || true
fi

# HTML Report - Most tolerant settings
if command -v genhtml &>/dev/null; then
  echo -e "\nGenerating HTML report..."
  genhtml "$MERGED" \
    --output-directory "$HTML_DIR" \
    --title "SmartRetail MFE Coverage" \
    --no-branch-coverage \
    --ignore-errors inconsistent,source,corrupt \
    --synthesize-missing \
    --quiet \
    && echo "HTML report generated successfully!" \
    || echo "HTML report generated with some warnings (this is normal)"
else
  echo "genhtml not found — skipping HTML report"
fi

echo -e "\n Frontend coverage report ready:"
echo "   LCOV:  dist/coverage/frontend/lcov.info"
echo "   HTML:  dist/coverage/frontend/html/index.html"