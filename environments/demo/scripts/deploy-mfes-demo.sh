#!/usr/bin/env bash
# Build React MFEs and sync dist/ to S3.
# cdk-demo target: sc-planner only, served as an S3 static website (HTTP, no CloudFront).
#
# Usage:
#   ./environments/demo/scripts/deploy-mfes-demo.sh [OPTIONS]
#
# Options:
#   --env       <dev|prod>      Environment name (default: $SMARTRETAIL_ENV or dev)
#   --profile   <aws-profile>   AWS CLI profile (default: smartretail-dev)
#   --mfes      <sc-planner>    Comma-separated MFEs to deploy (default: sc-planner)
#   --skip-build                Skip npm build (use existing dist/)
#
# Examples:
#   ./environments/demo/scripts/deploy-mfes-demo.sh --env dev
#   ./environments/demo/scripts/deploy-mfes-demo.sh --env dev --skip-build

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
ENV="${SMARTRETAIL_ENV:-dev}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
MFES="sc-planner"
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

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

ACCOUNT=$(aws sts get-caller-identity \
  --query Account --output text --profile "$PROFILE" 2>/dev/null)

echo "=================================================="
echo " SmartRetail — Deploy MFEs (demo)"
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

  # Generate runtime config.js from SSM — overwrites the empty placeholder
  # that ships with the build. Must happen after build, before S3 sync.
  echo "▶  Generating config.js from SSM"
  ALB_URL=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/api/endpoint" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)
  COGNITO_POOL_ID=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/cognito/internal-pool-id" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)
  COGNITO_CLIENT_ID=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/cognito/internal-client-id" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)

  cat > "mfe/${MFE}/dist/config.js" <<CONFIGEOF
window.SMARTRETAIL_CONFIG = {
  apiGatewayEndpoint: '${ALB_URL}',
  cognitoPoolId:      '${COGNITO_POOL_ID}',
  cognitoClientId:    '${COGNITO_CLIENT_ID}',
  cognitoDomain:      '',
  env:                '${ENV}',
};
CONFIGEOF
  echo "   apiGatewayEndpoint: ${ALB_URL}"

  # S3 sync
  BUCKET="smartretail-mfe-${ENV}-${MFE}-${ACCOUNT}"
  echo "▶  Syncing → s3://${BUCKET}/"
  aws s3 sync "mfe/${MFE}/dist/" "s3://${BUCKET}/" \
    --delete \
    --profile "$PROFILE"

  # Show the S3 website URL from Parameter Store (written by Min-HostingStack)
  SITE_URL=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/hosting/${MFE}-url" \
    --query Parameter.Value --output text \
    --profile "$PROFILE" 2>/dev/null || true)

  echo "   ✅ ${MFE} deployed (S3 static website, HTTP)"
  [[ -n "$SITE_URL" && "$SITE_URL" != "None" ]] && echo "   🌐  ${SITE_URL}"
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
