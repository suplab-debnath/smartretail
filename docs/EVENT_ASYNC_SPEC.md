# Event & Async Contract Specification

> **Audience:** Service owners, platform engineers, and anyone producing or consuming async messages in SmartRetail.
>
> **Status:** Authoritative — supersedes any async-related notes scattered across `API_CONTRACTS.md`, `FLOWS.md`, or `CDK_SPEC.md` where they conflict with this document.

---

## 1. Overview

SmartRetail uses three async channels:

```
[POS Terminals]
    │ store LAN / batch
    ▼
[Store-Edge Aggregator]  ← outside AWS
    │ Firehose PutRecordBatch (HTTPS, IAM SigV4)
    ▼
[Amazon Data Firehose: smartretail-ingest-{env}]     ← dev/prod only
    ├──────────────────────────────────────────────────────────────────┐
    │ native S3 delivery (raw archive)               │ HTTP endpoint  │
    ▼                                                ▼                │
[S3: smartretail-events-{env}]        [API Gateway (ingest stage)]    │
                                                     │ VPC Link       │
                                                     ▼                │
                                         [SIS Service]                │
                                    → RDS sales_events                │
                                    → RDS idempotency_keys (dedup)    │
                                    │ EventBridge PutEvents           │
                                    ▼                                 │
[EventBridge Custom Bus: smartretail-events-{env}]                    │
    ├── Rule: smartretail-sales-to-ims-{env}  ──→  SQS Standard: smartretail-ims-sales-{env}
    │                                                     │
    │                                               [IMS Service]  →  RDS (inventory schema)
    │                                                     │ EventBridge PutEvents
    │                                                     ▼
    ├── Rule: smartretail-alert-to-re-{env}   ──→  SQS FIFO: smartretail-re-alert-{env}.fifo
    │                                                     │ (MessageGroupId = dcId)
    │                                               [RE Service]  →  RDS (replenishment schema)
    │                                                     │ EventBridge PutEvents
    │                                                     ▼
    └── Rule: smartretail-all-to-ars-{env}    ──→  SQS Standard: smartretail-ars-updates-{env}
                                                          │
                                                    [ARS Service]  (analytics aggregation)
```

**SageMaker path (dev/prod only):**
```
[SageMaker Training Job]
    │ CSV output file → S3 Bucket: smartretail-sagemaker-{env}
    ▼
[Lambda: BatchPostProcessorHandler]
    │ HTTP POST /v1/forecast/runs/{runId}/results
    ▼
[DFS Service]  →  RDS (forecasting schema)
```

### Delivery guarantee summary

All channels provide **at-least-once** delivery. There is **no exactly-once** guarantee. Every consumer **must be idempotent**.

### Glossary

| Term | Meaning |
|------|---------|
| Producer | Service that calls EventBridge `PutEvents` or sends to Firehose |
| Consumer | Service with an SQS listener or Lambda trigger |
| DLQ | Dead-letter queue — receives messages after `maxReceiveCount` failures |
| Envelope | Full EventBridge JSON wrapper that arrives in SQS |
| Detail | The `detail` field inside the EventBridge envelope; the domain payload |
| Detail-type | EventBridge routing field used in filter rules (`detail-type` key) |
| Idempotency key | Stable identifier used to detect duplicate processing |
| Message group ID | FIFO queue partitioning key that enforces strict ordering within a group |

---

## 2. Infrastructure Inventory

