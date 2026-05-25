#!/usr/bin/env bash
# Usage: chmod +x environments/dev/scripts/deploy-cdk.sh && ./environments/dev/scripts/deploy-cdk.sh
# Deploys the demo/dev stack (environments/demo/infra — SQS-only, default VPC, Min-* stacks).

set -euo pipefail

# =========================================================
# SmartRetail CDK Deployment Script
# Environment: dev (Min-* stacks, SQS POS ingestion)
# Region: us-east-1
# =========================================================

export SMARTRETAIL_ENV=dev
export AWS_ACCOUNT_ID=123456789012
export CDK_DEFAULT_ACCOUNT=$AWS_ACCOUNT_ID
export CDK_DEFAULT_REGION=us-east-1

CDK_DIR="$(cd "$(dirname "$0")/../infra" && pwd)"

cd "$CDK_DIR"

echo "=================================================="
echo "Installing dependencies"
echo "=================================================="

npm install

echo "=================================================="
echo "Building CDK"
echo "=================================================="

npm run build

echo "=================================================="
echo "Bootstrapping CDK"
echo "=================================================="

npx cdk bootstrap aws://$AWS_ACCOUNT_ID/us-east-1

echo "=================================================="
echo "Synthesizing stacks"
echo "=================================================="

npx cdk synth

echo "=================================================="
echo "Deploying stacks"
echo "=================================================="

npx cdk deploy \
  Min-NetworkStack \
  Min-DataStack \
  Min-MessagingStack \
  Min-IdentityStack \
  Min-ComputeStack \
  Min-ApiStack \
  Min-HostingStack \
  --require-approval never

echo "=================================================="
echo "Deployment completed"
echo "=================================================="
