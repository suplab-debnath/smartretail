#!/bin/bash
# run-flyway-direct-demo.sh — runs Flyway directly via JDBC from the local machine.
# Use when you need to bypass ECS (e.g. to verify migration status or run ad-hoc).
# Prerequisite: your IP must be in the sgRds security group on port 5432.
#
# Add your IP:
#   MY_IP=$(curl -s https://checkip.amazonaws.com)
#   SG_ID=$(aws ssm get-parameter --name /smartretail/demo/sg-rds-id --query Parameter.Value --output text)
#   aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 5432 --cidr ${MY_IP}/32 --region us-east-1
#
# Usage: ./run-flyway-direct-demo.sh [migrate|info|repair]
set -euo pipefail
exec "$(dirname "$0")/../../../scripts/shared/run-flyway-direct.sh" "demo" "${1:-migrate}"