| Resource | Type | Demo stack | Dev/Prod stack | Consumer |
|----------|------|:----------:|:--------------:|----------|
| `smartretail-events-{env}` (bus) | EventBridge Custom Bus | ✓ | ✓ | Rules → SQS targets |
| `smartretail-ingest-{env}` | Amazon Data Firehose | ✗ | ✓ | Delivers to S3 + API Gateway → SIS |
| `smartretail-ims-sales-{env}` | SQS Standard | ✓ | ✓ | IMS `SalesSqsListener` |
| `smartretail-ims-sales-{env}-dlq` | SQS Standard DLQ | ✓ | ✓ | Manual triage |
| `smartretail-re-alert-{env}.fifo` | SQS FIFO | ✓ | ✓ | RE `AlertSqsListener` |
| `smartretail-re-alert-{env}-dlq.fifo` | SQS FIFO DLQ | ✓ | ✓ | Manual triage |
| `smartretail-ars-updates-{env}` | SQS Standard | ✓ | ✓ | ARS (analytics cache) |
| `smartretail-ars-updates-{env}-dlq` | SQS Standard DLQ | ✓ | ✓ | Manual triage |
| `sales.idempotency_keys` (RDS) | PostgreSQL table (sales schema) | ✓ | ✓ | SIS only — Flyway V1 migration |
| `smartretail-sagemaker-{env}` | S3 Bucket | ✗ | ✓ | Lambda Batch Post-Processor |

Queue URLs and stream names are resolved at runtime from AWS Parameter Store:
```
/smartretail/{env}/eventbridge/bus-name
/smartretail/{env}/sqs/ims-sales-queue-url
/smartretail/{env}/sqs/re-alert-queue-url
/smartretail/{env}/sqs/ars-updates-queue-url
/smartretail/{env}/firehose/stream-name
```

In LOCAL mode the Spring profile sets `spring.cloud.aws.endpoint=http://localhost:4566` and all resources use LocalStack.

---

## 3. Firehose Ingestion Channel

### 3.1 POS Event payload (Store-Edge Aggregator → Firehose)

The store-edge aggregator batches POS events and calls `firehose:PutRecordBatch`. Each record
in the batch is a raw JSON POS event. Partition key is implicit — Firehose is not shard-based.

```json
{
  "transactionId": "<UUID>",
  "storeId":       "<string, max 50>",
  "skuId":         "<string, max 50>",
  "dcId":          "<string, max 50>",
  "quantity":      "<integer, > 0>",
  "unitPrice":     "<decimal, >= 0>",
  "channel":       "POS | ECOMMERCE",
  "eventTimestamp":"<ISO-8601 instant, e.g. 2026-05-25T10:30:00Z>"
}
```

All fields required. No nullable fields.

### 3.2 Firehose delivery configuration

| Parameter | Value |
|-----------|-------|
| Stream name | `smartretail-ingest-{env}` |
| Buffering | 5 MB or 60 seconds (whichever first) |
| Destination 1 | S3 `smartretail-events-{env}/events/{yyyy/MM/dd}/` — raw archive, SSE-KMS |
| Destination 2 | HTTP endpoint → API Gateway ingest stage → VPC Link → SIS |
| Auth (HTTP dest) | `X-Amz-Firehose-Access-Key` header — static key from Secrets Manager |
| On HTTP failure | Retry for 24 hours, then write to S3 `firehose-backup/` prefix |
| Encryption | KMS CMK `alias/smartretail-firehose-{env}` |

### 3.3 Firehose HTTP endpoint — SIS request/response contract

Firehose POSTs to the SIS ingest endpoint with this structure:

**Request headers:**
```
X-Amz-Firehose-Request-Id: <UUID assigned by Firehose>
X-Amz-Firehose-Access-Key: <secret from Secrets Manager>
Content-Type: application/json
```

**Request body:**
```json
{
  "requestId": "<UUID>",
  "timestamp": 1234567890,
  "records": [
    { "data": "<base64-encoded POS event JSON>" },
    { "data": "<base64-encoded POS event JSON>" }
  ]
}
```

SIS decodes each `record.data` from base64, deserialises the POS JSON, and processes the batch.

**Required response (200 OK):**
```json
{
  "requestId": "<echo requestId from request>",
  "timestamp": <current epoch millis>
}
```

SIS must return 200 on all outcomes (accepted, duplicate, validation failure). Individual record
failures are routed to the SQS DLQ; they do not cause Firehose to retry the entire batch.
Non-2xx responses cause Firehose to retry the entire batch delivery.

### 3.4 SIS batch processing algorithm

