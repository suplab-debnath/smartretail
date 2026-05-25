#!/usr/bin/env bash
# Usage: ./environments/dev/scripts/deploy-cdk.sh [--env <env>] [--profile <profile>] [--region <region>]
# Deploys Dev-* CDK stacks (environments/dev/infra — Kinesis, 2-AZ VPC).

set -euo pipefail

# =========================================================
# Defaults — override via env vars or flags
# =========================================================

ENV="${SMARTRETAIL_ENV:-dev}"
PROFILE="${AWS_PROFILE:-smartretail-dev}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)     ENV="$2";     shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --region)  REGION="$2";  shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

export SMARTRETAIL_ENV="$ENV"
export AWS_DEFAULT_REGION="$REGION"

ACCOUNT_ID=$(AWS_PROFILE="$PROFILE" aws sts get-caller-identity \
  --query Account --output text)

export CDK_DEFAULT_ACCOUNT="$ACCOUNT_ID"
export CDK_DEFAULT_REGION="$REGION"

CDK_DIR="$(cd "$(dirname "$0")/../infra" && pwd)"

echo "=================================================="
echo "SmartRetail CDK Deployment"
echo "  Environment : $ENV"
echo "  Account     : $ACCOUNT_ID"
echo "  Region      : $REGION"
echo "  Profile     : $PROFILE"
echo "  CDK dir     : $CDK_DIR"
echo "=================================================="

cd "$CDK_DIR"

echo "--- Installing dependencies ---"
npm install --silent

echo "--- Building CDK ---"
npm run build

echo "--- Bootstrapping CDK ---"
AWS_PROFILE="$PROFILE" npx cdk bootstrap "aws://$ACCOUNT_ID/$REGION"

echo "--- Synthesizing stacks ---"
AWS_PROFILE="$PROFILE" npx cdk synth

echo "--- Deploying stacks ---"
AWS_PROFILE="$PROFILE" npx cdk deploy \
  Dev-NetworkStack \
  Dev-DataStack \
  Dev-MessagingStack \
  Dev-IdentityStack \
  Dev-ComputeStack \
  Dev-ApiStack \
  Dev-HostingStack \
  Dev-MonitoringStack \
  --require-approval never

echo "=================================================="
echo "Deployment complete (env: $ENV)"
echo "=================================================="
