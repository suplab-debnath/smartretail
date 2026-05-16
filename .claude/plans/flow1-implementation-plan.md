# Flow 1 Implementation Plan: POS Event → SIS → RDS → IMS → Stock Alert → EventBridge

## Context

Flow 1 is the foundational end-to-end pipeline. Every later flow depends on it. The repo is
specification-complete but implementation-empty — all directories exist, no code has been written.
This plan covers everything needed to get Flow 1 passing all 10 observable evidence checks in
LOCAL mode, with notes on what differs for AWS mode.

---

## Phase 0 — Project Scaffolding

### 0.1 Root Maven Parent POM
**File:** `pom.xml`
- groupId `com.smartretail`, version `1.0.0-SNAPSHOT`, packaging `pom`
- Modules: `services`, `lambdas`, `migrations/flyway`
- Manage dependency versions (Spring Boot 3.3.x BOM, AWS SDK v2 BOM, Jackson, Flyway 10.x, JUnit 5, ArchUnit)
- Plugin management: `spring-boot-maven-plugin`, `openapi-generator-maven-plugin`, `maven-compiler-plugin` (Java 21)

### 0.2 Services Aggregator POM
**File:** `services/pom.xml`
- Modules: `sis`, `ims`, `re`, `ars`

### 0.3 SIS Service POM
**File:** `services/sis/pom.xml`
- Parent: `services/pom.xml`
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jdbc`, `aws-sdk-v2` (kinesis, sqs, s3, eventbridge, dynamodb), `flyway-core`, `openapi-generator` plugin wired to `openapi/sis-api.yaml`
- Profiles: `local` (LocalStack endpoint override), `aws`

### 0.4 IMS Service POM
**File:** `services/ims/pom.xml`
- Same structure as SIS; openapi-generator wired to `openapi/ims-api.yaml`

### 0.5 Lambda POM
**File:** `lambdas/kinesis-consumer/pom.xml`
- Standalone Maven module (not child of services)
- Dependencies: `aws-lambda-java-core`, `aws-lambda-java-events`, `aws-sdk-v2` (dynamodb, s3, http client), `jackson-databind`
- Plugin: `maven-shade-plugin` to produce fat JAR

### 0.6 Flyway Module POM
**File:** `migrations/flyway/pom.xml`
- Standalone module
- Dependencies: `flyway-core`, `flyway-database-postgresql`, `postgresql` JDBC driver
- Config from environment variables: `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`

---

## Phase 1 — OpenAPI Contracts (Contract-First, Non-Negotiable)

### 1.1 SIS OpenAPI YAML
**File:** `openapi/sis-api.yaml`
- `openapi: 3.1.0`, info, servers
- `POST /v1/ingest/events` — request body `SalesEventRequest`, responses 202 `IngestResponse`, 409 `DuplicateEventError`, 400 `ValidationError`
- All schemas defined under `components/schemas` with required fields, format qualifiers, descriptions
- Follow `.claude/standards/openapi.md` strictly (additionalProperties: false, $ref reuse)

### 1.2 IMS OpenAPI YAML
**File:** `openapi/ims-api.yaml`
- `GET /v1/inventory/positions` — query params dcId, skuId, page, size; response `InventoryPositionPage`
- `GET /v1/inventory/alerts` — query params dcId, severity, status, page, size; response `StockAlertPage`
- All schemas under `components/schemas`

**Success criterion:** `mvn generate-sources` produces Java stubs in `services/{sis,ims}/target/generated-sources/openapi/` with no errors.

---

## Phase 2 — Database Migrations (Flyway SQL)

### 2.1 Sales Schema
**File:** `migrations/flyway/src/main/resources/db/migration/V1__create_sales_schema.sql`
- `CREATE SCHEMA IF NOT EXISTS sales;`
- `sales.sales_events` partitioned table (partition by RANGE on event_date)
- Partition `sales_events_current` for prototype window
- Indexes: `idx_sales_sku_dc_date`, `idx_sales_store_date`

### 2.2 Inventory Schema
**File:** `migrations/flyway/src/main/resources/db/migration/V3__create_inventory_schema.sql`
- `CREATE SCHEMA IF NOT EXISTS inventory;`
- `inventory.inventory_positions` with version column (optimistic locking), UNIQUE(sku_id, dc_id)
- `inventory.stock_alerts` with FK to inventory_positions
- Indexes: `idx_inventory_dc_id`, `idx_stock_alerts_status`, `idx_stock_alerts_position`

> V2 (forecasting), V4–V6 are stubs needed by Flyway version ordering — create them as no-ops or include from specs to avoid gaps.

**Success criterion:** `mvn flyway:migrate` against local Postgres → 0 errors, both schemas exist.

---

## Phase 3 — Local Infrastructure

### 3.1 Docker Compose
**File:** `docker-compose.yml`
```
services:
  postgres:   image: postgres:15, port 5432, env POSTGRES_DB=smartretail
  localstack: image: localstack/localstack:3, port 4566, SERVICES=kinesis,sqs,s3,dynamodb,events,lambda,iam
