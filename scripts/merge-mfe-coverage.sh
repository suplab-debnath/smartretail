#!/usr/bin/env bash
# Merges per-MFE lcov.info files into a single consolidated report.
# Requires: lcov (apt install lcov / brew install lcov)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
OUT_DIR="$ROOT/dist/coverage/frontend"

if ! command -v lcov &>/dev/null; then
  echo "ERROR: lcov is not installed."
  echo "  Ubuntu/Debian: sudo apt-get install -y lcov"
  echo "  macOS:         brew install lcov"
  exit 1
fi

if ! command -v genhtml &>/dev/null; then
  echo "ERROR: genhtml is not installed (ships with lcov package)."
  exit 1
fi

mkdir -p "$OUT_DIR/html"

TRACEFILES=()
MFE_DIRS=(store-manager sc-planner executive demo)

for mfe in "${MFE_DIRS[@]}"; do
  lcov_file="$ROOT/mfe/$mfe/coverage/lcov.info"
  if [[ -f "$lcov_file" ]]; then
    TRACEFILES+=("--add-tracefile" "$lcov_file")
    echo "  + mfe/$mfe/coverage/lcov.info"
  else
    echo "  ! WARNING: $lcov_file not found — run 'npm run test:coverage' in mfe/$mfe first"
  fi
done

if [[ ${#TRACEFILES[@]} -eq 0 ]]; then
  echo "ERROR: No lcov.info files found. Run 'make coverage-frontend' to generate them."
  exit 1
fi

echo "Merging ${#TRACEFILES[@]} trace files..."
lcov "${TRACEFILES[@]}" --output-file "$OUT_DIR/lcov.info"

echo "Generating HTML report..."
genhtml "$OUT_DIR/lcov.info" \
  --output-directory "$OUT_DIR/html" \
  --title "SmartRetail MFE Coverage" \
  --legend \
  --show-details \
  --quiet

echo ""
echo "Frontend coverage report:"
echo "  HTML:  dist/coverage/frontend/html/index.html"
echo "  LCOV:  dist/coverage/frontend/lcov.info"
