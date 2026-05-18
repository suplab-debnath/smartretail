#!/usr/bin/env bash
# Merges per-MFE lcov.info files into a single consolidated report.
# Uses plain concatenation (compatible with LCOV 1.x and 2.x) rather than
# lcov --add-tracefile, which fails on lcov 2.0 when branch data is absent
# from V8-generated coverage files.
#
# Requires genhtml (ships with the lcov package) only for HTML generation.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
OUT_DIR="$ROOT/dist/coverage/frontend"
MFE_DIRS=(store-manager sc-planner executive demo)

mkdir -p "$OUT_DIR/html"

MERGED="$OUT_DIR/lcov.info"
> "$MERGED"   # truncate / create

FOUND=0
for mfe in "${MFE_DIRS[@]}"; do
  lcov_file="$ROOT/mfe/$mfe/coverage/lcov.info"
  if [[ -f "$lcov_file" ]]; then
    cat "$lcov_file" >> "$MERGED"
    echo "  + mfe/$mfe/coverage/lcov.info"
    FOUND=$((FOUND + 1))
  else
    echo "  ! WARNING: $lcov_file not found — run 'npm run test:coverage' in mfe/$mfe first"
  fi
done

if [[ $FOUND -eq 0 ]]; then
  echo "ERROR: No lcov.info files found. Run 'make coverage-frontend' to generate them."
  exit 1
fi

echo "Merged $FOUND lcov.info files → $MERGED"

# Print a summary using lcov if available
if command -v lcov &>/dev/null; then
  echo ""
  lcov --summary "$MERGED" 2>&1 || true
fi

# Generate HTML report if genhtml is available
if command -v genhtml &>/dev/null; then
  echo ""
  echo "Generating HTML report..."
  # --no-branch-coverage: skip branch stats when V8 lcov has no BRDA lines
  genhtml "$MERGED" \
    --output-directory "$OUT_DIR/html" \
    --title "SmartRetail MFE Coverage" \
    --no-branch-coverage \
    --quiet \
    || echo "WARNING: genhtml exited non-zero — HTML report may be incomplete"
else
  echo "INFO: genhtml not found — skipping HTML report (install lcov package)"
fi

echo ""
echo "Frontend coverage report:"
echo "  LCOV:  dist/coverage/frontend/lcov.info"
echo "  HTML:  dist/coverage/frontend/html/index.html"