```

### 3.2 LocalStack Init Script
**File:** `scripts/localstack-init.sh`
- Wait for LocalStack health endpoint
- `aws --endpoint-url=http://localhost:4566 kinesis create-stream --stream-name smartretail-events-local --shard-count 1`
- Create SQS queues: `smartretail-ims-sales-local`, `smartretail-re-alert-local.fifo` (FIFO)
- Create S3 bucket: `smartretail-events-local`
- Create DynamoDB table: `smartretail-idempotency-keys-local` (PK: event_id S, TTL: expires_at)
- Create EventBridge bus: `smartretail-events-local`
- Create EventBridge rule routing `SalesTransactionEvent` → SQS `smartretail-ims-sales-local`
- Create EventBridge rule routing `InventoryAlertEvent` → SQS `smartretail-re-alert-local.fifo`

### 3.3 Makefile
**File:** `Makefile`
Targets for Flow 1:
```
local-up        → docker-compose up -d && scripts/localstack-init.sh
local-migrate   → cd migrations/flyway && mvn flyway:migrate
local-seed      → psql seed for inventory_positions (required for IMS to have rows)
local-sis       → cd services/sis && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
local-ims       → cd services/ims && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
build-all       → mvn clean package -DskipTests
test-flow1      → scripts/smoke-test.sh flow1
```

---

## Phase 4 — CDK Stacks (AWS mode; also defines LocalStack resources structurally)

Only the stacks needed for Flow 1:

### 4.1 CDK Project Init
**File:** `infra/cdk/package.json`, `tsconfig.json`, `cdk.json`, `bin/app.ts`
- `npm init`, install `aws-cdk-lib`, `constructs`
- `bin/app.ts` instantiates: NetworkStack → DataStack → MessagingStack → ComputeStack

### 4.2 NetworkStack
**File:** `infra/cdk/lib/network-stack.ts`
- VPC (2 AZs, private + public subnets), Security Groups for ECS services and RDS Proxy

### 4.3 DataStack
**File:** `infra/cdk/lib/data-stack.ts`
- RDS Aurora PostgreSQL cluster + RDS Proxy
- DynamoDB table `smartretail-idempotency-keys-{env}` (PK: event_id, TTL: expires_at, PAY_PER_REQUEST)
- S3 bucket `smartretail-events-{env}`

### 4.4 MessagingStack
**File:** `infra/cdk/lib/messaging-stack.ts`
- Kinesis stream `smartretail-events-{env}` (1 shard)
- EventBridge bus `smartretail-events-{env}`
- SQS queue `smartretail-ims-sales-{env}` + EventBridge rule (SalesTransactionEvent → SQS)
- SQS FIFO queue `smartretail-re-alert-{env}.fifo` + EventBridge rule (InventoryAlertEvent → SQS FIFO)
- Lambda `smartretail-kinesis-consumer-{env}` with Kinesis event source mapping