```
For each record in Firehose batch:
  1. Base64-decode record.data
  2. Deserialise JSON → SalesTransaction
  3. If JSON invalid → send to SQS DLQ; continue
  4. Compute idempotency_key = SHA-256(transactionId)
  5. BEGIN TRANSACTION
  6.   SELECT 1 FROM sales.idempotency_keys WHERE event_id = idempotency_key
  7.   If found → DUPLICATE; ROLLBACK; continue (skip silently)
  8.   INSERT INTO sales.sales_events (transaction data)
  9.   INSERT INTO sales.idempotency_keys (event_id, received_at)
  10.  EventBridge PutEvents (SalesTransactionEvent)
  11. COMMIT
  12. Increment accepted count
Return 200 OK { requestId, timestamp }
```

Steps 8, 9, and 10 are in the same `@Transactional` boundary. EventBridge call is best-effort
(outside the JDBC transaction — failure is logged, not rolled back).

### 3.5 Edge aggregator obligations

- The aggregator authenticates to Firehose via IAM SigV4 — no long-lived access keys.
- `eventTimestamp` must be the time the sale occurred, not the batch-publish time.
- The aggregator must not retry a `PutRecordBatch` on HTTP 200 — Firehose acknowledged it.
- Batch size: up to 500 records per `PutRecordBatch` call (Firehose limit).

### 3.6 LOCAL mode — no Firehose

In LOCAL mode (`SPRING_PROFILES_ACTIVE=local`), Firehose is not emulated. The test script
`scripts/shared/publish-pos-event.py` POSTs a single-record JSON body directly to
`http://localhost:8080/v1/ingest/events`. SIS handles both the Firehose batch envelope
(detected via `X-Amz-Firehose-Request-Id` header) and the direct single-record format.


## 4. EventBridge Event Contracts

### 4.1 Envelope format

Every message delivered to SQS by EventBridge has this outer structure:

```json
{
  "version": "0",
  "id": "<UUID assigned by EventBridge>",
  "source": "smartretail.sis",
  "detail-type": "SalesTransactionEvent",
  "account": "<AWS account ID>",
  "time": "<ISO-8601 instant>",
  "region": "<AWS region>",
  "resources": [],
  "detail": { /* domain payload — see sections below */ }
}
```

Consumers must deserialise the full envelope and extract `detail` before applying the domain schema. The envelope `id` is EventBridge-assigned and is **not** a stable idempotency key — use the business identifier inside `detail` instead.

---

### 4.2 SalesTransactionEvent

**Channel:** EventBridge bus → `smartretail-ims-sales-{env}` (Standard SQS)

| Field | Source | Detail-type |
|-------|--------|-------------|
| `smartretail.sis` | SIS `EventBridgePublisher` | `SalesTransactionEvent` |

#### Detail schema

```json
{
  "transactionId": "<UUID>             required — stable idempotency key",
  "storeId":       "<string, max 50>  required",
  "skuId":         "<string, max 50>  required",
  "dcId":          "<string, max 50>  required",
  "quantity":      "<integer, > 0>    required",
  "unitPrice":     "<decimal, >= 0>   required",
  "channel":       "POS | ECOMMERCE   required",
  "eventTimestamp":"<ISO-8601 instant> required"
}
```

No nullable fields.

#### EventBridge rule

```
Rule name: smartretail-sales-to-ims-{env}
Pattern:
  {
    "source": ["smartretail.sis"],
    "detail-type": ["SalesTransactionEvent"]
  }
Target: smartretail-ims-sales-{env} (Standard SQS)
```

#### Producer contract (SIS)

- Publish within the `@Transactional` boundary that commits `sales.sales_events`.
- Do not publish if the transaction was rejected as a duplicate (return `DUPLICATE` result instead).
- `transactionId` must match the value stored in RDS — never generate a new UUID on retry.

#### Consumer contract (IMS — `SalesSqsListener`)

- Use `transactionId` as the natural idempotency key.
- Apply optimistic lock on `inventory_positions.version`; if update returns 0 rows, throw `OptimisticLockException` — the SQS message will be re-delivered.
- Must tolerate receiving the same `SalesTransactionEvent` more than once with no net side effect.

---

### 4.3 InventoryAlertEvent

