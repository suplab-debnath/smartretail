# Batch Post-Processor Lambda

AWS Lambda function triggered by S3 ObjectCreated events from SageMaker batch transform output.
Parses the transform output CSV and posts the forecast rows to the Demand Forecasting Service (DFS)
via its internal `POST /v1/forecast/runs/{runId}/results` endpoint.

## Flow position

```
SageMaker Batch Transform
    │  writes part-*.csv to S3
    │  s3://smartretail-sagemaker-{env}/sagemaker/output/{run_id}/part-*.csv
    ▼
BatchPostProcessorHandler   ← this Lambda
    │  HTTP POST /v1/forecast/runs/{runId}/results
    ▼
DFS (Demand Forecasting Service, port 8084)
    │  writes to forecasting.demand_forecasts
    │  marks forecasting.forecast_runs status = COMPLETED
    ▼
forecasting schema (PostgreSQL via RDS Proxy)
```

## Handler

**Class:** `com.smartretail.lambda.batchpostprocessor.BatchPostProcessorHandler`
**Interface:** `RequestHandler<S3Event, Void>`

For each S3 ObjectCreated record:

1. Extract `run_id` from the S3 key (`sagemaker/output/{run_id}/part-*.csv`).
2. Download the CSV from S3.
3. Parse each row into a `ForecastRowPayload`. Malformed rows are skipped with a warning.
4. POST all rows to `DFS_ENDPOINT/v1/forecast/runs/{runId}/results`.
5. On HTTP 201 → log success and continue.
6. On any failure → throw RuntimeException; Lambda retries the event.

## CSV format

No header. Seven comma-separated columns:

```
sku_id,dc_id,forecast_date,horizon_days,p10,p50,p90
SKU-BEV-001,DC-LONDON,2026-06-01,30,80,105,135
```

## Source files

```
src/main/java/com/smartretail/lambda/batchpostprocessor/
├── BatchPostProcessorHandler.java   Entry point — RequestHandler<S3Event, Void>
├── S3CsvReader.java                 S3 CSV reader interface
├── S3CsvReaderImpl.java             S3 GetObject + CSV parsing implementation
├── DfsApiClient.java                HTTP POST to DFS (java.net.http.HttpClient)
└── ForecastRowPayload.java          Immutable CSV row record
```

## Build

```bash
# From repository root
JAVA_HOME=<java-21-home> mvn clean package -pl backend/adapters/batch-post-processor

# Fat JAR output:
# backend/adapters/batch-post-processor/target/smartretail-batch-post-processor-1.0.0-SNAPSHOT.jar
```

## Environment variables

| Variable | Description |
|----------|-------------|
| `DFS_ENDPOINT` | Base URL of DFS (e.g. `http://dfs.internal:8084`) |
| `AWS_REGION` | Injected by Lambda runtime |

## Error handling

- Malformed CSV rows: skipped with WARN log; processing continues.
- Empty CSV output: DFS call skipped.
- Key pattern mismatch (`sagemaker/output/{uuid}/...`): skipped with WARN log.
- DFS non-201 response: RuntimeException thrown; Lambda retries.
- 3 consecutive Lambda failures: S3 event routes to SQS DLQ.

## Idempotency

The DFS endpoint uses `ON CONFLICT DO NOTHING` on the `demand_forecasts` unique constraint
`(run_id, sku_id, dc_id, forecast_date, horizon_days)`. Lambda retries are safe —
duplicate rows are silently discarded.

## Known prototype limitation

If SageMaker produces multiple part files for a single run, the run is marked `COMPLETED`
after the first successful part-file ingestion. For the prototype, configure SageMaker to
produce a single merged output or accept this behaviour.
