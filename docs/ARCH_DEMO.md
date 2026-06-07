# SmartRetail — Demo Environment Architecture

> **Purpose:** SC Planner showcase. Five backend services, pre-seeded data (no real-time POS
> ingestion), single-MFE deployment. Intended lifespan: 1–2 days. CDK stack prefix: `Min-*`.

---

## 1. Environment Summary

| Property              | Value                                                                                     |
|-----------------------|-------------------------------------------------------------------------------------------|
| Environment name      | `demo`                                                                                    |
| Spring profile        | `demo`                                                                                    |
| CDK stacks            | `Min-Network` · `Min-Data` · `Min-Messaging` · `Min-Compute` · `Min-Identity` · `Min-Api` |
| CPU architecture      | ARM64 (Graviton)                                                                          |
| VPC type              | Default account VPC (looked up by CDK, not created)                                       |
| Subnet tier           | Public only (no private subnets in default VPC)                                           |
| RDS proxy             | None — ECS tasks connect directly to the RDS instance                                     |
| SIS / Firehose        | Absent — sales data pre-seeded via Flyway V7–V9                                           |
| MFEs deployed         | SC Planner only (:5174)                                                                   |
| ECS task min / max    | 1 / 2 (CPU scaling at 70%)                                                                |
| ECS task size         | 256 CPU units · 512 MiB                                                                   |
| Log retention         | 2 weeks                                                                                   |
| Removal policy        | DESTROY (all resources)                                                                   |

---

## 2. Network Topology

