# Demo Environment — AWS Operations Command Reference

Collected from live Flow 1–4 debugging session. All commands target `smartretail-ingest-demo` / `us-east-1` unless noted.

---

## Firehose

### Inspect delivery stream endpoint and retry config
```bash
aws firehose describe-delivery-stream \
  --delivery-stream-name smartretail-ingest-demo \
  --query 'DeliveryStreamDescription.Destinations[0].HttpEndpointDestinationDescription.{
    Url:EndpointConfiguration.Url,
    RetryDuration:RetryOptions.DurationInSeconds,
    BufferingSize:BufferingHints.SizeInMBs,
    BufferingInterval:BufferingHints.IntervalInSeconds,
    S3BackupMode:S3BackupMode
  }'
```
**Why:** Confirm Firehose is pointing at the correct API GW URL and that retry/buffer settings are as expected.

### Check delivery stream status and failure info
```bash
aws firehose describe-delivery-stream \
  --delivery-stream-name smartretail-ingest-demo \
  --query 'DeliveryStreamDescription.{Status:DeliveryStreamStatus,FailureDescription:FailureDescription}'
```
**Why:** Distinguish between stream being ACTIVE vs degraded. `FailureDescription` shows IAM or config errors (e.g. missing `secretsmanager:GetSecretValue`).

### Read a Firehose error file from S3
```bash
# List error files for today
aws s3 ls s3://smartretail-events-demo-491085389857/firehose-errors/ --recursive \
  | grep "$(date -u +%Y/%m/%d)"

# Download and read (files are NOT gzip despite .gz extension)
aws s3 cp "s3://smartretail-events-demo-491085389857/firehose-errors/http-endpoint-failed/2026/06/18/<filename>.gz" error.txt \
  && notepad error.txt
```
**Why:** Error files contain `errorCode` and `errorMessage` — the ground truth for why Firehose delivery failed (e.g. `SecretsManagerException`, `SecretsManagerValueParseException`, HTTP 4xx/5xx).

---

## API Gateway

### Check account-level CloudWatch logging role
```bash
aws apigateway get-account --query 'cloudwatchRoleArn'
```
**Why:** API GW silently drops execution logs if no CloudWatch IAM role is set at the account level. Must return a valid ARN for logging to work.

### Create and assign the CloudWatch logging role (if missing)
```bash
aws iam create-role \
  --role-name APIGatewayCloudWatchLogsRole \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }'

aws iam attach-role-policy \
  --role-name APIGatewayCloudWatchLogsRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs

aws apigateway update-account \
  --patch-operations op=replace,path=/cloudwatchRoleArn,value=$(aws iam get-role \
    --role-name APIGatewayCloudWatchLogsRole --query 'Role.Arn' --output text)
```
**Why:** Without this, enabling execution logs on a stage has no effect.

### Force a stage redeployment (required after enabling logging)
```bash
aws apigateway create-deployment \
  --rest-api-id b886qsqh89 \
  --stage-name internal
```
**Why:** Stage setting changes (logging level, throttling) only take effect after a redeployment. API GW execution logs won't appear until this is done.

### Find the execution log group for a stage
**Log group name pattern:** `API-Gateway-Execution-Logs_{apiId}/{stageName}`
For the demo internal stage: `API-Gateway-Execution-Logs_b886qsqh89/internal`

```bash
aws logs describe-log-groups \
  --log-group-name-prefix "API-Gateway-Execution-Logs" \
  --query 'logGroups[].logGroupName'
```

### Check for recent API GW log streams
```bash
aws logs describe-log-streams \
  --log-group-name "API-Gateway-Execution-Logs_b886qsqh89/internal" \
  --order-by LastEventTime \
  --descending \
  --max-items 5 \
  --query 'logStreams[].{name:logStreamName,last:lastEventTimestamp}'
```
**Why:** If no new streams appear after sending an event, requests are not reaching API GW at all (network, URL mismatch, or Firehose not flushing).

---

## CloudWatch Logs — Services

### Tail recent logs for any ECS service
```bash
# Replace <service> with: sis, ims, re, ars, dfs, sup
aws logs filter-log-events \
  --log-group-name "/smartretail/<service>/demo" \
  --start-time $(($(date -u +%s) - 300))000 \
  --query 'events[].message' \
  --output text
```
**Why:** Primary way to trace event processing through the pipeline without needing a terminal on the container.

### Key log messages to look for per service
| Service | Log message | Meaning |
|---------|-------------|---------|
| SIS | `SalesTransactionEvent published` | Event sent to EventBridge |
| SIS | `SalesTransactionEvent processed` | Full ingest complete |
| SIS | `Skipping invalid Firehose record` | Base64/JSON decode failed |
| IMS | `SQS message received` | Picked up from ims-sales queue |
| IMS | `InventoryAlertEvent published` | Stock alert raised on EventBridge |
| RE | `InventoryAlertEvent received` | Picked up from re-alert FIFO queue |
| RE | `Rule found` | Replenishment rule matched |
| RE | `PurchaseOrderEvent published status=APPROVED` | Auto-approved PO |
| RE | `PurchaseOrderEvent published status=PENDING_APPROVAL` | Manual approval required |