**Channel:** EventBridge bus → `smartretail-re-alert-{env}.fifo` (FIFO SQS)

| Field | Source | Detail-type |
|-------|--------|-------------|
| `smartretail.ims` | IMS `EventBridgeAlertPublisher` | `InventoryAlertEvent` |

#### Detail schema

```json
{
  "alertId":        "<UUID>            required — stable idempotency key",
  "positionId":     "<UUID>            required",
  "skuId":          "<string, max 50>  required",
  "dcId":           "<string, max 50>  required — used as FIFO MessageGroupId",
  "alertType":      "LOW_STOCK | OVERSTOCK  required",
  "severity":       "CRITICAL | HIGH | MEDIUM  required",
  "thresholdValue": "<integer>         required — reorder point",
  "actualValue":    "<integer>         required — current ATP"
}
```

No nullable fields.

#### Severity derivation rules

| Condition | Severity |
|-----------|----------|
| `actualValue <= 0` | `CRITICAL` |
| `actualValue < thresholdValue × 0.5` | `HIGH` |
| otherwise | `MEDIUM` |

#### EventBridge rule

```
Rule name: smartretail-alert-to-re-{env}
Pattern:
  {
    "source": ["smartretail.ims"],
    "detail-type": ["InventoryAlertEvent"]
  }
Target: smartretail-re-alert-{env}.fifo (FIFO SQS)
  MessageGroupId: $.detail.dcId
```

`dcId` must never be null — it is the FIFO partition key. A null `dcId` sends all messages to a single group, destroying ordering guarantees for other DCs.

#### Producer contract (IMS)

- Publish within the `@Transactional` boundary that commits `inventory.stock_alerts`.
- `dcId` must be present and non-null before calling `PutEvents`.
- `alertId` must be the UUID assigned to the `stock_alerts` row.

#### Consumer contract (RE — `AlertSqsListener`)

- Use `alertId` as the natural idempotency key.
- Messages arrive in strict per-`dcId` order; do not parallelise processing within a DC.
- RE must evaluate replenishment rules by (skuId, dcId) and produce a `purchase_orders` row with status `APPROVED` or `PENDING_APPROVAL`.
- Throw `RuntimeException` on any processing failure — SQS will redeliver (FIFO ordering preserved during retry).

---

### 4.4 PurchaseOrderEvent

**Channel:** EventBridge bus → `smartretail-ars-updates-{env}` (Standard SQS)

| Field | Source | Detail-type |
|-------|--------|-------------|
| `smartretail.re` | RE `EventBridgePurchaseOrderPublisher` | `PurchaseOrderEvent` |

Published on two paths:
1. **Auto-approve path** — RE SQS FIFO listener finishes processing an `InventoryAlertEvent`
2. **REST approve/reject path** — SC Planner calls `POST /v1/replenishment/orders/{poId}/approve` or `.../reject`

#### Detail schema

```json
{
  "poId":           "<UUID>            required — stable idempotency key",
  "ruleId":         "<UUID>            required",
  "supplierId":     "<string>          required",
  "skuId":          "<string, max 50>  required",
  "dcId":           "<string, max 50>  required",
  "quantity":       "<integer, > 0>    required",
  "totalValue":     "<decimal, > 0>    required",
  "workflowStatus": "<enum>            required — reflects post-transition state",
  "alertId":        "<UUID | null>     nullable — null for manually triggered POs",
  "createdAt":      "<ISO-8601 instant> required"
}
```

#### `workflowStatus` valid transitions published via this event

| Transition | Trigger |
|------------|---------|
| → `PENDING_APPROVAL` | Auto-approve path, totalValue > autoApproveThreshold |
| → `APPROVED` | Auto-approve path (under threshold) OR planner REST approve |
| → `REJECTED` | Planner REST reject (planner-initiated) |
| → `CANCELLED` | System-level cancellation only (never for planner rejection) |

#### EventBridge rule

```
Rule name: smartretail-all-to-ars-{env}
Pattern:
  {
    "source": ["smartretail.ims", "smartretail.re"]
  }
Target: smartretail-ars-updates-{env} (Standard SQS)
```

