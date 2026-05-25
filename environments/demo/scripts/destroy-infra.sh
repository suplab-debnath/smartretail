#!/usr/bin/env bash
# Usage: ./environments/demo/scripts/destroy-infra.sh [--env <env>] [--profile <profile>] [--region <region>]
# Full teardown: CDK stacks + S3 + ECR + CloudFront + SSM + logs.

set -euo pipefail

# =========================================================
# Defaults — override via env vars or flags
# =========================================================

ENV="${SMARTRETAIL_ENV:-demo}"
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

STACKS=(
  Min-MonitoringStack
  Min-HostingStack
  Min-ApiStack
  Min-ComputeStack
  Min-IdentityStack
  Min-MessagingStack
  Min-DataStack
  Min-NetworkStack
)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CDK_DIR="${SCRIPT_DIR}/../infra"

echo "=================================================="
echo "SmartRetail Demo Teardown"
echo "  Environment : $ENV"
echo "  Account     : $ACCOUNT_ID"
echo "  Region      : $REGION"
echo "  Profile     : $PROFILE"
echo "=================================================="

# =========================================================
# Destroy CDK stacks
# =========================================================

cd "$CDK_DIR"
for STACK in "${STACKS[@]}"; do
  echo "Destroying ${STACK}"
  AWS_PROFILE="$PROFILE" npx cdk destroy "${STACK}" --force || true
done
cd "$SCRIPT_DIR/../../.."

# =========================================================
# Disable and delete orphaned CloudFront distributions
# =========================================================

CF_DISTS=$(AWS_PROFILE="$PROFILE" aws cloudfront list-distributions \
  --query "DistributionList.Items[?contains(Comment, 'SmartRetail')].Id" \
  --output text 2>/dev/null || true)

for DIST_ID in $CF_DISTS; do
  echo "Disabling CloudFront distribution: $DIST_ID"

  ETAG=$(AWS_PROFILE="$PROFILE" aws cloudfront get-distribution-config \
    --id "$DIST_ID" --query ETag --output text)

  DISABLED_CONFIG=$(AWS_PROFILE="$PROFILE" aws cloudfront get-distribution-config \
    --id "$DIST_ID" --query DistributionConfig \
    | python3 -c "import sys,json; d=json.load(sys.stdin); d['Enabled']=False; print(json.dumps(d))")

  AWS_PROFILE="$PROFILE" aws cloudfront update-distribution \
    --id "$DIST_ID" \
    --distribution-config "$DISABLED_CONFIG" \
    --if-match "$ETAG" || true

  echo "Waiting for distribution $DIST_ID to deploy (this may take a few minutes)..."
  AWS_PROFILE="$PROFILE" aws cloudfront wait distribution-deployed \
    --id "$DIST_ID" || true

  ETAG=$(AWS_PROFILE="$PROFILE" aws cloudfront get-distribution-config \
    --id "$DIST_ID" --query ETag --output text)

  AWS_PROFILE="$PROFILE" aws cloudfront delete-distribution \
    --id "$DIST_ID" --if-match "$ETAG" || true
done

# =========================================================
# Empty S3 Buckets (app + CDK bootstrap)
# CDK bootstrap bucket has DeletionPolicy: Retain — CloudFormation leaves it
# behind when CDKToolkit is deleted, so we must remove it explicitly.
# =========================================================

BOOTSTRAP_BUCKET=$(AWS_PROFILE="$PROFILE" aws s3api list-buckets \
  --query "Buckets[?starts_with(Name, 'cdk-') && contains(Name, \`${ACCOUNT_ID}\`)].Name" \
  --output text 2>/dev/null || true)

if [[ -n "$BOOTSTRAP_BUCKET" ]]; then
  echo "Cleaning CDK bootstrap bucket: $BOOTSTRAP_BUCKET"
  AWS_PROFILE="$PROFILE" aws s3 rm "s3://${BOOTSTRAP_BUCKET}" --recursive || true
  AWS_PROFILE="$PROFILE" aws s3api delete-bucket --bucket "$BOOTSTRAP_BUCKET" || true