```
                              INTERNET
                                 │
           ┌─────────────────────┤─────────────────────────────────────┐
           │                     │                                     │
  ┌────────▼────────┐   ┌────────▼──────────────────────────────────┐  │
  │  Amazon Cognito │   │              Amazon CloudFront            │  │
  │  Internal Pool  │   │         (HTTPS → MFE distribution)        │  │
  │                 │   └───────────────────┬───────────────────────┘  │
  │  Groups:        │                       │                          │
  │  • STORE_MANAGER│   ┌───────────────────▼───────────────────────┐  │
  │  • SC_PLANNER   │   │             Amazon S3                     │  │
  │  • EXECUTIVE    │   │  smartretail-mfe-demo-sc-planner-{acct}   │  │
  │  • ADMIN        │   │  (static React MFE bundle)                │  │
  └────────┬────────┘   └───────────────────────────────────────────┘  │
           │ JWT Bearer token                                          │
  ┌────────▼────────────────────────────────────────────────────────┐  │
  │                Amazon API Gateway (Regional REST API)           │  │
  │             smartretail-api-demo  │  stage: internal            │  │
  │                                                                 │  │
  │  /v1/dashboard/{proxy+}      ANY → ARS  :8083  via VPC Link     │  │
  │  /v1/inventory/{proxy+}      ANY → IMS  :8081  via VPC Link     │  │
  │  /v1/forecast/{proxy+}       ANY → DFS  :8084  via VPC Link     │  │
  │  /v1/replenishment/{proxy+}  ANY → RE   :8082  via VPC Link     │  │
  │  /v1/supplier/{proxy+}       ANY → SUP  :8085  via VPC Link     │  │
  │                                                                 │  │
  │  CORS: all origins (*)  │  4xx/5xx gateway responses CORS-safe  │  │
  └────────┬────────────────────────────────────────────────────────┘  │
           │ VPC Link: smartretail-vpclink-demo (backed by NLB)        │
           │                                                           │
┌──────────▼───────────────────────────────────────────────────────────▼────┐
│  DEFAULT VPC  (172.31.0.0/16 — account default; CIDR varies per account)  │
│                                                                           │
│  ┌──────────────────── PUBLIC SUBNETS (all AZs) ────────────────────────┐ │
│  │                                                                      │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐ │ │
│  │  │  NLB: smartretail-nlb-demo   (internal, not internet-facing)    │ │ │
│  │  │  Protocol: TCP  │  Subnets: public                              │ │ │
│  │  │                                                                 │ │ │
│  │  │  Listeners → Target Groups (health: HTTP /actuator/health):     │ │ │
│  │  │    :8081 TCP → imsContainer   (interval 30s, 2 healthy / 3 ×)   │ │ │
│  │  │    :8082 TCP → reContainer    (deregistration delay: 30s)       │ │ │
│  │  │    :8083 TCP → arsContainer                                     │ │ │
│  │  │    :8084 TCP → dfsContainer                                     │ │ │
│  │  │    :8085 TCP → supContainer                                     │ │ │
│  │  └───────────────────────────────┬─────────────────────────────────┘ │ │
│  │                                  │                                   │ │
│  │  ┌───────────────────────────────▼─────────────────────────────────┐ │ │
│  │  │  ECS Cluster: smartretail-demo                                  │ │ │
│  │  │  Launch type: Fargate  │  Arch: ARM64  │  Container Insights V2 │ │ │
│  │  │  CloudMap namespace: smartretail.local                          │ │ │
│  │  │                                                                 │ │ │
│  │  │  Security Group: sgEcsTasks                                     │ │ │
│  │  │    Ingress: TCP 8080–8086  from VPC CIDR                        │ │ │
│  │  │    Ingress: all TCP        from sgEcsTasks (svc-to-svc)         │ │ │
│  │  │    Egress:  all (0.0.0.0/0 — ECR, SQS, EventBridge, Secrets)    │ │ │
│  │  │                                                                 │ │ │
│  │  │  ┌───────────────────────────────────────────────────────────┐  │ │ │
│  │  │  │  Persistent Services                                      │  │ │ │
│  │  │  │  desired=1 · max=2 · scale on CPU>70% · circuit breaker   │  │ │ │
│  │  │  │  assignPublicIp=true · profile=demo                       │  │ │ │
│  │  │  │                                                           │  │ │ │
│  │  │  │  IMS  :8081   inventory schema                            │  │ │ │
│  │  │  │  RE   :8082   replenishment schema                        │  │ │ │
│  │  │  │  ARS  :8083   multi-schema (no cross-schema JOINs)        │  │ │ │
│  │  │  │  DFS  :8084   forecasting schema                          │  │ │ │
│  │  │  │  SUP  :8085   supplier schema                             │  │ │ │
│  │  │  │                                                           │  │ │ │
│  │  │  │  Env vars (all services):                                 │  │ │ │
│  │  │  │    SMARTRETAIL_ENV=demo  AWS_REGION=us-east-1             │  │ │ │
│  │  │  │    RDS_PROXY_ENDPOINT=<rds-instance-hostname>             │  │ │ │
│  │  │  │    DB_PASSWORD injected from Secrets Manager at start     │  │ │ │
│  │  │  │    COGNITO_ISSUER_URI=https://cognito-idp.{region}.       │  │ │ │
│  │  │  │                        amazonaws.com/{poolId}             │  │ │ │
│  │  │  └───────────────────────────────────────────────────────────┘  │ │ │
│  │  │                                                                 │ │ │
│  │  │  ┌───────────────────────────────────────────────────────────┐  │ │ │
│  │  │  │  Flyway Migration Task (run-task only — not a service)    │  │ │ │
│  │  │  │  Family: smartretail-flyway-demo                          │  │ │ │
│  │  │  │  256 CPU · 512 MiB · ARM64 · assignPublicIp=true          │  │ │ │
│  │  │  │  Image: flyway/flyway:10-alpine + SQL files               │  │ │ │
│  │  │  │  FLYWAY_SCHEMAS: public,sales,forecasting,inventory,      │  │ │ │
│  │  │  │                  replenishment,supplier,promotions        │  │ │ │
│  │  │  │  FLYWAY_PASSWORD injected from Secrets Manager            │  │ │ │
│  │  │  │  Logs: /smartretail/flyway/demo                           │  │ │ │
│  │  │  └───────────────────────────────────────────────────────────┘  │ │ │
│  │  └───────────────────────────────┬─────────────────────────────────┘ │ │
│  │                                  │ TCP :5432                         │ │
│  │  ┌───────────────────────────────▼─────────────────────────────────┐ │ │
│  │  │  RDS: smartretail-rds-demo                                      │ │ │
│  │  │  Engine: PostgreSQL 16.13  │  Instance: t4g.micro               │ │ │
│  │  │  Storage: 20 GiB GP2  │  Single-AZ  │  Encrypted at rest        │ │ │
│  │  │  Backup: 0 days  │  No RDS Proxy  │  Deletion protection: off   │ │ │
│  │  │  DB name: smartretail  │  Admin: smartretail_admin              │ │ │
│  │  │  Schemas: public · sales · forecasting · inventory ·            │ │ │
│  │  │           replenishment · supplier · promotions                 │ │ │
│  │  │  CW Logs: postgresql → /aws/rds/instance/…  (2 wks)             │ │ │
│  │  │  Secret: smartretail-rds-secret-demo (Secrets Manager)          │ │ │
│  │  │                                                                 │ │ │
│  │  │  Security Group: sgRds                                          │ │ │
│  │  │    Ingress: TCP 5432  from sgEcsTasks only                      │ │ │
│  │  │    Egress:  none                                                │ │ │
│  │  └─────────────────────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 3. SQS Queues

| Queue name                           | Type     | Visibility | DLQ (max receive) | Encryption   | Note                  |
|--------------------------------------|----------|------------|-------------------|--------------|----------------------|
| `smartretail-ims-sales-demo`         | Standard | 120 s      | …-dlq (3×)        | SQS-managed  | Provisioned; idle — no EventBridge rule routes to it (SIS absent, no `SalesTransactionEvent` published) |
| `smartretail-re-alert-demo.fifo`     | FIFO     | 120 s      | …-dlq.fifo (3×)   | SQS-managed  | Content-based dedup; `messageGroupId=$.detail.dcId` |
| `smartretail-ars-updates-demo`       | Standard | default    | …-dlq (3×)        | SQS-managed  | Dashboard aggregation |

> **Why 3 queues?** Demo has no PPS service and no SIS service. The IMS sales queue is wired in CDK for consistency but receives no messages; only 2 queues (`re-alert` and `ars-updates`) carry live traffic during demos.

---

## 4. EventBridge

**Bus:** `smartretail-events-demo`

| Rule name                            | Source                         | Detail type           | Target                        | Notes                              |
|--------------------------------------|--------------------------------|-----------------------|-------------------------------|------------------------------------|
| `smartretail-alert-to-re-demo`       | `smartretail.ims`              | `InventoryAlertEvent` | `re-alert-demo.fifo`          | `messageGroupId = $.detail.dcId`   |
| `smartretail-all-to-ars-demo`        | `smartretail.ims`, `smartretail.re` | any              | `ars-updates-demo`            | Dashboard aggregation              |

> Note: IMS publishes events; RE reads the FIFO queue and publishes in turn; ARS consumes the
> updates queue. SIS is absent in demo — no `SalesTransactionEvent` rule is needed.

---

## 5. API Gateway Routes

**API name:** `smartretail-api-demo` · **Stage:** `internal` · **Type:** Regional REST

| Path pattern               | Method | Backend service | Port   | Integration      |
|----------------------------|--------|-----------------|--------|------------------|
| `/v1/dashboard/{proxy+}`   | ANY    | ARS             | 8083   | HTTP_PROXY / VPC Link |
| `/v1/inventory/{proxy+}`   | ANY    | IMS             | 8081   | HTTP_PROXY / VPC Link |
| `/v1/forecast/{proxy+}`    | ANY    | DFS             | 8084   | HTTP_PROXY / VPC Link |
| `/v1/replenishment/{proxy+}`| ANY   | RE              | 8082   | HTTP_PROXY / VPC Link |
| `/v1/supplier/{proxy+}`    | ANY    | SUP             | 8085   | HTTP_PROXY / VPC Link |

Integration URI pattern: `http://{nlb-dns}:{port}/v1/{pathPart}/{proxy}` — the path prefix is
prepended in the URI because API Gateway's `{proxy}` captures only the suffix after the resource
path.