This rule also matches `InventoryAlertEvent` from `smartretail.ims` — ARS receives both event types on this queue and must handle both.

#### Producer contract (RE)

- **Auto-approve path:** publish inside the `@Transactional` boundary that inserts `purchase_orders`.
- **REST approve/reject path:** publish only after `UPDATE purchase_orders WHERE version = :v` returns 1 row. If 0 rows updated, throw `OptimisticLockException` (409 to caller) and do **not** publish.
- `workflowStatus` in the event must equal the status committed to RDS.
- Never publish `workflowStatus = CANCELLED` for a planner rejection — use `REJECTED`.

#### Consumer contract (ARS)

- Use `poId` as the natural idempotency key for `PurchaseOrderEvent`.
- ARS receives messages from both `smartretail.ims` and `smartretail.re` on the same queue — deserialise by `detail-type` before processing.
- Standard queue: tolerate out-of-order delivery. A later status (e.g. `APPROVED`) may arrive before an earlier one (e.g. `PENDING_APPROVAL`) — apply last-write-wins by comparing event timestamps or relying on RDS as the source of truth via a re-read.

---

## 5. SQS Queue Configuration Reference

### 5.1 Standard queues

| Property | `ims-sales-{env}` | `ars-updates-{env}` |
|----------|:-----------------:|:-------------------:|
| Type | Standard | Standard |
| Visibility timeout | 120 s | 120 s |
| Message retention | 4 days (default) | 4 days (default) |
| Max receive count | 3 | 3 |
| DLQ retention | 14 days | 14 days |
| Encryption | `SQS_MANAGED` | `SQS_MANAGED` |
| Ordering | Best-effort | Best-effort |
| Deduplication | None | None |

### 5.2 FIFO queue

| Property | `re-alert-{env}.fifo` |
|----------|-----------------------|
| Type | FIFO |
| Visibility timeout | 120 s |
| Message retention | 4 days (default) |
| Max receive count | 3 |
| DLQ | `re-alert-{env}-dlq.fifo` (FIFO) |
| DLQ retention | 14 days |
| Encryption | `SQS_MANAGED` |
| Ordering | Strict per `dcId` |
| Content-based dedup | `true` — 5-minute dedup window based on payload hash |

### 5.3 Visibility timeout rationale

120 seconds covers the worst-case RDS transaction time + EventBridge `PutEvents` roundtrip. If a consumer thread is preempted or the downstream DB is slow, the message will not be re-delivered mid-processing.

### 5.4 Retry exhaustion path

```
Message received
  ↓ Consumer throws RuntimeException
  ↓ Visibility timeout expires (120 s)
  ↓ Message re-delivered (receiveCount + 1)
  ↓ ... (up to maxReceiveCount = 3)
  ↓ On 4th receive: SQS moves to DLQ automatically
```

---

## 6. SageMaker / Batch Post-Processor Channel

### 6.1 S3 trigger configuration

| Parameter | Value |
|-----------|-------|
| Bucket | `smartretail-sagemaker-{env}` |
| Event type | `s3:ObjectCreated:*` |
| Key prefix filter | `sagemaker/output/` |
| Key suffix filter | `.csv` |
| Key pattern | `sagemaker/output/{run_id}/part-*.csv` |

`run_id` is extracted from the S3 key by splitting on `/` at index 2.

### 6.2 CSV row schema (per row)

Each row represents one forecast result. Column order: `sku_id, dc_id, period_start, period_end, p10, p50, p90, model_version`.

The Lambda skips rows that cannot be parsed (logs WARNING) and continues. An empty result set after parsing skips the DFS call entirely.

### 6.3 Lambda processing algorithm

```
1. Extract run_id from S3 object key
2. Download CSV from S3
3. Parse rows; skip malformed rows (log WARNING)
4. If no parseable rows → log WARNING; return success
5. POST /v1/forecast/runs/{runId}/results to DFS
6. HTTP 201 → success
7. Any other HTTP status or network error → throw RuntimeException → Lambda retries
```