fi

BUCKETS=$(AWS_PROFILE="$PROFILE" aws s3api list-buckets \
  --query "Buckets[?contains(Name, 'smartretail')].Name" \
  --output text)

for BUCKET in $BUCKETS; do
  echo "Cleaning bucket: $BUCKET"
  AWS_PROFILE="$PROFILE" aws s3 rm "s3://$BUCKET" --recursive || true
  AWS_PROFILE="$PROFILE" aws s3api delete-bucket --bucket "$BUCKET" || true
done

# =========================================================
# Delete ECR Images + Repositories
# =========================================================

REPOS=$(AWS_PROFILE="$PROFILE" aws ecr describe-repositories \
  --query "repositories[?contains(repositoryName, 'smartretail')].repositoryName" \
  --output text)

for REPO in $REPOS; do
  echo "Deleting ECR repo: $REPO"
  AWS_PROFILE="$PROFILE" aws ecr delete-repository \
    --repository-name "$REPO" --force || true
done

# =========================================================
# Delete CloudWatch Log Groups
# =========================================================

LOG_GROUPS=$(AWS_PROFILE="$PROFILE" aws logs describe-log-groups \
  --query "logGroups[?contains(logGroupName, 'smartretail')].logGroupName" \
  --output text)

for LG in $LOG_GROUPS; do
  echo "Deleting log group: $LG"
  AWS_PROFILE="$PROFILE" aws logs delete-log-group --log-group-name "$LG" || true
done

# =========================================================
# Delete SSM Parameters
# =========================================================

PARAMS=$(AWS_PROFILE="$PROFILE" aws ssm describe-parameters \
  --query "Parameters[?contains(Name, 'smartretail')].Name" \
  --output text)

for PARAM in $PARAMS; do
  echo "Deleting SSM parameter: $PARAM"
  AWS_PROFILE="$PROFILE" aws ssm delete-parameter --name "$PARAM" || true
done

# =========================================================
# Delete orphaned ENIs
# =========================================================

ENIS=$(AWS_PROFILE="$PROFILE" aws ec2 describe-network-interfaces \
  --query "NetworkInterfaces[?contains(Description, 'smartretail')].NetworkInterfaceId" \
  --output text)

for ENI in $ENIS; do
  echo "Deleting ENI: $ENI"
  AWS_PROFILE="$PROFILE" aws ec2 delete-network-interface \
    --network-interface-id "$ENI" || true
done

# =========================================================
# Delete orphaned security groups
# =========================================================

SGS=$(AWS_PROFILE="$PROFILE" aws ec2 describe-security-groups \
  --query "SecurityGroups[?contains(GroupName, 'smartretail')].GroupId" \
  --output text)

for SG in $SGS; do
  echo "Deleting SG: $SG"
  AWS_PROFILE="$PROFILE" aws ec2 delete-security-group --group-id "$SG" || true
done

# =========================================================
# Delete retained Secrets Manager secrets
# =========================================================

SECRETS=$(AWS_PROFILE="$PROFILE" aws secretsmanager list-secrets \
  --query "SecretList[?contains(Name, 'smartretail')].ARN" \
  --output text)

for SECRET in $SECRETS; do
  echo "Deleting secret: $SECRET"
  AWS_PROFILE="$PROFILE" aws secretsmanager delete-secret \
    --secret-id "$SECRET" --force-delete-without-recovery || true
done

# =========================================================
# Delete retained Cognito pools (if orphaned)
# =========================================================

POOLS=$(AWS_PROFILE="$PROFILE" aws cognito-idp list-user-pools \
  --max-results 60 \
  --query "UserPools[?contains(Name, 'smartretail')].Id" \
  --output text)

for POOL in $POOLS; do
  echo "Deleting Cognito pool: $POOL"
  AWS_PROFILE="$PROFILE" aws cognito-idp delete-user-pool --user-pool-id "$POOL" || true
done

# =========================================================
# Final verification
# =========================================================

echo "=================================================="
echo "Cleanup completed (env: $ENV)"
echo "=================================================="