---

## SQS

### Check queue depth (messages waiting + in-flight)
```bash
aws sqs get-queue-attributes \
  --queue-url $(aws ssm get-parameter \
    --name /smartretail/demo/sqs/re-alert-queue-url \
    --query Parameter.Value --output text) \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
```
**Why:** `ApproximateNumberOfMessages=0` with `NotVisible=0` means messages were already consumed. `NotVisible>0` means a consumer is actively processing.

### SSM parameter names for queue URLs
| Queue | SSM parameter |
|-------|---------------|
| IMS sales | `/smartretail/demo/sqs/ims-sales-queue-url` |
| RE alert (FIFO) | `/smartretail/demo/sqs/re-alert-queue-url` |
| ARS updates | `/smartretail/demo/sqs/ars-updates-queue-url` |

---

## ECS

### Check service health (desired vs running count)
```bash
aws ecs describe-services \
  --cluster smartretail-demo \
  --services smartretail-sis-demo \
  --query 'services[0].{desired:desiredCount,running:runningCount,status:status}'
```

### Check which Docker image a running task is using
```bash
aws ecs describe-tasks \
  --cluster smartretail-demo \
  --tasks $(aws ecs list-tasks \
    --cluster smartretail-demo \
    --service-name smartretail-sis-demo \
    --query 'taskArns[0]' --output text) \
  --query 'tasks[0].containers[0].image'
```
**Why:** After pushing a new image, verify the running task has picked it up. If stale, force a new deployment.

### Force a new ECS deployment (after pushing new image)
```bash
aws ecs update-service \
  --cluster smartretail-demo \
  --service smartretail-sis-demo \
  --force-new-deployment
```

---

## Secrets Manager

### Read the Firehose access key value
```bash
aws secretsmanager get-secret-value \
  --secret-id /smartretail/demo/firehose/ingest-access-key \
  --query SecretString --output text
```
**Why:** Used when configuring Firehose HTTP endpoint with a direct access key value (not Secrets Manager ARN reference — Firehose requires plain string or JSON depending on config mode).

---

## IAM

### Inline policy to allow developer IAM user to put records to Firehose
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "FirehosePutRecord",
      "Effect": "Allow",
      "Action": ["firehose:PutRecord", "firehose:PutRecordBatch"],
      "Resource": "arn:aws:firehose:*:*:deliverystream/smartretail-ingest-*"
    }
  ]
}
```
**Why:** The Python script `publish-pos-event.py` calls `firehose.put_record()` using the developer's local AWS profile. Without this, the call returns `AccessDeniedException`.

### Inline policy to allow Firehose role to read access key from Secrets Manager
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:us-east-1:491085389857:secret:/smartretail/demo/firehose/ingest-access-key-uhyavd"
    }
  ]
}
```
**Attach to:** IAM role `smartretail-firehose-demo`
**Why:** Firehose needs to fetch the access key from Secrets Manager to send it as `X-Amz-Firehose-Access-Key` on each HTTP delivery. Without this, delivery fails with `SecretsManagerException`.

---

## Firehose → SIS Pipeline — Root Causes Found & Fixed

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | Firehose `SecretsManagerException` | `smartretail-firehose-demo` IAM role lacked `secretsmanager:GetSecretValue` | Added inline policy to Firehose role |
| 2 | Firehose `SecretsManagerValueParseException` | Console edit of retry duration switched access key mode to "Secrets Manager ARN" which requires JSON secret | Changed access key back to "Direct" mode with raw value |
| 3 | SIS returning 401 to Firehose | Spring Security JWT filter rejected Firehose requests before reaching `FirehoseBatchFilter` | Added `X-Amz-Firehose-Request-Id` header matcher to `SecurityConfig` to bypass JWT |
| 4 | `SMARTRETAIL_FIREHOSE_ACCESSKEY` not injected | Demo compute stack `sisConfig.secrets` was missing the secret injection | Added `ecs.Secret.fromSecretsManager(data.firehoseAccessKeySecret)` to sisConfig |
| 5 | `Skipping invalid Firehose record` (double base64) | Python script pre-encoded payload with `base64.b64encode()` before `put_record()` — Firehose encodes again | Removed pre-encoding; pass raw `json.dumps(event).encode('utf-8')` |
| 6 | IMS never received `SalesTransactionEvent` | EventBridge rule `salesToImsRule` used wrong `detailType: SalesTransactionProcessed` | Fixed to `SalesTransactionEvent` (canonical name per `EVENT_ASYNC_SPEC.md`) |
