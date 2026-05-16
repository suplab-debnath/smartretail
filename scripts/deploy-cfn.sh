#!/usr/bin/env bash
# Usage: chmod +x scripts/deploy-cfn.sh && ./scripts/deploy-cfn.sh

set -euo pipefail

# =========================================================
# SmartRetail CloudFormation Deployment
# =========================================================

export AWS_DEFAULT_REGION=us-east-1

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE_DIR="$PROJECT_ROOT/infra/cfn"

cd "$CFN_DIR"

ENV=dev

STACK_PREFIX="smartretail"

function deploy_stack() {

  local STACK_NAME=$1
  local TEMPLATE=$2

  echo "=================================================="
  echo "Deploying ${STACK_NAME}"
  echo "=================================================="

  aws cloudformation deploy \
    --stack-name ${STACK_NAME} \
    --template-file ${TEMPLATE_DIR}/${TEMPLATE} \
    --parameter-overrides SrEnv=${ENV} \
    --capabilities CAPABILITY_NAMED_IAM
}

deploy_stack "${STACK_PREFIX}-network-${ENV}" "01_network-stack.yaml"
deploy_stack "${STACK_PREFIX}-data-${ENV}" "02_data-stack.yaml"
deploy_stack "${STACK_PREFIX}-messaging-${ENV}" "03_messaging-stack.yaml"
deploy_stack "${STACK_PREFIX}-identity-${ENV}" "04_identity-stack.yaml"
deploy_stack "${STACK_PREFIX}-compute-${ENV}" "05_compute-stack.yaml"
deploy_stack "${STACK_PREFIX}-api-${ENV}" "06_api-stack.yaml"

echo "=================================================="
echo "All stacks deployed"
echo "=================================================="