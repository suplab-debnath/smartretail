#!/bin/bash
# run-flyway-aws.sh — runs Flyway migrations against AWS RDS via Secrets Manager
# Usage: ./scripts/run-flyway-aws.sh [env]

set -e

ENV=${1:-dev}

echo "Running Flyway migrations for env=${ENV}..."

SECRET_ARN=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/rds/secret-arn" \
    --query Parameter.Value --output text)

RDS_ENDPOINT=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/rds/proxy-endpoint" \
    --query Parameter.Value --output text)

DB_PASSWORD=$(aws secretsmanager get-secret-value \
    --secret-id "$SECRET_ARN" \
    --query SecretString --output text | python3 -c "import json,sys; print(json.load(sys.stdin)['password'])")

cd migrations/flyway
mvn flyway:migrate \
    -Dflyway.url="jdbc:postgresql://${RDS_ENDPOINT}:5432/smartretail" \
    -Dflyway.user=smartretail_admin \
    -Dflyway.password="${DB_PASSWORD}" \
    -Dflyway.locations=filesystem:src/main/resources/db/migration

echo "✅ Migrations complete for env=${ENV}"
