#!/usr/bin/env bash
# Usage: chmod +x scripts/deploy-cdk.sh && ./scripts/deploy-cdk.sh

set -euo pipefail

# =========================================================
# SmartRetail CDK Deployment Script
# Environment: dev
# Region: us-east-1
# =========================================================

export SMARTRETAIL_ENV=dev
export AWS_ACCOUNT_ID=123456789012
export CDK_DEFAULT_ACCOUNT=$AWS_ACCOUNT_ID
export CDK_DEFAULT_REGION=us-east-1

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
CDK_DIR="$PROJECT_ROOT/infra/cdk"

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

cdk bootstrap aws://$AWS_ACCOUNT_ID/us-east-1

echo "=================================================="
echo "Synthesizing stacks"
echo "=================================================="

cdk synth

echo "=================================================="
echo "Deploying stacks"
echo "=================================================="

cdk deploy \
  NetworkStack \
  DataStack \
  MessagingStack \
  IdentityStack \
  ComputeStack \
  ApiStack \
  HostingStack \
  --require-approval never

echo "=================================================="
echo "Deployment completed"
echo "=================================================="