---

## 6. IAM Roles

### EcsExecutionRole
Assumed by: `ecs-tasks.amazonaws.com`

| Permission | Source |
|-----------|--------|
| Pull images from ECR, write to CloudWatch Logs | `AmazonECSTaskExecutionRolePolicy` (managed) |
| `secretsmanager:GetSecretValue` on `smartretail-rds-secret-demo` | `grantRead()` — used to inject `DB_PASSWORD` and `FLYWAY_PASSWORD` |

### Per-service Task Roles

| Role | Allowed actions | Resources |
|------|----------------|-----------|
| `imsTaskRole` | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes` | `smartretail-ims-sales-demo` |
| | `events:PutEvents` | `smartretail-events-demo` bus |
| | `rds-db:connect` | `dbuser:*/smartretail_admin` |
| `reTaskRole` | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`, `ChangeMessageVisibility` | `smartretail-re-alert-demo.fifo` |
| | `events:PutEvents` | `smartretail-events-demo` bus |
| | `rds-db:connect` | `dbuser:*/smartretail_admin` |
| `arsTaskRole` | `rds-db:connect` | `dbuser:*/smartretail_admin` |
| `dfsTaskRole` | `events:PutEvents` | `smartretail-events-demo` bus |
| | `rds-db:connect` | `dbuser:*/smartretail_admin` |
| `supTaskRole` | `events:PutEvents` | `smartretail-events-demo` bus |
| | `rds-db:connect` | `dbuser:*/smartretail_admin` |

