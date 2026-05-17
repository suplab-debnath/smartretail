#!/bin/bash
# localstack-init.sh — runs automatically when LocalStack is ready
# Mounted at: /etc/localstack/init/ready.d/init.sh

set -e

ENDPOINT="http://localhost:4566"
ENV="local"
REGION="us-east-1"
ACCOUNT="000000000000"  # LocalStack fake account ID

echo "Creating LocalStack resources for env=${ENV}..."

# ── Kinesis ──────────────────────────────────────────────────────────────────

awslocal --endpoint-url=$ENDPOINT kinesis create-stream \
    --stream-name "smartretail-events-${ENV}" \
    --shard-count 1 \
    --region $REGION

echo "✅ Kinesis stream created"

# ── EventBridge ───────────────────────────────────────────────────────────────

awslocal --endpoint-url=$ENDPOINT events create-event-bus \
    --name "smartretail-events-${ENV}" \
    --region $REGION

echo "✅ EventBridge bus created"

# ── SQS queues ────────────────────────────────────────────────────────────────

awslocal --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}-dlq" \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}" \
    --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:smartretail-ims-sales-local-dlq\",\"maxReceiveCount\":\"3\"}","VisibilityTimeout":"30"}' \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}-dlq.fifo" \
    --attributes FifoQueue=true \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}.fifo" \
    --attributes '{"FifoQueue":"true","ContentBasedDeduplication":"false"}' \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ars-updates-${ENV}" \
    --region $REGION

echo "✅ SQS queues created"

# ── EventBridge rules → SQS targets ──────────────────────────────────────────

IMS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ims-sales-${ENV}"
RE_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-re-alert-${ENV}.fifo"
ARS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ars-updates-${ENV}"

# SalesTransactionEvent → IMS SQS
awslocal --endpoint-url=$ENDPOINT events put-rule \
    --name "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis"],"detail-type":["SalesTransactionEvent"]}' \
    --state ENABLED \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT events put-targets \
    --rule "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ims-target\",\"Arn\":\"${IMS_QUEUE_ARN}\"}]" \
    --region $REGION

# InventoryAlertEvent → RE FIFO SQS (grouped by dcId)
awslocal --endpoint-url=$ENDPOINT events put-rule \
    --name "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.ims"],"detail-type":["InventoryAlertEvent"]}' \
    --state ENABLED \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT events put-targets \
    --rule "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"re-target\",\"Arn\":\"${RE_QUEUE_ARN}\",\"SqsParameters\":{\"MessageGroupId\":\"inventory-alert\"}}]" \
    --region $REGION

# All events → ARS SQS (for dashboard updates)
awslocal --endpoint-url=$ENDPOINT events put-rule \
    --name "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis","smartretail.ims","smartretail.re"]}' \
    --state ENABLED \
    --region $REGION

awslocal --endpoint-url=$ENDPOINT events put-targets \
    --rule "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ars-target\",\"Arn\":\"${ARS_QUEUE_ARN}\"}]" \
    --region $REGION

echo "✅ EventBridge rules and targets created"

# ── DynamoDB ──────────────────────────────────────────────────────────────────

awslocal --endpoint-url=$ENDPOINT dynamodb create-table \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --attribute-definitions AttributeName=event_id,AttributeType=S \
    --key-schema AttributeName=event_id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region $REGION

# Enable TTL on expires_at attribute
awslocal --endpoint-url=$ENDPOINT dynamodb update-time-to-live \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --time-to-live-specification "Enabled=true,AttributeName=expires_at" \
    --region $REGION

echo "✅ DynamoDB table created"

# ── S3 ────────────────────────────────────────────────────────────────────────

awslocal --endpoint-url=$ENDPOINT s3 mb \
    "s3://smartretail-events-${ENV}" \
    --region $REGION

echo "✅ S3 bucket created"

