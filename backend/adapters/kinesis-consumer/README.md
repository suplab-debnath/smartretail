# Kinesis Consumer Lambda

AWS Lambda function that sits at the entry point of Flow 1. It consumes POS events from the Kinesis Data Stream, deduplicates them with a DynamoDB TTL table, and forwards them to the Sales Ingestion Service (SIS) over HTTP.

## Flow position

```
POS Terminal
    │  Kinesis Data Stream
    ▼
KinesisConsumerHandler   ← this Lambda
    │  HTTP POST /v1/ingest/events
    ▼
SIS (Sales Ingestion Service, port 8080)
```

## Handler

**Class:** `com.smartretail.lambda.kinesis.KinesisConsumerHandler`  
**Interface:** `RequestHandler<KinesisEvent, Void>`

For each Kinesis record in the batch:

1. Base64-decode the record data and deserialise into `PosEventPayload`.
2. Compute SHA-256 of the `transactionId` and look it up in the DynamoDB idempotency table.
3. If already processed → skip (log and continue).
4. POST the payload to `SIS_ENDPOINT/v1/ingest/events`.
5. On HTTP 2xx → write the idempotency key to DynamoDB with a 48-hour TTL.
6. On failure → allow the record to reach the SQS DLQ after 3 retries.

## Source files

```
src/main/java/com/smartretail/lambda/kinesis/
├── KinesisConsumerHandler.java   Entry point — RequestHandler implementation
├── SisApiClient.java             HTTP client to SIS (java.net.http.HttpClient)
└── PosEventPayload.java          POJO matching the POS event JSON schema
```

## Build

```bash
# From repo root
JAVA_HOME=<java-21-home> mvn clean package -pl lambdas/kinesis-consumer

# Fat JAR output:
# lambdas/kinesis-consumer/target/kinesis-consumer-1.0.0-SNAPSHOT.jar
```

The fat JAR bundles all dependencies. No Spring context — startup time is under 1 s in SnapStart.

## Environment variables

| Variable | Description |
|----------|-------------|
| `SIS_ENDPOINT` | Base URL of the SIS service (e.g. `http://sis.internal:8080`) |
| `IDEMPOTENCY_TABLE_NAME` | DynamoDB table name for dedup keys |
| `AWS_REGION` | AWS region (injected automatically by the Lambda runtime) |

## Local mode

In local mode LocalStack provides both Kinesis and DynamoDB. The `environments/local/scripts/localstack-init.sh` script creates the stream, the DLQ, and the Lambda function pointing at the built JAR. SIS runs on `localhost:8080`.

```bash
make local-up          # start LocalStack + Postgres
make local-migrate     # run Flyway
make local-sis         # start SIS on :8080
# localstack-init.sh deploys the Lambda automatically on make local-up
```

## Error handling

- **Idempotency collision** — silently skipped; does not increment retry count.
- **SIS 4xx** — logged as a warning; record is treated as processed (bad data, not transient).
- **SIS 5xx / network error** — record is NOT written to idempotency table; Lambda returns a partial failure response so Kinesis retries only the failed records.
- **3 consecutive failures** — Kinesis routes the shard to the SQS DLQ.

## Dependencies (key)

| Artifact | Version |
|----------|---------|
| `aws-lambda-java-core` | 1.2.3 |
| `aws-lambda-java-events` | 3.14.0 |
| `software.amazon.awssdk:dynamodb` | 2.26.12 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 |
