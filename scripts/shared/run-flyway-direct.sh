#!/bin/bash
# run-flyway-direct.sh — runs Flyway via Maven directly from the local machine.
# Requires the caller's IP to be allowed on port 5432 in the RDS security group.
# Usage: ./scripts/shared/run-flyway-direct.sh [env] [migrate|info|repair|clean]
#   env     demo | dev (default: demo)
#   command flyway goal (default: migrate)
# ------------------------------------
# # Add your IP to sgRds first
# MY_IP=$(curl -s https://checkip.amazonaws.com)
# SG_ID=$(AWS_PROFILE=smartretail-dev aws ssm get-parameter --name /smartretail/demo/sg-rds-id --query Parameter.Value --output text)
# AWS_PROFILE=smartretail-dev aws ec2 authorize-security-group-ingress \
#   --group-id $SG_ID --protocol tcp --port 5432 --cidr ${MY_IP}/32 --region us-east-1
#
# # Check migration status
# AWS_PROFILE=smartretail-dev bash environments/demo/scripts/run-flyway-direct-demo.sh info
#
# # Run migrations
# AWS_PROFILE=smartretail-dev bash environments/demo/scripts/run-flyway-direct-demo.sh migrate

# Remove your IP when done
# AWS_PROFILE=smartretail-dev aws ec2 revoke-security-group-ingress \
#   --group-id $SG_ID --protocol tcp --port 5432 --cidr ${MY_IP}/32 --region us-east-1

set -euo pipefail

ENV=${1:-demo}
GOAL=${2:-migrate}

echo "Fetching RDS connection details for env=${ENV}..."

RDS_ENDPOINT=$(aws ssm get-parameter \
  --name "/smartretail/${ENV}/rds/instance-endpoint" \
  --query Parameter.Value --output text)

SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "smartretail-rds-secret-${ENV}" \
  --query SecretString --output text)

DB_USER=$(echo "$SECRET_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['username'])")
DB_PASS=$(echo "$SECRET_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['password'])")

export FLYWAY_URL="jdbc:postgresql://${RDS_ENDPOINT}:5432/smartretail"
export FLYWAY_USER="$DB_USER"
export FLYWAY_PASSWORD="$DB_PASS"

echo "RDS: ${RDS_ENDPOINT}"
echo "Running: flyway:${GOAL}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

mvn -f "${REPO_ROOT}/backend/migrations/pom.xml" \
  "flyway:${GOAL}" \
  -Dflyway.cleanDisabled=true
