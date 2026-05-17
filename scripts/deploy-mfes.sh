#!/usr/bin/env bash
# Build React MFEs, sync dist/ to S3, and invalidate CloudFront.
#
# Usage:
#   ./scripts/deploy-mfes.sh [OPTIONS]
#
# Options:
#   --env       <dev|prod>                    Environment name (default: $SMARTRETAIL_ENV or dev)
#   --profile   <aws-profile>                 AWS CLI profile (default: smartretail-dev)
#   --mfes      <store-manager,sc-planner,…>  Comma-separated MFEs to deploy
#                                             (default: all four)
#   --skip-build                              Skip npm build (use existing dist/)
#
# Examples:
#   ./scripts/deploy-mfes.sh --env dev
#   ./scripts/deploy-mfes.sh --env dev --mfes store-manager,executive
#   ./scripts/deploy-mfes.sh --env dev --mfes demo --skip-build

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
ENV="${SMARTRETAIL_ENV:-dev}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
MFES="store-manager sc-planner executive demo"
SKIP_BUILD=false

# ── Argument parsing ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --env)        ENV="$2";               shift 2 ;;
    --profile)    PROFILE="$2";           shift 2 ;;
    --mfes)       MFES="${2//,/ }";       shift 2 ;;
    --skip-build) SKIP_BUILD=true;        shift   ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ACCOUNT=$(aws sts get-caller-identity \
  --query Account --output text --profile "$PROFILE" 2>/dev/null)

echo "=================================================="
echo " SmartRetail — Deploy MFEs"
echo " env:    ${ENV}"
echo " mfes:   ${MFES}"
echo "=================================================="

FAILED=()

for MFE in $MFES; do
  echo ""
  echo "── ${MFE} ──────────────────────────────────────────"

  # Build
  if [[ "$SKIP_BUILD" == false ]]; then
    echo "▶  npm install + build  (mfe/${MFE})"
    (cd "mfe/${MFE}" && npm install --silent && npm run build)
  else
    echo "▶  Skipping build (--skip-build)"
  fi

  # Verify dist/ exists
  if [[ ! -d "mfe/${MFE}/dist" ]]; then
    echo "   ❌  mfe/${MFE}/dist not found — was the build skipped or did it fail?" >&2
    FAILED+=("$MFE")
    continue
  fi

  # S3 sync
  BUCKET="smartretail-mfe-${ENV}-${MFE}-${ACCOUNT}"
  echo "▶  Syncing → s3://${BUCKET}/"
  aws s3 sync "mfe/${MFE}/dist/" "s3://${BUCKET}/" \
    --delete \
    --profile "$PROFILE"

  # CloudFront invalidation
  CF_ID=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/cloudfront/${MFE}-distribution-id" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)

  if [[ -n "$CF_ID" && "$CF_ID" != "None" ]]; then
    echo "▶  Invalidating CloudFront distribution ${CF_ID}…"
    INVALIDATION_ID=$(aws cloudfront create-invalidation \
      --distribution-id "$CF_ID" \
      --paths "/*" \
      --profile "$PROFILE" \
      --query Invalidation.Id --output text)
    echo "   ✅ ${MFE} deployed  (invalidation: ${INVALIDATION_ID})"

    CF_URL=$(aws ssm get-parameter \
      --name "/smartretail/${ENV}/cloudfront/${MFE}-url" \
      --query Parameter.Value --output text \
      --profile "$PROFILE" 2>/dev/null || true)
    [[ -n "$CF_URL" ]] && echo "   🌐 ${CF_URL}"
  else
    echo "   ⚠️   No CloudFront distribution found in SSM for '${MFE}'"
    echo "        Run 'make aws-deploy-hosting' to create distributions first."
    echo "   ✅ ${MFE} synced to S3 (no invalidation)"
  fi
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
if [[ ${#FAILED[@]} -gt 0 ]]; then
  echo "⚠️   Completed with errors — failed MFEs: ${FAILED[*]}"
  exit 1
else
  echo "✅  MFE deployment complete  (env: ${ENV})"
fi
echo "=================================================="