### 4.5 ComputeStack (partial — ECS for SIS and IMS)
**File:** `infra/cdk/lib/compute-stack.ts`
- ECS Cluster
- SIS Fargate task definition (port 8080), service, IAM role (DynamoDB, S3, EventBridge, SQS)
- IMS Fargate task definition (port 8081), service, IAM role (SQS read, EventBridge publish)

---

## Phase 5 — Kinesis Consumer Lambda

**Location:** `lambdas/kinesis-consumer/src/main/java/com/smartretail/lambda/`

### 5.1 Handler
**File:** `KinesisConsumerHandler.java`
- Implements `RequestHandler<KinesisEvent, Void>`
- For each Kinesis record:
  1. Decode Base64 + deserialize JSON → `PosEventPayload` record
  2. Call `SisApiClient.postEvent(payload)` via HTTP (SIS REST endpoint)
  3. Log response code; on 409 log "duplicate skipped"; on non-2xx throw to trigger retry

### 5.2 SIS API Client
**File:** `SisApiClient.java`
- Pure Java HTTP client (`java.net.http.HttpClient`)
- Reads SIS endpoint URL from env var `SIS_ENDPOINT_URL`
- No AWS SDK calls — Lambda is infrastructure adapter only

### 5.3 Configuration
**File:** `src/main/resources/` — no Spring, just env vars read directly

**Success criterion:** Lambda receives Kinesis record, forwards to SIS, SIS returns 202 or 409.

---

## Phase 6 — SIS Service (Hexagonal Architecture)

Package root: `com.smartretail.sis`

### 6.1 Domain Layer (`domain/`)
**No AWS imports allowed here (enforced by ArchUnit)**

- `domain/model/SalesEvent.java` — Java record: transactionId, storeId, skuId, dcId, quantity, unitPrice, channel, eventTimestamp
- `domain/model/Channel.java` — enum: POS, ECOMMERCE
- `domain/exception/DuplicateEventException.java` — domain exception
- `domain/port/in/IngestSalesEventUseCase.java` — interface: `IngestResult ingest(SalesEvent event)`
- `domain/port/out/SalesEventRepository.java` — interface: `void save(SalesEvent event)`
- `domain/port/out/IdempotencyStore.java` — interface: `boolean isProcessed(String eventId)`, `void markProcessed(String eventId)`
- `domain/port/out/RawEventArchive.java` — interface: `String archive(SalesEvent event)`
- `domain/port/out/DomainEventPublisher.java` — interface: `void publishSalesTransactionEvent(SalesEvent event)`
- `domain/service/IngestSalesEventService.java` — implements `IngestSalesEventUseCase`:
  1. Compute SHA-256(transactionId)
  2. `idempotencyStore.isProcessed(hash)` → if yes throw `DuplicateEventException`
  3. `salesEventRepository.save(event)`
  4. `rawEventArchive.archive(event)`
  5. `idempotencyStore.markProcessed(hash)` (TTL 48h)
  6. `domainEventPublisher.publishSalesTransactionEvent(event)`

### 6.2 Application Layer (`application/`)
- `application/IngestSalesEventServiceImpl.java` — Spring `@Service` wrapping domain service, `@Transactional`

### 6.3 Adapter — Inbound (`adapter/in/web/`)
- `SalesIngestController.java` — implements generated OpenAPI `IngestApi` interface
  - `@PostMapping("/v1/ingest/events")`
  - Maps `SalesEventRequest` (generated) → `SalesEvent` domain model
  - Calls `IngestSalesEventUseCase.ingest()`
  - Returns `ResponseEntity<IngestResponse>` 202 or 409 on `DuplicateEventException`

### 6.4 Adapter — Outbound (`adapter/out/persistence/`)
- `SalesEventJdbcRepository.java` — implements `SalesEventRepository`
  - Uses `NamedParameterJdbcTemplate`
  - INSERT into `sales.sales_events` with all columns

### 6.5 Adapter — Outbound (`adapter/out/idempotency/`)
- `DynamoDbIdempotencyStore.java` — implements `IdempotencyStore`
  - LOCAL: endpoint `http://localhost:4566`, AWS: standard endpoint
  - `GetItem` on `event_id` key; `PutItem` with `expires_at = now + 172800`

