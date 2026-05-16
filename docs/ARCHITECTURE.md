# Architecture — Confirmed Decisions
 
This document is the single source of truth for all architecture decisions
in the Smart Retail prototype. Every decision here is confirmed and locked.
Do not generate code that contradicts these decisions.
 
---
 
## Platform Overview
 
**Platform name:** Smart Retail Demand Forecasting & Supply Chain Platform
**Primary region:** AWS us-east-1 (N. Virginia)
**Deployment:** ECS Fargate for all bounded-context services
 
---
 
## Bounded Context Services
 
Seven services. All run on ECS Fargate. All use Java 21 + Spring Boot 3.3.x.
All follow Hexagonal Architecture (Ports & Adapters).
 
| Service | Abbreviation | Owns | REST surface |
|---------|-------------|------|-------------|
| Sales Ingestion Service | SIS | sales schema | POST /v1/ingest/events |
| Demand Forecasting Service | DFS | forecasting schema | GET /v1/forecast/** |
| Inventory Management Service | IMS | inventory schema | GET /v1/inventory/** |
| Replenishment Engine | RE | replenishment schema | POST/GET /v1/replenishment/** |
| Supplier Integration Service | SUP | supplier schema | GET /v1/supplier/** |
| Pricing & Promotions Service | PPS | promotions schema | None — event-driven only |
| Analytics & Reporting Service | ARS | None (read-only) | GET /v1/dashboard/** |
 
**Prototype scope: SIS, IMS, RE, ARS, DFS, SUP are implemented. PPS is a stub.**
 
---
 
## Lambda ML Adapters (not in prototype scope)
 
Three Lambda functions exist in production but are NOT part of this prototype:
- Kinesis Consumer Lambda (SIS inbound adapter) — IS in prototype scope
- SageMaker Trigger Lambda — not in prototype scope
- Batch Post-Processor Lambda — not in prototype scope
 
---
 
## Hexagonal Architecture — Package Structure
 
Every ECS service follows this exact package structure:
 
```
com.smartretail.{service}/
├── domain/
│   ├── model/           ← aggregates, entities, value objects (zero AWS imports)
│   └── usecase/         ← application services / use cases
├── port/
│   ├── inbound/         ← inbound port interfaces (called by adapters)
│   └── outbound/        ← outbound port interfaces (implemented by adapters)
└── adapter/
    ├── inbound/
    │   ├── rest/        ← Spring MVC REST controllers
    │   └── sqs/         ← SQS message listeners
    └── outbound/
        ├── persistence/ ← Spring Data JDBC repositories
        ├── event/       ← EventBridge publisher
        └── messaging/   ← SQS sender
```
 
**Rule:** Nothing in `domain/` or `port/` may import from `software.amazon.*`
or any AWS SDK package. All AWS SDK usage is in `adapter/` only.
 
---
 
## Data Architecture
 
### Primary Store: Amazon RDS for PostgreSQL
 
- Instance class: db.t3.medium (prototype) / db.r6g.large (production)
- Multi-AZ: enabled (even for prototype to validate the pattern)
- Engine version: PostgreSQL 15.x
- PITR: enabled, 7-day retention
- Connection: ALL services connect via RDS Proxy only
- IAM database authentication: enabled on RDS Proxy
- Database name: `smartretail`
- Six schemas, one per bounded context
 
### RDS Proxy
 
- All ECS task IAM roles grant `rds-db:connect` to the RDS Proxy
- No password-based connection strings in application code
- Connection pool: 10 connections per ECS task (HikariCP max-pool-size = 10)
- Endpoint stored in Parameter Store at `/smartretail/{env}/rds/proxy-endpoint`
 
### Secondary Store: Amazon DynamoDB
 
- Table name: `smartretail-idempotency-keys-{env}`
- Purpose: SIS deduplication only
- PK: `event_id` (SHA-256 of transactionId)
- TTL attribute: `expires_at` (processed_at + 48 hours)
- Capacity: On-Demand
- No GSIs
 
### S3
 
- `smartretail-events-{env}`: raw POS JSON archive (SIS writes)
- `smartretail-mfe-{env}-{mfe-name}`: MFE static assets (4 buckets)
 
---
 
## Messaging Architecture
 
### Kinesis Data Streams
 
- Stream name: `smartretail-events-{env}`
- Shard mode: On-Demand
- Retention: 7 days
- Consumer: Kinesis Consumer Lambda (SIS inbound adapter)
 
### EventBridge
 
- Bus name: `smartretail-events-{env}`
- All domain events published here
- EventBridge rules route to SQS queues per subscriber
 
### SQS Queues (prototype scope)
 
| Queue | Type | Consumer | Source event |
|-------|------|----------|-------------|
| `smartretail-ims-sales-{env}` | Standard | IMS | Sales transaction event |
| `smartretail-re-alert-{env}` | FIFO | RE | Inventory alert event |
| `smartretail-ars-updates-{env}` | Standard | ARS | All domain events |
 
Each queue has a paired DLQ: `{queue-name}-dlq`
 
FIFO queue message group ID for RE: `{dcId}#{skuId}` (ensures ordering per DC+SKU)
 
---
 
## API Gateway
 
- Type: REST API (not HTTP API — REST API required for VPC Link)
- Integration: VPC Link to ECS services on port 8080
- Authoriser: Cognito JWT authoriser on all endpoints except /health
- Stages: `internal` (staff) and `supplier` (external supplier portal)
- No ALB — API Gateway VPC Link is the sole ingress path to ECS
 
---
 
## Identity
 
### Cognito Internal User Pool
 
- User pool name: `smartretail-internal-{env}`
- Groups: STORE_MANAGER, SC_PLANNER, EXECUTIVE, ADMIN
- Auth flow: PKCE OAuth 2.0
- Token validity: access token 1 hour, refresh token 8 hours
- MFA: optional for prototype (required in production)
 
### Cognito Supplier User Pool
 
- User pool name: `smartretail-supplier-{env}`
- Groups: SUPPLIER, SUPPLIER_ADMIN
- Custom attribute: `custom:supplierId` (UUID)
- Auth flow: PKCE OAuth 2.0
- Token validity: access token 30 minutes, refresh token 4 hours
- MFA: TOTP (required)
- Admin approval: required before supplier can authenticate
 
---
 
## Security
 
### IAM Task Roles (one per ECS service)
 
Each ECS task role grants ONLY what that service needs:
 
**SIS task role:**
- `rds-db:connect` to RDS Proxy (sales schema user)
- `dynamodb:GetItem`, `dynamodb:PutItem` on idempotency-keys table
- `s3:PutObject` on smartretail-events bucket
- `events:PutEvents` on EventBridge bus
 
**IMS task role:**
- `rds-db:connect` to RDS Proxy (inventory schema user)
- `sqs:ReceiveMessage`, `sqs:DeleteMessage` on ims-sales queue
- `events:PutEvents` on EventBridge bus
 
**RE task role:**
- `rds-db:connect` to RDS Proxy (replenishment schema user)
- `sqs:ReceiveMessage`, `sqs:DeleteMessage` on re-alert queue
- `events:PutEvents` on EventBridge bus
- `ssm:GetParameter` on Parameter Store /smartretail/* paths
 
**ARS task role:**
- `rds-db:connect` to RDS Proxy (ars_readonly schema user)
- `sqs:ReceiveMessage`, `sqs:DeleteMessage` on ars-updates queue
- READ ONLY — no write permissions anywhere
 
### VPC Endpoints (Interface)
 
Required for all services to reach AWS APIs without internet egress:
- S3 Gateway endpoint
- DynamoDB Gateway endpoint
- SQS Interface endpoint
- EventBridge Interface endpoint
- KMS Interface endpoint
- Secrets Manager Interface endpoint
- ECR Interface endpoints (api + dkr)
- CloudWatch Logs Interface endpoint
 
---
 
## Observability
 
- Logs: CloudWatch Logs via awslogs driver (ECS log configuration)
  - Log group per service: `/smartretail/{service}/{env}`
  - Retention: 30 days
- Metrics: CloudWatch custom metrics namespace `SmartRetail/{service}`
- Traces: AWS X-Ray via OTel Java agent (add as ECS init container)
- ECS: Container Insights enabled on cluster
 
### Structured Log Format (all services)
 
Every log line must be valid JSON with these mandatory fields:
```json
{
  "timestamp": "ISO-8601",
  "level": "INFO|WARN|ERROR",
  "service": "SIS|IMS|RE|ARS",
  "traceId": "W3C trace ID",
  "spanId": "span ID",
  "correlationId": "request correlation ID",
  "message": "human readable message"
}
```
 
PII filter rule: any field name matching `*email*`, `*phone*`, `*contact*`
must be masked before logging.
 
---
 
## ECS Configuration
 
### Cluster
 
- Name: `smartretail-{env}`
- Container Insights: enabled
 
### Task Definition (per service)
 
- CPU: 512 (prototype) — scale up for production
- Memory: 1024 MB (prototype)
- Network mode: awsvpc
- Execution role: shared ECS execution role (ECR pull + CW logs)
- Task role: per-service (see Security section above)
- Health check: `GET /actuator/health` → 200 OK
  - Interval: 30s (prototype) — 10s in production
  - Healthy threshold: 2
  - Unhealthy threshold: 3
- Log driver: awslogs
  - awslogs-group: `/smartretail/{service}/{env}`
  - awslogs-region: us-east-1
  - awslogs-stream-prefix: ecs
 
### Service Configuration
 
- Min tasks: 1 (prototype) — 2 in production
- Max tasks: 3 (prototype)
- Deployment: rolling update
- Health check grace period: 60s
 
---
 
## Spring Boot Configuration
 
Every service has these dependencies in pom.xml:
 
```xml
<!-- Core -->
<dependency>spring-boot-starter-web</dependency>
<dependency>spring-boot-starter-actuator</dependency>
<dependency>spring-boot-starter-validation</dependency>
<dependency>spring-boot-starter-data-jdbc</dependency>
 
<!-- AWS -->
<dependency>software.amazon.awssdk:eventbridge</dependency>
<dependency>software.amazon.awssdk:sqs</dependency>
<dependency>software.amazon.awssdk:ssm</dependency>
<dependency>software.amazon.awssdk:rds</dependency>
 
<!-- Database -->
<dependency>org.postgresql:postgresql</dependency>
<dependency>com.zaxxer:HikariCP</dependency>
<dependency>org.flywaydb:flyway-core</dependency>
 
<!-- Observability -->
<dependency>io.opentelemetry:opentelemetry-api</dependency>
<dependency>micrometer-registry-cloudwatch2</dependency>
```
 
### application.yml baseline (all services)
 
```yaml
spring:
  application:
    name: ${SERVICE_NAME}
  datasource:
    url: jdbc:postgresql://${RDS_PROXY_ENDPOINT}:5432/smartretail?currentSchema=${DB_SCHEMA}
    username: ${DB_USERNAME}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
  flyway:
    enabled: false  # migrations run separately, not at service startup
 
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: never
 
server:
  port: 8080
```
 
All `${...}` values are injected from Parameter Store at container startup
via the AWS Systems Manager Parameter Store integration or as ECS task
environment variables set by CDK.
 
---
 
## React MFE Configuration
 
### Shared @smartretail/auth library
 
All three prototype MFEs use a shared auth module (local package, not published).
Location: `mfe/shared/auth/`
 
The auth module provides:
- `useAuth()` hook: returns `{ user, token, signIn, signOut, isLoading }`
- `AuthProvider`: wraps the app, handles PKCE flow with Cognito
- JWT is stored in memory only — never localStorage or sessionStorage
- Auto-refresh: token refresh 5 minutes before expiry
 
### MFE Build
 
Each MFE is a standalone Create React App (Vite) project.
Build output is uploaded to its dedicated S3 bucket.
CloudFront serves the MFE with OAC.
No Module Federation — each MFE is independently deployed.
 
### API Client
 
Each MFE uses an OpenAPI-generated TypeScript/Axios client.
Base URL: read from `window.SMARTRETAIL_CONFIG.apiGatewayEndpoint`
(injected via CloudFront response headers or config.json in S3)
 
Authorization header: `Bearer ${token}` on every request.
401 responses trigger automatic token refresh then retry once.

 
 