---

## 7. Data Flows

### Flow 2 — Inventory Alert → RE Auto-approve (live during demo)

```
SC Planner MFE
  → CloudFront → API Gateway /v1/replenishment/* (JWT validated)
    → VPC Link → NLB :8082
      → RE :8082 (reads replenishment schema, queries RDS)
        → publishes ReplenishmentOrderCreated to EventBridge
          → ars-updates-demo (SQS)
            → ARS polls queue, updates in-memory aggregates
```

### Flow 3 — SC Planner approves / rejects PO

```
SC Planner MFE
  → API Gateway /v1/replenishment/v1/purchase-orders/{id}/approve  (POST)
    → RE :8082
      → UPDATE purchase_orders SET status='APPROVED', version=v+1
        WHERE id=:id AND status='PENDING_APPROVAL' AND version=:v
          → publishes PurchaseOrderApprovedEvent to EventBridge
            → ars-updates-demo → ARS aggregates
```

### Flow 4 — Dashboard reads (ARS)

```
MFE → API Gateway /v1/dashboard/* → ARS :8083
  ARS reads each schema independently (no cross-schema JOINs):
    inventory schema   → stock levels
    replenishment schema → PO pipeline
    forecasting schema   → demand forecasts
    supplier schema      → supplier performance
  → merged in Java, returned as JSON
```

### Flyway migration (run once per deploy)

```
Developer workstation:  make demo-push-flyway
  → docker buildx build --platform linux/arm64 backend/migrations/
  → docker push {ecr}/smartretail-flyway-demo:latest

Developer workstation:  make demo-migrate
  → reads SSM /smartretail/demo/network/ecs-subnet-ids + sg-ecs-tasks-id
  → aws ecs run-task --launch-type FARGATE
      --task-definition smartretail-flyway-demo
      --network-configuration {subnets, sgEcsTasks, assignPublicIp=ENABLED}
  → ECS task starts, connects RDS :5432 via sgEcsTasks
  → Flyway applies V1…V9 migrations then exits 0
  → aws ecs wait tasks-stopped → reports result
```

---

## 8. Observability

| Signal         | Detail                                                                  |
|----------------|-------------------------------------------------------------------------|
| Container logs | CloudWatch Logs `/smartretail/{svc}/demo` · retention 2 weeks          |
| Flyway logs    | CloudWatch Logs `/smartretail/flyway/demo` · retention 2 weeks         |
| RDS logs       | `postgresql` log type exported to CloudWatch · retention 2 weeks       |
| Metrics        | Container Insights V2 on ECS cluster (CPU, memory, task counts)       |
| Health checks  | NLB HTTP `/actuator/health` every 30 s (2 healthy / 3 unhealthy)      |
| Circuit breaker| ECS deployment circuit breaker with rollback enabled                   |
| Log format     | Structured JSON — fields: `timestamp`, `level`, `service`, `correlationId`, `traceId` |
| Error format   | RFC 7807 `ProblemDetail` on all 4xx/5xx responses                     |

---

## 9. Key Resource Names

| Resource              | Name / Pattern                                             |
|-----------------------|------------------------------------------------------------|
| ECS cluster           | `smartretail-demo`                                         |
| RDS instance          | `smartretail-rds-demo`                                     |
| RDS secret            | `smartretail-rds-secret-demo`                              |
| NLB                   | `smartretail-nlb-demo`                                     |
| VPC Link              | `smartretail-vpclink-demo`                                 |
| API Gateway           | `smartretail-api-demo` (stage `internal`)                  |
| EventBridge bus       | `smartretail-events-demo`                                  |
| ECR repos             | `smartretail-{ims,re,ars,dfs,sup,flyway}-demo`             |
| MFE S3 bucket         | `smartretail-mfe-demo-sc-planner-{accountId}`              |
| SSM prefix            | `/smartretail/demo/`                                       |
| CloudMap namespace    | `smartretail.local`                                        |
| Flyway task family    | `smartretail-flyway-demo`                                  |

---

## 10. CDK Stack Dependency Order

```
Min-Network
  └── Min-Data         (needs VPC + SGs for RDS placement + ECR repos)
        └── Min-Messaging    (no VPC dependency — SQS/EventBridge only)
              └── Min-Identity     (Cognito — no VPC dependency)
                    └── Min-Compute  (needs VPC, Data, Messaging, Identity)
                          └── Min-Api    (needs VPC, Compute, Data, Messaging)
```