# ── SSM Parameter Store ───────────────────────────────────────────────────────
# Values read by Spring Boot services at startup in local mode

PARAMS=(
    "/smartretail/local/rds/proxy-endpoint=localhost"
    "/smartretail/local/rds/database-name=smartretail"
    "/smartretail/local/eventbridge/bus-name=smartretail-events-local"
    "/smartretail/local/kinesis/stream-name=smartretail-events-local"
    "/smartretail/local/s3/events-bucket=smartretail-events-local"
    "/smartretail/local/dynamodb/idempotency-table=smartretail-idempotency-keys-local"
)

# These queue URLs need special handling
awslocal ssm put-parameter --name "/smartretail/local/sqs/ims-sales-queue-url" \
    --value "http://localhost:4566/000000000000/smartretail-ims-sales-local" \
    --type String --overwrite --region $REGION || true

awslocal ssm put-parameter --name "/smartretail/local/sqs/re-alert-queue-url" \
    --value "http://localhost:4566/000000000000/smartretail-re-alert-local.fifo" \
    --type String --overwrite --region $REGION || true

awslocal ssm put-parameter --name "/smartretail/local/sqs/ars-updates-queue-url" \
    --value "http://localhost:4566/000000000000/smartretail-ars-updates-local" \
    --type String --overwrite --region $REGION || true

for param in "${PARAMS[@]}"; do
    KEY="${param%%=*}"
    VALUE="${param##*=}"
    awslocal ssm put-parameter \
        --name "$KEY" \
        --value "$VALUE" \
        --type String \
        --overwrite \
        --region $REGION || true
done

echo "✅ SSM parameters written"

# ── Cognito User Pool ───────────────────────────────────────────────────────
echo "Creating Cognito User Pool..."

INTERNAL_POOL_ID=$(awslocal cognito-idp create-user-pool \
  --pool-name smartretail-internal \
  --query 'UserPool.Id' \
  --output text)

echo "User Pool: $INTERNAL_POOL_ID"

CLIENT_ID=$(awslocal cognito-idp create-user-pool-client \
  --user-pool-id "$INTERNAL_POOL_ID" \
  --client-name smartretail-web-client \
  --query 'UserPoolClient.ClientId' \
  --output text)

echo "Client ID: $CLIENT_ID"

# Create groups first
awslocal cognito-idp create-group \
    --user-pool-id "$INTERNAL_POOL_ID" \
    --group-name STORE_MANAGER || true

awslocal cognito-idp create-group \
    --user-pool-id "$INTERNAL_POOL_ID" \
    --group-name SC_PLANNER || true

awslocal cognito-idp create-group \
    --user-pool-id "$INTERNAL_POOL_ID" \
    --group-name EXECUTIVE || true

echo "Creating test user..."

create_local_user() {
    local username=$1 email=$2 password=$3 group=$4
    awslocal cognito-idp admin-create-user \
        --user-pool-id "$INTERNAL_POOL_ID" \
        --username "$username" \
        --user-attributes Name=email,Value="$email" Name=email_verified,Value=true \
        --message-action SUPPRESS 2>/dev/null || true

    awslocal cognito-idp admin-set-user-password \
        --user-pool-id "$INTERNAL_POOL_ID" \
        --username "$username" \
        --password "$password" \
        --permanent || true

    awslocal cognito-idp admin-add-user-to-group \
        --user-pool-id "$INTERNAL_POOL_ID" \
        --username "$username" \
        --group-name "$group" || true

    echo "  ✅ Created user: $username ($group)"
}

create_local_user "store-manager-1" "sm1@test.com"   "Test@12345!" "STORE_MANAGER"
create_local_user "sc-planner-1"    "scp1@test.com"  "Test@12345!" "SC_PLANNER"
create_local_user "executive-1"     "exec1@test.com" "Test@12345!" "EXECUTIVE"

echo "✅ All Cognito test users created for env=${ENV}"
echo "LocalStack initialization completed."