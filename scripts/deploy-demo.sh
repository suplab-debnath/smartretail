#!/usr/bin/env bash
# deploy-demo.sh — end-to-end SC Planner demo deployment (cdk-demo stack)
#
# Usage:
#   ./scripts/deploy-demo.sh
#   SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev ./scripts/deploy-demo.sh
#
# Optional context:
#   CDK_CONTEXT_alertEmail=you@example.com ./scripts/deploy-demo.sh
#     — enables CloudWatch alarm email notifications
set -euo pipefail

DEMO_ENV="${SMARTRETAIL_ENV:-demo}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ALERT_EMAIL="${CDK_CONTEXT_alertEmail:-}"
DEMO_SERVICES=(ims re ars dfs sup)

CDK_DIR="$(cd "$(dirname "$0")/../infra/cdk-demo" && pwd)"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

hr() { printf '\n══ %s ══\n\n' "$1"; }

hr "SmartRetail SC Planner Demo — Deploy"
echo "  Environment : $DEMO_ENV"
echo "  AWS Profile : $PROFILE"
echo "  Region      : $REGION"
echo "  Alert email : ${ALERT_EMAIL:-'(not set — add CDK_CONTEXT_alertEmail to enable)'}"

# ── 1. CDK bootstrap (idempotent) ─────────────────────────────────────────────
hr "Step 1 / 5 — CDK bootstrap"
cd "$CDK_DIR"
npm install --silent
ACCOUNT=$(AWS_PROFILE="$PROFILE" aws sts get-caller-identity --query Account --output text)
AWS_PROFILE="$PROFILE" npx cdk bootstrap "aws://${ACCOUNT}/${REGION}"

# ── 2. CDK deploy all Min-* stacks ────────────────────────────────────────────
hr "Step 2 / 5 — CDK deploy (Min-* stacks)"
ALERT_CTX=""
if [[ -n "$ALERT_EMAIL" ]]; then
  ALERT_CTX="-c alertEmail=${ALERT_EMAIL}"
fi
AWS_PROFILE="$PROFILE" SMARTRETAIL_ENV="$DEMO_ENV" \
  npx cdk deploy --all --require-approval never $ALERT_CTX

# ── 3. Build and push service images ──────────────────────────────────────────
hr "Step 3 / 5 — Build & push service images (${DEMO_SERVICES[*]})"
cd "$ROOT_DIR"
ECR_PREFIX="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

AWS_PROFILE="$PROFILE" aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ECR_PREFIX"

for svc in "${DEMO_SERVICES[@]}"; do
  echo "  → Building $svc…"
  docker buildx build --platform linux/arm64 \
    -t "smartretail-${svc}:local" "services/${svc}/"
  docker tag "smartretail-${svc}:local" \
    "${ECR_PREFIX}/smartretail-${svc}-${DEMO_ENV}:latest"
  docker push "${ECR_PREFIX}/smartretail-${svc}-${DEMO_ENV}:latest"
done

# Force ECS redeployment so tasks pick up the new images
for svc in "${DEMO_SERVICES[@]}"; do
  echo "  → Redeploying ECS service: smartretail-${svc}-${DEMO_ENV}"
  AWS_PROFILE="$PROFILE" aws ecs update-service \
    --cluster "smartretail-${DEMO_ENV}" \
    --service "smartretail-${svc}-${DEMO_ENV}" \
    --force-new-deployment \
    --region "$REGION" \
    --output none
done

# ── 4. DB migrations (includes seed data via V7) ───────────────────────────────
hr "Step 4 / 5 — DB migrations + seed data"
AWS_PROFILE="$PROFILE" SMARTRETAIL_ENV="$DEMO_ENV" \
  "$ROOT_DIR/scripts/run-flyway-aws.sh" "$DEMO_ENV"

# ── 5. SC Planner MFE ─────────────────────────────────────────────────────────
hr "Step 5 / 5 — Build & deploy SC Planner MFE"
cd "$ROOT_DIR/mfe/sc-planner"
npm install --silent
npm run build
BUCKET_NAME=$(AWS_PROFILE="$PROFILE" aws ssm get-parameter \
  --name "/smartretail/${DEMO_ENV}/hosting/sc-planner-bucket-name" \
  --query Parameter.Value --output text)
AWS_PROFILE="$PROFILE" aws s3 sync dist/ "s3://${BUCKET_NAME}/" --delete

# ── Summary ───────────────────────────────────────────────────────────────────
hr "Done"
SC_URL=$(AWS_PROFILE="$PROFILE" aws ssm get-parameter \
  --name "/smartretail/${DEMO_ENV}/hosting/sc-planner-url" \
  --query Parameter.Value --output text 2>/dev/null || echo "pending")
ALB_URL=$(AWS_PROFILE="$PROFILE" aws ssm get-parameter \
  --name "/smartretail/${DEMO_ENV}/api/endpoint" \
  --query Parameter.Value --output text 2>/dev/null || echo "pending")
CW_URL="https://${REGION}.console.aws.amazon.com/cloudwatch/home?region=${REGION}#dashboards:name=SmartRetail-${DEMO_ENV}-Ops"

echo "  ✅  SC Planner  : $SC_URL"
echo "  ✅  API Endpoint: $ALB_URL"
echo "  ✅  CW Dashboard: $CW_URL"
echo ""
echo "  Cognito users not yet created."
echo "  Run: AWS_PROFILE=${PROFILE} SMARTRETAIL_ENV=${DEMO_ENV} ./scripts/create-cognito-users.sh ${DEMO_ENV}"
echo ""
echo "  To tear down: SMARTRETAIL_ENV=${DEMO_ENV} AWS_PROFILE=${PROFILE} make demo-destroy"