### 6.6 Adapter — Outbound (`adapter/out/archive/`)
- `S3RawEventArchive.java` — implements `RawEventArchive`
  - Puts raw JSON to S3 bucket key: `events/{date}/{transactionId}.json`
  - Returns S3 URI stored in `raw_s3_reference` column

### 6.7 Adapter — Outbound (`adapter/out/eventbridge/`)
- `EventBridgeDomainEventPublisher.java` — implements `DomainEventPublisher`
  - Publishes `SalesTransactionEvent` to EventBridge bus

### 6.8 Configuration
- `SisApplication.java` — `@SpringBootApplication`
- `src/main/resources/application.yml` — common config
- `src/main/resources/application-local.yml` — LocalStack endpoints, mock JWT, JDBC `jdbc:postgresql://localhost:5432/smartretail`
- `src/main/resources/application-aws.yml` — RDS Proxy JDBC URL from env var, real AWS endpoints

### 6.9 ArchUnit Tests
**File:** `src/test/java/com/smartretail/sis/architecture/ArchitectureTest.java`
- Assert domain packages have no `software.amazon.*` imports
- Assert no cross-schema SQL joins (regex on repository classes)

**Success criterion:** `POST /v1/ingest/events` returns 202; second call with same transactionId returns 409; `sales.sales_events` row exists; DynamoDB key exists; S3 object exists; EventBridge event published.

---

## Phase 7 — IMS Service (Hexagonal Architecture)

Package root: `com.smartretail.ims`

### 7.1 Domain Layer (`domain/`)
- `domain/model/InventoryPosition.java` — record: positionId, skuId, dcId, onHand, inTransit, reserved, reorderPoint, safetyStock, version, lastUpdatedAt
- `domain/model/StockAlert.java` — record: alertId, positionId, skuId, dcId, alertType, severity, thresholdValue, actualValue, status, raisedAt
- `domain/model/AlertType.java` — enum: LOW_STOCK, OVERSTOCK
- `domain/model/Severity.java` — enum: CRITICAL, HIGH, MEDIUM
- `domain/port/in/ProcessSalesEventUseCase.java` — interface: `void process(SalesEventDto dto)`
- `domain/port/out/InventoryPositionRepository.java` — interface: `InventoryPosition findBySkuAndDc(String skuId, String dcId)`, `void updateWithOptimisticLock(InventoryPosition position)`
- `domain/port/out/StockAlertRepository.java` — interface: `void save(StockAlert alert)`
- `domain/port/out/InventoryEventPublisher.java` — interface: `void publishInventoryAlertEvent(StockAlert alert)`
- `domain/service/ProcessSalesEventService.java` — implements `ProcessSalesEventUseCase`:
  1. `inventoryPositionRepository.findBySkuAndDc(skuId, dcId)`
  2. Decrement `onHand` by quantity
  3. `inventoryPositionRepository.updateWithOptimisticLock(position)` — throws `OptimisticLockException` on version mismatch (retry up to 3x)
  4. Compute ATP = onHand - reserved
  5. If ATP < reorderPoint: create `StockAlert`, classify severity
  6. `stockAlertRepository.save(alert)`
  7. `inventoryEventPublisher.publishInventoryAlertEvent(alert)`

### 7.2 Adapter — Inbound (`adapter/in/sqs/`)
- `SalesEventSqsListener.java` — `@SqsListener("smartretail-ims-sales-{env}")`
  - Deserializes EventBridge envelope → `SalesEventDto`
  - Calls `ProcessSalesEventUseCase.process(dto)`
  - On `OptimisticLockException` after retries: send to DLQ (Spring SQS handles visibility timeout)

### 7.3 Adapter — Inbound Web (`adapter/in/web/`)
- `InventoryController.java` — implements generated `InventoryApi` interface
  - `GET /v1/inventory/positions` — queries `InventoryPositionRepository`
  - `GET /v1/inventory/alerts` — queries `StockAlertRepository`

