#!/usr/bin/env bash
# Build React MFEs, sync dist/ to S3, and invalidate the shared CloudFront distribution.
#
# Usage:
#   ./scripts/deploy-mfes.sh [OPTIONS]
#
# Options:
#   --env       <dev|prod>                              Environment name (default: $SMARTRETAIL_ENV or dev)
#   --profile   <aws-profile>                           AWS CLI profile (default: smartretail-dev)
#   --mfes      <store-manager,sc-planner,executive,…>  Comma-separated MFEs to deploy (default: all four)
#   --skip-build                                        Skip npm build (use existing dist/)
#
# Examples:
#   ./scripts/deploy-mfes.sh --env dev
#   ./scripts/deploy-mfes.sh --env dev --mfes store-manager,executive

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
ENV="${SMARTRETAIL_ENV:-dev}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
MFES="store-manager sc-planner executive supplier"
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

# ── Shared SSM lookups ────────────────────────────────────────────────────────
API_ENDPOINT=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/api/endpoint" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)

# Internal pool — used by sc-planner, store-manager, executive
INTERNAL_POOL_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/internal-pool-id" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)
INTERNAL_CLIENT_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/internal-client-id" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)
INTERNAL_DOMAIN=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/internal-domain" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)

# Supplier pool — used by supplier MFE only
SUPPLIER_POOL_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/supplier-pool-id" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)
SUPPLIER_CLIENT_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/supplier-client-id" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)
SUPPLIER_DOMAIN=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/cognito/supplier-domain" \
  --query Parameter.Value --output text \
  --profile "$PROFILE" 2>/dev/null || true)

echo "=================================================="
echo " SmartRetail — Deploy MFEs"
echo " env:    ${ENV}"
echo " mfes:   ${MFES}"
echo "=================================================="

FAILED=()

for MFE in $MFES; do
  echo ""
  echo "── ${MFE} ──────────────────────────────────────────"

  # Build with path-based base so assets reference /{mfe}/ correctly
  if [[ "$SKIP_BUILD" == false ]]; then
    echo "▶  npm install + build  (mfe/${MFE})  base=/${MFE}/"
    (cd "mfe/${MFE}" && npm install --silent && VITE_BASE_PATH="/${MFE}/" npm run build)
  else
    echo "▶  Skipping build (--skip-build)"
  fi

  if [[ ! -d "mfe/${MFE}/dist" ]]; then
    echo "   ❌  mfe/${MFE}/dist not found — build failed?" >&2
    FAILED+=("$MFE")
    continue
  fi

  # Select Cognito pool: supplier MFE uses supplier pool, all others use internal pool
  if [[ "$MFE" == "supplier" ]]; then
    COGNITO_POOL_ID="$SUPPLIER_POOL_ID"
    COGNITO_CLIENT_ID="$SUPPLIER_CLIENT_ID"
    COGNITO_DOMAIN="$SUPPLIER_DOMAIN"
  else
    COGNITO_POOL_ID="$INTERNAL_POOL_ID"
    COGNITO_CLIENT_ID="$INTERNAL_CLIENT_ID"
    COGNITO_DOMAIN="$INTERNAL_DOMAIN"
  fi

  # Generate runtime config.js from SSM — overwrites the empty placeholder
  echo "▶  Generating config.js from SSM"
  cat > "mfe/${MFE}/dist/config.js" <<CONFIGEOF
window.SMARTRETAIL_CONFIG = {
  apiGatewayEndpoint: '${API_ENDPOINT}',
  cognitoPoolId:      '${COGNITO_POOL_ID}',
  cognitoClientId:    '${COGNITO_CLIENT_ID}',
  cognitoDomain:      '${COGNITO_DOMAIN}',
  env:                '${ENV}',
};
CONFIGEOF
  echo "   apiGatewayEndpoint: ${API_ENDPOINT}"
  echo "   cognitoDomain:      ${COGNITO_DOMAIN}"

  # S3 sync
  BUCKET="smartretail-mfe-${ENV}-${MFE}-${ACCOUNT}"
  echo "▶  Syncing → s3://${BUCKET}/"
  aws s3 sync "mfe/${MFE}/dist/" "s3://${BUCKET}/" \
    --delete \
    --profile "$PROFILE"

  echo "   ✅ ${MFE} synced"
done

# ── CloudFront invalidation (single distribution covers all MFEs) ─────────────
if [[ ${#FAILED[@]} -eq 0 ]]; then
  CF_ID=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/hosting/cloudfront-distribution-id" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)

  if [[ -n "$CF_ID" && "$CF_ID" != "None" ]]; then
    echo ""
    echo "▶  Invalidating CloudFront distribution ${CF_ID}…"
    INVALIDATION_ID=$(aws cloudfront create-invalidation \
      --distribution-id "$CF_ID" \
      --paths "/*" \
      --profile "$PROFILE" \
      --query Invalidation.Id --output text)
    echo "   ✅ Invalidation created: ${INVALIDATION_ID}"

    CF_URL=$(aws ssm get-parameter \
      --name "/smartretail/${ENV}/hosting/cloudfront-url" \
      --query Parameter.Value --output text \
      --profile "$PROFILE" 2>/dev/null || true)
    [[ -n "$CF_URL" ]] && echo "   🌐  ${CF_URL}"
  else
    echo "   ⚠️   No CloudFront distribution found in SSM — run 'make aws-deploy-infra' first."
  fi
fi

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
