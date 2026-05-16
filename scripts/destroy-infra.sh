#!/usr/bin/env bash
# Usage: chmod +x scripts/destroy-infra.sh && ./scripts/destroy-infra.sh

set -euo pipefail

export AWS_DEFAULT_REGION=us-east-1

ENV=dev
ACCOUNT_ID=123456789012

STACKS=(
  ApiStack
  ComputeStack
  IdentityStack
  MessagingStack
  DataStack
  NetworkStack
)

# =========================================================
# Destroy CDK stacks
# =========================================================

for STACK in "${STACKS[@]}"
do
  echo "Destroying ${STACK}"

  cdk destroy ${STACK} \
    --force || true

done

# =========================================================
# Empty S3 Buckets
# =========================================================

BUCKETS=$(aws s3api list-buckets \
  --query "Buckets[?contains(Name, 'smartretail')].Name" \
  --output text)

for BUCKET in $BUCKETS

do
  echo "Cleaning bucket: $BUCKET"

  aws s3 rm s3://$BUCKET --recursive || true

  aws s3api delete-bucket \
    --bucket $BUCKET || true

done

# =========================================================
# Delete ECR Images + Repositories
# =========================================================

REPOS=$(aws ecr describe-repositories \
  --query "repositories[?contains(repositoryName, 'smartretail')].repositoryName" \
  --output text)

for REPO in $REPOS

do
  echo "Deleting ECR repo: $REPO"

  aws ecr delete-repository \
    --repository-name $REPO \
    --force || true

done

# =========================================================
# Delete CloudWatch Log Groups
# =========================================================

LOG_GROUPS=$(aws logs describe-log-groups \
  --query "logGroups[?contains(logGroupName, 'smartretail')].logGroupName" \
  --output text)

for LG in $LOG_GROUPS

do
  echo "Deleting log group: $LG"

  aws logs delete-log-group \
    --log-group-name "$LG" || true

done

# =========================================================
# Delete SSM Parameters
# =========================================================

PARAMS=$(aws ssm describe-parameters \
  --query "Parameters[?contains(Name, 'smartretail')].Name" \
  --output text)

for PARAM in $PARAMS

do
  echo "Deleting SSM parameter: $PARAM"

  aws ssm delete-parameter \
    --name "$PARAM" || true

done

# =========================================================
# Delete orphaned ENIs
# =========================================================

ENIS=$(aws ec2 describe-network-interfaces \
  --query "NetworkInterfaces[?contains(Description, 'smartretail')].NetworkInterfaceId" \
  --output text)

for ENI in $ENIS

do
  echo "Deleting ENI: $ENI"

  aws ec2 delete-network-interface \
    --network-interface-id $ENI || true

done

# =========================================================
# Delete orphaned security groups
# =========================================================

SGS=$(aws ec2 describe-security-groups \
  --query "SecurityGroups[?contains(GroupName, 'smartretail')].GroupId" \
  --output text)

for SG in $SGS

do
  echo "Deleting SG: $SG"

  aws ec2 delete-security-group \
    --group-id $SG || true

done

# =========================================================
# Delete retained Secrets Manager secrets
# =========================================================

SECRETS=$(aws secretsmanager list-secrets \
  --query "SecretList[?contains(Name, 'smartretail')].ARN" \
  --output text)

for SECRET in $SECRETS

do
  echo "Deleting secret: $SECRET"

  aws secretsmanager delete-secret \
    --secret-id $SECRET \
    --force-delete-without-recovery || true

done

# =========================================================
# Delete retained Cognito pools (if orphaned)
# =========================================================

POOLS=$(aws cognito-idp list-user-pools \
  --max-results 60 \
  --query "UserPools[?contains(Name, 'smartretail')].Id" \
  --output text)

for POOL in $POOLS

do
  echo "Deleting Cognito pool: $POOL"

  aws cognito-idp delete-user-pool \
    --user-pool-id $POOL || true

done

# =========================================================
# Final verification
# =========================================================

echo "=================================================="
echo "Cleanup completed"
echo "=================================================="