### 7.4 Adapter — Outbound (`adapter/out/persistence/`)
- `InventoryPositionJdbcRepository.java` — implements `InventoryPositionRepository`
  - `SELECT ... FROM inventory.inventory_positions WHERE sku_id = :skuId AND dc_id = :dcId`
  - UPDATE with `WHERE position_id = :id AND version = :version` → if 0 rows updated throw `OptimisticLockException`
- `StockAlertJdbcRepository.java` — implements `StockAlertRepository`
  - INSERT into `inventory.stock_alerts`

### 7.5 Adapter — Outbound (`adapter/out/eventbridge/`)
- `EventBridgeInventoryEventPublisher.java` — implements `InventoryEventPublisher`
  - Publishes `InventoryAlertEvent` to EventBridge bus

### 7.6 Configuration
- `ImsApplication.java`
- `application.yml`, `application-local.yml`, `application-aws.yml` (same pattern as SIS)
- Spring Cloud AWS SQS auto-configuration wired to LocalStack in local profile

### 7.7 ArchUnit Tests
- Same domain isolation check as SIS

**Success criterion:** After SIS publishes SalesTransactionEvent, IMS SQS listener fires; `inventory.inventory_positions.on_hand` decremented; `inventory.stock_alerts` row created; `InventoryAlertEvent` published to EventBridge.

---

## Phase 8 — Seed Data (Required for IMS to have rows to update)

### 8.1 Minimal Flow 1 Seed SQL
**File:** `migrations/flyway/src/main/resources/db/migration/V7__seed_data.sql` (or a separate local seed script)
- INSERT 2–3 `inventory.inventory_positions` rows with known skuId/dcId matching test events
- Set `on_hand = 150`, `reorder_point = 100`, `reserved = 20` so ATP (130) > reorder_point initially
- For negative test: one row where ATP will drop below reorder_point after test event

---

## Phase 9 — End-to-End Verification (Observable Evidence)

Run in this order:

| Check | Command / Query |
|-------|----------------|
| 1.1 Kinesis record | `aws --endpoint-url=http://localhost:4566 kinesis get-records ...` |
| 1.2 Lambda invoked | Check logs (local: stdout; AWS: CloudWatch `/aws/lambda/smartretail-kinesis-consumer`) |
| 1.3 DynamoDB idempotency key | `aws --endpoint-url=http://localhost:4566 dynamodb get-item --table-name smartretail-idempotency-keys-local --key '{"event_id":{"S":"<sha256>"}}'` |
| 1.4 SIS log | `grep "SalesTransactionEvent processed" <sis-logs>` |
| 1.5 RDS sales_events row | `SELECT * FROM sales.sales_events WHERE transaction_id = '<uuid>'` |
| 1.6 S3 archive | `aws --endpoint-url=http://localhost:4566 s3 ls s3://smartretail-events-local/` |
| 1.7 EventBridge → SQS → IMS | IMS log: `grep "SQS message received"` |
| 1.8 IMS inventory updated | `SELECT on_hand FROM inventory.inventory_positions WHERE sku_id = 'SKU-4423' AND dc_id = 'DC-LONDON'` |
| 1.9 Stock alert created | `SELECT * FROM inventory.stock_alerts WHERE status = 'ACTIVE' ORDER BY raised_at DESC LIMIT 5` |
| 1.10 InventoryAlertEvent published | IMS log: `grep "InventoryAlertEvent published"` |
| Duplicate test | Re-run `publish-pos-event.py` with same transactionId → SIS returns 409, no new DB row |

### Smoke Test
`scripts/smoke-test.sh` already exists and covers Flow 1 assertions. Run it after all services are up.

---

## Execution Order Summary