Lambda retries: default async invocation retry (2 attempts). S3 event source does not have a configurable DLQ in the prototype — monitor Lambda error metrics.

### 6.4 Idempotency

DFS uses `INSERT ... ON CONFLICT DO NOTHING` keyed on `(run_id, sku_id, dc_id, period_start)`. Lambda retries are safe without additional idempotency tracking.

---

## 7. Idempotency Contract

### 7.1 Mechanisms by surface

| Surface | Mechanism | Key | TTL / Window |
|---------|-----------|-----|-------------|
| Firehose → SIS | SIS checks `sales.idempotency_keys` (RDS SELECT in same transaction as INSERT) | `SHA-256(transactionId)` | 48 h (scheduled DELETE) |
| RE approve/reject REST | `X-Idempotency-Key` request header | Client-supplied UUID | Per request |
| DFS forecast results | `ON CONFLICT DO NOTHING` in INSERT | `(run_id, sku_id, dc_id, period_start)` | Permanent |
| RE FIFO queue | Content-based deduplication | Payload SHA-256 | 5-min window |
| IMS / RE SQS processing | Business key check + optimistic lock | `transactionId` / `alertId` | Bounded by DLQ TTL |

### 7.2 RDS idempotency table schema

Table: `sales.idempotency_keys` (SIS schema, PostgreSQL)

| Column | Type | Role |
|--------|------|------|
| `event_id` | `VARCHAR(64) PK` | SHA-256 hex of `transactionId` |
| `received_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Insertion timestamp for TTL cleanup |

TTL: Spring `@Scheduled` job in SIS deletes rows where `received_at < NOW() - INTERVAL '48 hours'` (runs hourly).
Unlike DynamoDB TTL (which was eventually consistent), RDS DELETE is synchronous and deterministic.

The dedup check and the `sales_events` INSERT are in the **same RDS transaction** — there is no window where
a duplicate can pass the check but the original write fails.

### 7.3 Duplicate handling under Firehose retry

If SIS returns non-2xx (transient failure), Firehose retries the entire batch. On retry, records that were
already committed will be caught by the `idempotency_keys` check and silently skipped.
This is safe — the transaction ensures atomicity.

### 7.4 Rules

1. Any service receiving the same logical event twice must produce the same outcome without additional side effects.
2. Idempotency keys must be derived from the business event identifier (e.g. `transactionId`, `alertId`, `poId`), not from infrastructure-assigned IDs (e.g. SQS `MessageId`, EventBridge `id`).
3. After idempotency key cleanup (48 h scheduled DELETE), re-processing a `transactionId` will be treated as a new event. Design test data accordingly.
4. The FIFO 5-minute content-based deduplication window is an infrastructure safeguard, not a substitute for consumer-level idempotency.

---

## 8. Delivery Guarantees & Ordering

| Channel | Delivery | Ordering | Notes |
|---------|----------|----------|-------|
| Firehose → API GW → SIS | At-least-once | None (Firehose buffers in order but no per-record ordering guarantee) | Firehose HTTP endpoint retry 24 h; fallback to S3 backup |
| EventBridge → SQS Standard | At-least-once | None | May arrive out of order |
| EventBridge → SQS FIFO | At-least-once | Strict per `dcId` group | 5-min dedup window |
| SIS → EventBridge | At-least-once | None | EventBridge retries for 24 h on PutEvents failure |
| IMS → EventBridge | At-least-once | None | Same retry policy |
| RE → EventBridge | At-least-once | None | Same retry policy |
| S3 → Lambda | At-least-once | None | 2 Lambda async retries |

**No channel provides exactly-once delivery.** Consumers must be idempotent (see Section 7).

---

## 9. Transaction Boundaries

### SIS — `@Transactional` scope

```
BEGIN
  SELECT 1 FROM sales.idempotency_keys WHERE event_id = :id  ← dedup check (inside txn)
  If found → ROLLBACK (return DUPLICATE — no further action)
  INSERT INTO sales.sales_events (transaction data)
  INSERT INTO sales.idempotency_keys (event_id, received_at)  ← atomic with sales_events
  EventBridge PutEvents       ← best-effort; called after COMMIT; SIS logs error on failure
