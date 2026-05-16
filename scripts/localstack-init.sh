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

aws --endpoint-url=$ENDPOINT kinesis create-stream \
    --stream-name "smartretail-events-${ENV}" \
    --shard-count 1 \
    --region $REGION

echo "✅ Kinesis stream created"

# ── EventBridge ───────────────────────────────────────────────────────────────

aws --endpoint-url=$ENDPOINT events create-event-bus \
    --name "smartretail-events-${ENV}" \
    --region $REGION

echo "✅ EventBridge bus created"

# ── SQS queues ────────────────────────────────────────────────────────────────

aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}-dlq" \
    --region $REGION

aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}" \
    --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:smartretail-ims-sales-local-dlq\",\"maxReceiveCount\":\"3\"}","VisibilityTimeout":"30"}' \
    --region $REGION

aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}-dlq.fifo" \
    --attributes FifoQueue=true \
    --region $REGION

aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}.fifo" \
    --attributes '{"FifoQueue":"true","ContentBasedDeduplication":"false"}' \
    --region $REGION

aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ars-updates-${ENV}" \
    --region $REGION

echo "✅ SQS queues created"

# ── EventBridge rules → SQS targets ──────────────────────────────────────────

IMS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ims-sales-${ENV}"
RE_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-re-alert-${ENV}.fifo"
ARS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ars-updates-${ENV}"

# SalesTransactionEvent → IMS SQS
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis"],"detail-type":["SalesTransactionEvent"]}' \
    --state ENABLED \
    --region $REGION

aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ims-target\",\"Arn\":\"${IMS_QUEUE_ARN}\"}]" \
    --region $REGION

# InventoryAlertEvent → RE FIFO SQS (grouped by dcId)
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.ims"],"detail-type":["InventoryAlertEvent"]}' \
    --state ENABLED \
    --region $REGION

aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"re-target\",\"Arn\":\"${RE_QUEUE_ARN}\",\"SqsParameters\":{\"MessageGroupId\":\"inventory-alert\"}}]" \
    --region $REGION

# All events → ARS SQS (for dashboard updates)
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis","smartretail.ims","smartretail.re"]}' \
    --state ENABLED \
    --region $REGION

aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ars-target\",\"Arn\":\"${ARS_QUEUE_ARN}\"}]" \
    --region $REGION

echo "✅ EventBridge rules and targets created"

# ── DynamoDB ──────────────────────────────────────────────────────────────────

aws --endpoint-url=$ENDPOINT dynamodb create-table \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --attribute-definitions AttributeName=event_id,AttributeType=S \
    --key-schema AttributeName=event_id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region $REGION

# Enable TTL on expires_at attribute
aws --endpoint-url=$ENDPOINT dynamodb update-time-to-live \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --time-to-live-specification "Enabled=true,AttributeName=expires_at" \
    --region $REGION

echo "✅ DynamoDB table created"

# ── S3 ────────────────────────────────────────────────────────────────────────

aws --endpoint-url=$ENDPOINT s3 mb \
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
    "/smartretail/local/sqs/ims-sales-queue-url=http://localhost:4566/000000000000/smartretail-ims-sales-local"
    "/smartretail/local/sqs/re-alert-queue-url=http://localhost:4566/000000000000/smartretail-re-alert-local.fifo"
    "/smartretail/local/sqs/ars-updates-queue-url=http://localhost:4566/000000000000/smartretail-ars-updates-local"
    "/smartretail/local/dynamodb/idempotency-table=smartretail-idempotency-keys-local"
)

for param in "${PARAMS[@]}"; do
    KEY="${param%%=*}"
    VALUE="${param##*=}"
    aws --endpoint-url=$ENDPOINT ssm put-parameter \
        --name "$KEY" \
        --value "$VALUE" \
        --type String \
        --overwrite \
        --region $REGION
done

echo "✅ SSM parameters written"
echo "✅ LocalStack resources created successfully"
