#!/bin/bash
# create-cognito-users.sh — creates test users in Cognito user pools
# Usage: ./scripts/create-cognito-users.sh [env]

set -e

ENV=${1:-dev}

echo "Creating Cognito test users for env=${ENV}..."

INTERNAL_POOL=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/cognito/internal-pool-id" \
    --query Parameter.Value --output text)

create_user() {
    local pool=$1 username=$2 email=$3 password=$4 group=$5
    aws cognito-idp admin-create-user \
        --user-pool-id "$pool" \
        --username "$username" \
        --user-attributes Name=email,Value="$email" Name=email_verified,Value=true \
        --message-action SUPPRESS 2>/dev/null || true
    aws cognito-idp admin-set-user-password \
        --user-pool-id "$pool" \
        --username "$username" \
        --password "$password" \
        --permanent
    aws cognito-idp admin-add-user-to-group \
        --user-pool-id "$pool" \
        --username "$username" \
        --group-name "$group"
    echo "  ✅ $username ($group)"
}

create_user "$INTERNAL_POOL" "store-manager-1" "sm1@test.com"   "Test@12345!" "STORE_MANAGER"
create_user "$INTERNAL_POOL" "sc-planner-1"    "scp1@test.com"  "Test@12345!" "SC_PLANNER"
create_user "$INTERNAL_POOL" "executive-1"     "exec1@test.com" "Test@12345!" "EXECUTIVE"

echo "✅ All Cognito test users created for env=${ENV}"
