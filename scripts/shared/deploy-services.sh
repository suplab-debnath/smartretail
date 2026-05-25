#!/usr/bin/env bash
# Build Java services + Lambda, push Docker images to ECR, force ECS redeployment.
#
# Usage:
#   ./scripts/deploy-services.sh [OPTIONS]
#
# Options:
#   --env       <dev|prod>          Environment name (default: $SMARTRETAIL_ENV or dev)
#   --profile   <aws-profile>       AWS CLI profile (default: smartretail-dev)
#   --region    <region>            AWS region (default: us-east-1)
#   --services  <sis,ims,...>       Comma-separated subset of services to deploy
#                                   (default: all six: sis,ims,re,ars,dfs,sup)
#   --no-lambda                     Skip Lambda build and update
#   --skip-build                    Skip Maven build (use existing JARs in target/)
#   --skip-push                     Build Docker images locally but skip ECR push + ECS update
#   --wait                          Wait for every ECS service to reach steady state
#
# Examples:
#   ./scripts/deploy-services.sh --env dev --wait
#   ./scripts/deploy-services.sh --env dev --services sis,ims --skip-build
#   ./scripts/deploy-services.sh --env dev --no-lambda --wait

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
ENV="${SMARTRETAIL_ENV:-dev}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
SERVICES="sis ims re ars dfs sup"
SKIP_BUILD=false
SKIP_PUSH=false
DEPLOY_LAMBDA=true
WAIT=false

# ── Argument parsing ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --env)        ENV="$2";                    shift 2 ;;
    --profile)    PROFILE="$2";               shift 2 ;;
    --region)     REGION="$2";                shift 2 ;;
    --services)   SERVICES="${2//,/ }";        shift 2 ;;
    --no-lambda)  DEPLOY_LAMBDA=false;         shift   ;;
    --skip-build) SKIP_BUILD=true;             shift   ;;
    --skip-push)  SKIP_PUSH=true;              shift   ;;
    --wait)       WAIT=true;                   shift   ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ACCOUNT=$(aws sts get-caller-identity \
  --query Account --output text --profile "$PROFILE" 2>/dev/null)
ECR_PREFIX="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
CLUSTER="smartretail-${ENV}"

echo "=================================================="
echo " SmartRetail — Deploy Services"
echo " env:      ${ENV}"
echo " cluster:  ${CLUSTER}"
echo " services: ${SERVICES}"
echo " lambda:   ${DEPLOY_LAMBDA}"
echo "=================================================="

# ── 1. Maven build ────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
  echo ""
  echo "▶  Building service JARs (Maven)…"
  mvn clean package -DskipTests \
    -pl backend/services/sis,backend/services/ims,backend/services/re,backend/services/ars,backend/services/dfs,backend/services/sup \
    -am --no-transfer-progress

  if [[ "$DEPLOY_LAMBDA" == true ]]; then
    echo "▶  Building Lambda JAR (Maven)…"
    mvn clean package -DskipTests \
      -pl backend/adapters/kinesis-consumer \
      --no-transfer-progress
  fi
else
  echo "▶  Skipping Maven build (--skip-build)"
fi

# ── 2. ECR authentication ──────────────────────────────────────────────────────
if [[ "$SKIP_PUSH" == false ]]; then
  echo ""
  echo "▶  Authenticating with ECR (${ECR_PREFIX})…"
  aws ecr get-login-password --region "$REGION" --profile "$PROFILE" \
    | docker login --username AWS --password-stdin "$ECR_PREFIX"
fi

# ── 3. Services: build image → push → force ECS deployment ───────────────────
for SVC in $SERVICES; do
  echo ""
  echo "── ${SVC} ──────────────────────────────────────────"

  echo "▶  docker build backend/services/${SVC}/ (linux/arm64)"
  docker buildx build --platform linux/arm64 -t "smartretail-${SVC}:local" "backend/services/${SVC}/"

  if [[ "$SKIP_PUSH" == false ]]; then
    REPO_URI="${ECR_PREFIX}/smartretail-${SVC}-${ENV}:latest"
    echo "▶  Pushing → ${REPO_URI}"
    docker tag "smartretail-${SVC}:local" "$REPO_URI"
    docker push "$REPO_URI"

    echo "▶  Forcing ECS redeployment…"
    aws ecs update-service \
      --cluster  "$CLUSTER" \
      --service  "smartretail-${SVC}-${ENV}" \
      --force-new-deployment \
      --profile  "$PROFILE" \
      --output   text > /dev/null
    echo "   ✅ ${SVC} redeployment triggered"
  fi
done

# ── 4. Lambda: build image → push → update function code ─────────────────────
if [[ "$DEPLOY_LAMBDA" == true && "$SKIP_PUSH" == false ]]; then
  echo ""
  echo "── kinesis-consumer Lambda ─────────────────────────"

  echo "▶  docker build backend/adapters/kinesis-consumer/ (linux/arm64)"
  docker buildx build --platform linux/arm64 -t smartretail-kinesis-consumer:local backend/adapters/kinesis-consumer/

  LAMBDA_URI="${ECR_PREFIX}/smartretail-kinesis-consumer-${ENV}:latest"
  echo "▶  Pushing → ${LAMBDA_URI}"
  docker tag smartretail-kinesis-consumer:local "$LAMBDA_URI"
  docker push "$LAMBDA_URI"

  echo "▶  Updating Lambda function code…"
  aws lambda update-function-code \
    --function-name "smartretail-kinesis-consumer-${ENV}" \
    --image-uri     "$LAMBDA_URI" \
    --profile       "$PROFILE" \
    --output        text > /dev/null
  echo "   ✅ Lambda updated"
fi

# ── 5. Wait for ECS steady state (optional) ───────────────────────────────────
if [[ "$WAIT" == true && "$SKIP_PUSH" == false ]]; then
  echo ""
  echo "▶  Waiting for ECS services to reach steady state…"
  for SVC in $SERVICES; do
    echo "   waiting: smartretail-${SVC}-${ENV}…"
    aws ecs wait services-stable \
      --cluster  "$CLUSTER" \
      --services "smartretail-${SVC}-${ENV}" \
      --profile  "$PROFILE"
    echo "   ✅ ${SVC} stable"
  done
fi

echo ""
echo "=================================================="
echo "✅  Service deployment complete  (env: ${ENV})"
echo "=================================================="