COMMIT
```

EventBridge `PutEvents` is called after the JDBC transaction commits. A failure here means
RDS has both rows but the event was not published — monitoring/alerting required (CloudWatch
alarm on SIS EventBridge publish error metric).
S3 raw archive: written by Firehose natively before this flow starts — SIS has no S3 obligation.

### IMS — `@Transactional` scope

```
BEGIN
  UPDATE inventory.inventory_positions WHERE version = :v   ← optimistic lock
  If 0 rows → throw OptimisticLockException (SQS retry)
  INSERT inventory.stock_alerts (if ATP < reorder_point)
  EventBridge PutEvents (InventoryAlertEvent)
COMMIT
```

### RE — `@Transactional` scope (auto-approve path)

```
BEGIN
  INSERT replenishment.purchase_orders
  EventBridge PutEvents (PurchaseOrderEvent)
COMMIT
```

### RE — `@Transactional` scope (REST approve/reject path)

```
BEGIN
  UPDATE replenishment.purchase_orders
    WHERE po_id = :id AND version = :v
    SET workflow_status = ?, version = version + 1
  If 0 rows → throw OptimisticLockException → 409 to caller, no event
  EventBridge PutEvents (PurchaseOrderEvent)
COMMIT
```

The optimistic lock check (`WHERE version = :v`) is non-negotiable (Architecture Rule #9). Any UPDATE that omits it will be caught by ArchUnit tests and will fail the build.

---

## 10. Error Handling & DLQ Policy

### 10.1 Consumer failure handling

```
@SqsListener method throws RuntimeException
  → Spring Cloud AWS lets message visibility expire
  → SQS redelivers after 120-second timeout
  → Up to maxReceiveCount (3) redeliveries
  → On 4th receive: SQS moves message to DLQ
```

Consumers must **not** catch and suppress exceptions for transient failures (DB outage, network timeout) — let SQS retry. Consumers **should** catch and discard structural errors (unparseable JSON) that will never succeed, to avoid unnecessary DLQ accumulation.

### 10.2 Poison message handling

A message with invalid JSON or a schema that cannot be deserialised will fail on every retry and land in the DLQ. Log the message body at ERROR level before discarding to aid investigation. Do not re-drive poison messages without fixing the root cause.

### 10.3 DLQ triage procedure

1. Inspect message body: extract `source`, `detail-type`, and payload from the EventBridge envelope.
2. Identify root cause (schema mismatch / downstream outage / invalid data).
3. Fix root cause first.
4. Re-drive message: use AWS Console **"Start DLQ redrive"** or `aws sqs send-message` pointing to the source queue.
5. Monitor source queue processing for success.

DLQ messages use the same encryption key as the source queue and retain the original EventBridge envelope intact, so re-driving is always safe.

### 10.4 Alerting thresholds (future)

Set CloudWatch alarms on:
- `ApproximateNumberOfMessagesVisible` on any DLQ > 0
- Lambda `Errors` metric > 0 for Batch Post-Processor
- Firehose `DeliveryToHttpEndpoint.Success` < expected rate
- EventBridge `FailedInvocations` > 0 on the custom bus

---

## 11. Schema Versioning & Evolution Rules

| Change type | Backward compatible? | Required action |
|-------------|:--------------------:|-----------------|
| Add nullable field to `detail` | Yes | Consumers must tolerate unknown fields (use `@JsonIgnoreProperties(ignoreUnknown = true)`) |
| Add required field to `detail` | No | Deploy all consumers first; then update producer |
| Remove or rename a field | No | Deploy all consumers first; then update producer |
| Change field type | No | Introduce new `detail-type` (e.g. `SalesTransactionEventV2`); run dual-publish during migration |
| Change enum value | No | Treat as type change |
| Change `source` value | No | Update all EventBridge rules before changing producer |

**Versioning strategy:**
- No `version` field exists in current event schemas.
- Before making any breaking change, add a `schemaVersion` integer field (initial value `1`) to the `detail` of the affected event.
- Use a new `detail-type` suffix (e.g. `SalesTransactionEventV2`) and add a parallel EventBridge rule targeting the new type.
- Remove the old rule and stop dual-publishing only after all consumers have been confirmed upgraded.

---

## 12. LOCAL vs. AWS Mode Differences

| Aspect | LOCAL (profile: `local`) | AWS (profile: `aws`) |
|--------|--------------------------|----------------------|
| Firehose stream | Not used — SIS accepts direct POST in LOCAL mode | `smartretail-ingest-{env}` real stream |
| EventBridge | LocalStack `:4566` | Real EventBridge |
| SQS | LocalStack `:4566` | Real SQS (queue URLs from Parameter Store) |
| RDS idempotency_keys | PostgreSQL Docker (Flyway migration) | RDS via RDS Proxy |
| S3 (SageMaker bucket) | Not used | Real S3 |
| Idempotency checks | Active (PostgreSQL Docker, sales.idempotency_keys) | Active (RDS, same table) |
| Auth | Mock bypass — JWT validation skipped | Cognito JWT validated at API Gateway + service layer |
| Lambda functions | Not deployed locally | Deployed via CDK compute stack |

Spring config injection for LOCAL:
```yaml
# application-local.yml (each service)
spring:
  cloud:
    aws:
      endpoint: http://localhost:4566
      credentials:
        access-key: test
        secret-key: test
      region:
        static: us-east-1
