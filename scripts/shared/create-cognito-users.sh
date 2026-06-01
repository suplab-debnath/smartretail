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
    local out
    if ! out=$(aws cognito-idp admin-create-user \
        --user-pool-id "$pool" \
        --username "$username" \
        --user-attributes Name=email,Value="$email" Name=email_verified,Value=true \
        --message-action SUPPRESS 2>&1); then
        echo "$out" | grep -q "UsernameExistsException" \
            || { echo "  [ERROR] admin-create-user failed for $username: $out"; exit 1; }
    fi
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

create_user "$INTERNAL_POOL" "sm1@test.com"   "sm1@test.com"   "Test@12345!" "STORE_MANAGER"
create_user "$INTERNAL_POOL" "scp1@test.com"  "scp1@test.com"  "Test@12345!" "SC_PLANNER"
create_user "$INTERNAL_POOL" "exec1@test.com" "exec1@test.com" "Test@12345!" "EXECUTIVE"

echo "✅ All Cognito test users created for env=${ENV}"
