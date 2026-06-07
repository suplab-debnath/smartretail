#!/bin/bash
# run-flyway-ecs.sh — runs Flyway as a one-shot ECS Fargate task.
# Eliminates the need to allowlist the caller's IP in the RDS security group.
# Usage: ./scripts/shared/run-flyway-ecs.sh [env] [--clean]
#   --clean  runs "flyway clean migrate" (drops all schemas then re-applies everything)
set -euo pipefail

ENV=${1:-dev}
CLEAN=false
for arg in "$@"; do
  [[ "$arg" == "--clean" ]] && CLEAN=true
done

CLUSTER="smartretail-${ENV}"
TASK_FAMILY="smartretail-flyway-${ENV}"

SUBNET_IDS=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/ecs-subnet-ids" \
  --query Parameter.Value --output text)

SG_ID=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/sg-ecs-tasks-id" \
  --query Parameter.Value --output text)

ASSIGN_PIP=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/network/assign-public-ip" \
  --query Parameter.Value --output text)

if [ "$CLEAN" = "true" ]; then
  echo "Running Flyway clean + migrate via ECS Fargate for env=${ENV}..."
  OVERRIDES='{"containerOverrides":[{"name":"flywayContainer","command":["clean","migrate"],"environment":[{"name":"FLYWAY_CLEAN_DISABLED","value":"false"}]}]}'
else
  echo "Running Flyway migrations via ECS Fargate for env=${ENV}..."
  OVERRIDES='{"containerOverrides":[]}'
fi

TASK_ARN=$(aws ecs run-task \
  --cluster "$CLUSTER" \
  --task-definition "$TASK_FAMILY" \
  --launch-type FARGATE \
  --network-configuration \
    "awsvpcConfiguration={subnets=[${SUBNET_IDS}],securityGroups=[${SG_ID}],assignPublicIp=${ASSIGN_PIP}}" \
  --overrides "$OVERRIDES" \
  --query 'tasks[0].taskArn' --output text)

echo "Task started: ${TASK_ARN}"
echo "Waiting for task to stop..."

aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$TASK_ARN"

EXIT_CODE=$(aws ecs describe-tasks \
  --cluster "$CLUSTER" \
  --tasks "$TASK_ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)

if [ "${EXIT_CODE}" = "0" ]; then
  if [ "$CLEAN" = "true" ]; then
    echo "✅ DB reset + migrations complete (env=${ENV})"
  else
    echo "✅ Migrations complete (env=${ENV})"
  fi
else
  echo "❌ Flyway task failed (exit=${EXIT_CODE}). Check logs: /smartretail/flyway/${ENV}"
  exit 1
fi