```

Queue URLs in LOCAL mode are the full LocalStack SQS URLs; in AWS mode they are resolved from Parameter Store at startup via `@Value("${smartretail.sqs.*-queue-url}")`.

---

## 13. Hexagonal Architecture Mapping

Each async channel maps to a named port and adapter. Domain code must only reference the port interface.

### Outbound ports (event publishing)

| Service | Port interface | Adapter class | Publishes |
|---------|---------------|---------------|-----------|
| SIS | `EventPublisherPort` | `EventBridgePublisher` | `SalesTransactionEvent` |
| IMS | `AlertPublisherPort` | `EventBridgeAlertPublisher` | `InventoryAlertEvent` |
| RE | `PurchaseOrderEventPublisherPort` | `EventBridgePurchaseOrderPublisher` | `PurchaseOrderEvent` |

### Inbound ports (event consuming)

| Service | Port interface | Adapter class | Consumes |
|---------|---------------|---------------|---------|
| IMS | `InventoryUpdatePort` | `SalesSqsListener` | `SalesTransactionEvent` |
| RE | `ProcessInventoryAlertPort` | `AlertSqsListener` | `InventoryAlertEvent` |

### Idempotency port

| Service | Port interface | Adapter class | Backend |
|---------|---------------|---------------|---------|
| SIS | `IdempotencyPort` | `RdsIdempotencyAdapter` | RDS PostgreSQL (`sales.idempotency_keys`) |

**Forbidden:** AWS SDK classes (`software.amazon.*`) must not appear in any `..domain..**` package. ArchUnit tests enforce this.

---

## 14. Quick Reference — Producer/Consumer Matrix

| Event | Producer service | Producer port/adapter | Consumer service | Consumer port/adapter | Queue |
|-------|------------------|-----------------------|------------------|-----------------------|-------|
| `SalesTransactionEvent` | SIS | `EventBridgePublisher` | IMS | `SalesSqsListener` | `ims-sales-{env}` |
| `InventoryAlertEvent` | IMS | `EventBridgeAlertPublisher` | RE | `AlertSqsListener` | `re-alert-{env}.fifo` |
| `InventoryAlertEvent` | IMS | `EventBridgeAlertPublisher` | ARS | _(future)_ | `ars-updates-{env}` |
| `PurchaseOrderEvent` | RE | `EventBridgePurchaseOrderPublisher` | ARS | _(future)_ | `ars-updates-{env}` |
| POS record batch | Store-Edge Aggregator | — | Firehose → API GW → SIS | `SalesIngestionController` (batch) | Firehose HTTP endpoint |
| SageMaker CSV | SageMaker job | — | Lambda Batch Post-Processor | `BatchPostProcessorHandler` | S3 event |
