#!/bin/bash
# run-flyway-ecs.sh — runs Flyway migrations as a one-shot ECS Fargate task.
# Eliminates the need to allowlist the caller's IP in the RDS security group.
# Usage: ./scripts/shared/run-flyway-ecs.sh [env]
set -euo pipefail

ENV=${1:-dev}
CLUSTER="smartretail-${ENV}"
TASK_FAMILY="smartretail-flyway-${ENV}"

echo "Running Flyway migrations via ECS Fargate for env=${ENV}..."

SUBNET_IDS=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/ecs-subnet-ids" \
  --query Parameter.Value --output text)

SG_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/sg-ecs-tasks-id" \
  --query Parameter.Value --output text)

ASSIGN_PIP=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/assign-public-ip" \
  --query Parameter.Value --output text)

TASK_ARN=$(aws ecs run-task \
  --cluster "$CLUSTER" \
  --task-definition "$TASK_FAMILY" \
  --launch-type FARGATE \
  --network-configuration \
    "awsvpcConfiguration={subnets=[${SUBNET_IDS}],securityGroups=[${SG_ID}],assignPublicIp=${ASSIGN_PIP}}" \
  --query 'tasks[0].taskArn' --output text)

echo "Migration task started: ${TASK_ARN}"
echo "Waiting for task to stop..."

aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$TASK_ARN"

EXIT_CODE=$(aws ecs describe-tasks \
  --cluster "$CLUSTER" \
  --tasks "$TASK_ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)

if [ "${EXIT_CODE}" = "0" ]; then
  echo "✅ Migrations complete (env=${ENV})"
else
  echo "❌ Migration task failed (exit=${EXIT_CODE}). Check logs: /smartretail/flyway/${ENV}"
  exit 1
fi