```
0.1–0.6  Maven scaffolding (parent pom, service poms, lambda pom, flyway pom)
1.1–1.2  OpenAPI YAMLs (sis-api.yaml, ims-api.yaml)
         → mvn generate-sources (verify stubs generated)
2.1–2.2  SQL migrations (V1, V3; stubs for V2/V4-V6)
3.1–3.3  docker-compose.yml, localstack-init.sh, Makefile
         → make local-up && make local-migrate (verify DB schemas exist)
4.1–4.5  CDK stacks (for AWS mode; can defer until local mode is proven)
5.1–5.3  Lambda implementation
6.1–6.9  SIS service (domain → ports → adapters → config → tests)
7.1–7.7  IMS service (domain → ports → adapters → config → tests)
8.1      Seed data
Phase 9  End-to-end run: make local-up → make local-migrate → make local-seed
         → make local-sis & make local-ims & → python scripts/publish-pos-event.py
         → scripts/smoke-test.sh → ✅ 10/10 checks pass
```

---

## Files to Create (Complete List for Flow 1)

### Maven
- `pom.xml`
- `services/pom.xml`
- `services/sis/pom.xml`
- `services/ims/pom.xml`
- `lambdas/kinesis-consumer/pom.xml`
- `migrations/flyway/pom.xml`

### OpenAPI
- `openapi/sis-api.yaml`
- `openapi/ims-api.yaml`

### SQL Migrations
- `migrations/flyway/src/main/resources/db/migration/V1__create_sales_schema.sql`
- `migrations/flyway/src/main/resources/db/migration/V2__create_forecasting_schema.sql` (stub)
- `migrations/flyway/src/main/resources/db/migration/V3__create_inventory_schema.sql`
- `migrations/flyway/src/main/resources/db/migration/V7__seed_data.sql`

### Infrastructure
- `docker-compose.yml`
- `scripts/localstack-init.sh`
- `Makefile`

### CDK (AWS mode)
- `infra/cdk/package.json`, `tsconfig.json`, `cdk.json`
- `infra/cdk/bin/app.ts`
- `infra/cdk/lib/network-stack.ts`
- `infra/cdk/lib/data-stack.ts`
- `infra/cdk/lib/messaging-stack.ts`
- `infra/cdk/lib/compute-stack.ts`

### Lambda
- `lambdas/kinesis-consumer/src/main/java/com/smartretail/lambda/KinesisConsumerHandler.java`
- `lambdas/kinesis-consumer/src/main/java/com/smartretail/lambda/SisApiClient.java`

### SIS Service (~15 Java files)
- `SisApplication.java`
- `domain/model/SalesEvent.java`, `Channel.java`, `DuplicateEventException.java`
- `domain/port/in/IngestSalesEventUseCase.java`
- `domain/port/out/SalesEventRepository.java`, `IdempotencyStore.java`, `RawEventArchive.java`, `DomainEventPublisher.java`
- `domain/service/IngestSalesEventService.java`
- `adapter/in/web/SalesIngestController.java`
- `adapter/out/persistence/SalesEventJdbcRepository.java`
- `adapter/out/idempotency/DynamoDbIdempotencyStore.java`
- `adapter/out/archive/S3RawEventArchive.java`
- `adapter/out/eventbridge/EventBridgeDomainEventPublisher.java`
- `src/main/resources/application.yml`, `application-local.yml`, `application-aws.yml`
- `src/test/java/.../architecture/ArchitectureTest.java`

### IMS Service (~18 Java files)
- `ImsApplication.java`
- `domain/model/InventoryPosition.java`, `StockAlert.java`, `AlertType.java`, `Severity.java`
- `domain/port/in/ProcessSalesEventUseCase.java`
- `domain/port/out/InventoryPositionRepository.java`, `StockAlertRepository.java`, `InventoryEventPublisher.java`
- `domain/service/ProcessSalesEventService.java`
- `adapter/in/sqs/SalesEventSqsListener.java`
- `adapter/in/web/InventoryController.java`
- `adapter/out/persistence/InventoryPositionJdbcRepository.java`, `StockAlertJdbcRepository.java`
- `adapter/out/eventbridge/EventBridgeInventoryEventPublisher.java`
- `src/main/resources/application.yml`, `application-local.yml`, `application-aws.yml`
- `src/test/java/.../architecture/ArchitectureTest.java`
