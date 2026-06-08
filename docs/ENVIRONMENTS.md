# SmartRetail — Environment Reference

> Three deployment environments: Demo (1-2 day showcase), Dev (full-stack development), Production (HA, 3-AZ).

---

## Demo Environment (Min-* CDK stacks)

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
│  ┌──────────────────────── PUBLIC SUBNETS (all AZs) ────────────────────────┐ │
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
│  │  │  │    HikariCP: max-pool=5  min-idle=1 (per service)         │  │ │ │
│  │  │  └───────────────────────────────────────────────────────────┘  │ │ │
│  │  │                                                                 │ │ │
│  │  │  ┌───────────────────────────────────────────────────────────┐  │ │ │
│  │  │  │  Flyway Migration Task (run-task only — not a service)    │  │ │ │
│  │  │  │  Family: smartretail-flyway-demo                          │  │ │ │
│  │  │  │  256 CPU · 512 MiB · X86_64 · assignPublicIp=true         │  │ │ │
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

| Queue name                       | Type     | Visibility | DLQ (max receive) | Encryption   | Note                                                                                                    |
|----------------------------------|----------|------------|-------------------|--------------|---------------------------------------------------------------------------------------------------------|
| `smartretail-ims-sales-demo`     | Standard | 120 s      | …-dlq (3×)        | SQS-managed  | Provisioned; idle — no EventBridge rule routes to it (SIS absent, no `SalesTransactionEvent` published) |
| `smartretail-re-alert-demo.fifo` | FIFO     | 120 s      | …-dlq.fifo (3×)   | SQS-managed  | Content-based dedup; `messageGroupId=$.detail.dcId`                                                     |
| `smartretail-ars-updates-demo`   | Standard | default    | …-dlq (3×)        | SQS-managed  | Dashboard aggregation                                                                                   |

> **Why 3 queues?** Demo has no PPS service and no SIS service. The IMS sales queue is wired in CDK for consistency but receives no messages; only 2 queues (`re-alert` and `ars-updates`) carry live traffic during demos.

---

## 4. EventBridge

**Bus:** `smartretail-events-demo`

| Rule name                      | Source                              | Detail type           | Target                | Notes                              |
|--------------------------------|-------------------------------------|-----------------------|-----------------------|------------------------------------|
| `smartretail-alert-to-re-demo` | `smartretail.ims`                   | `InventoryAlertEvent` | `re-alert-demo.fifo`  | `messageGroupId = $.detail.dcId`   |
| `smartretail-all-to-ars-demo`  | `smartretail.ims`, `smartretail.re` | any                   | `ars-updates-demo`    | Dashboard aggregation              |

> Note: IMS publishes events; RE reads the FIFO queue and publishes in turn; ARS consumes the
> updates queue. SIS is absent in demo — no `SalesTransactionEvent` rule is needed.

---

## 5. API Gateway Routes

**API name:** `smartretail-api-demo` · **Stage:** `internal` · **Type:** Regional REST

| Path pattern                | Method | Backend service | Port   | Integration           |
|-----------------------------|--------|-----------------|--------|-----------------------|
| `/v1/dashboard/{proxy+}`    | ANY    | ARS             | 8083   | HTTP_PROXY / VPC Link |
| `/v1/inventory/{proxy+}`    | ANY    | IMS             | 8081   | HTTP_PROXY / VPC Link |
| `/v1/forecast/{proxy+}`     | ANY    | DFS             | 8084   | HTTP_PROXY / VPC Link |
| `/v1/replenishment/{proxy+}`| ANY    | RE              | 8082   | HTTP_PROXY / VPC Link |
| `/v1/supplier/{proxy+}`     | ANY    | SUP             | 8085   | HTTP_PROXY / VPC Link |

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
  → docker buildx build --platform linux/amd64 --pull --load backend/migrations/
     (FROM --platform=$TARGETPLATFORM flyway/flyway:10-alpine — X86_64 native build)
  → docker push {ecr}/smartretail-flyway-demo:latest

Developer workstation:  make demo-migrate
  → reads SSM /smartretail/demo/network/ecs-subnet-ids + sg-ecs-tasks-id
  → aws ecs run-task --launch-type FARGATE
      --task-definition smartretail-flyway-demo   (X86_64)
      --network-configuration {subnets, sgEcsTasks, assignPublicIp=ENABLED}
  → ECS task starts, connects RDS :5432 via sgEcsTasks
  → Flyway applies V1…V9 migrations then exits 0
  → aws ecs wait tasks-stopped → reports result

Developer workstation:  make demo-reset-db          (between demo runs)
  → same ECS run-task with --overrides command=["clean","migrate"]
  → FLYWAY_CLEAN_DISABLED=false — drops all schemas then re-applies V1…V9
  → exits 0 when complete; logs at /smartretail/flyway/demo
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

---

## Dev Environment (Dev-* CDK stacks)

> **Full-stack development deployment.** All 7 backend services, live Firehose POS ingestion,
> SageMaker demand forecasting, 2-AZ VPC, single-AZ RDS, RDS Proxy, 4 MFEs, MonitoringStack.
> CDK stack prefix: `Dev-*`. Deployed via `make aws-deploy-all ENV=dev`.

---

## 1. Environment Summary

| Property              | Value                                                                 |
|-----------------------|-----------------------------------------------------------------------|
| Environment name      | `dev`                                                                 |
| Spring profile        | `aws`                                                                 |
| CDK stacks            | `Dev-Network` · `Dev-Data` · `Dev-Messaging` · `Dev-Hosting` · `Dev-Identity` · `Dev-Compute` · `Dev-Api` · `Dev-Monitoring` |
| CPU architecture      | x86_64                                                                |
| VPC type              | Custom CDK VPC (10.0.0.0/16), 2 AZs, 3 subnet tiers                 |
| Subnet tiers          | Public · PrivateApp · Isolated                                       |
| NAT Gateways          | 1 (in one public subnet; both PrivateApp subnets share it)           |
| RDS proxy             | Yes — all services connect via RDS Proxy in isolated subnets         |
| ECS task min / max    | 1 / 3 (CPU scaling at 70%)                                          |
| ECS task size         | 256 CPU units · 512 MiB                                             |
| Capacity strategy     | FARGATE_SPOT (weight 4) + FARGATE (weight 1)                        |
| Log retention         | 1 month                                                              |
| Removal policy        | DESTROY (all resources — dev is ephemeral)                           |
| CORS origin           | `https://*.smartretail.com`                                          |

---

## 2. Network Topology

### 2.1 VPC Layout (2 AZs × 3 tiers = 6 subnets)

```
VPC: 10.0.0.0/16   (name: smartretail-dev-vpc-dev)
│
├── Public subnets (/24 — one per AZ)
│     AZ-a: ~10.0.0.0/24    AZ-b: ~10.0.1.0/24
│     Contents:
│       • NAT Gateway × 1 (in AZ-a; AZ-b PrivateApp subnet routes through it)
│       • Internet Gateway
│
├── PrivateApp subnets (/24 — one per AZ, egress via single NAT)
│     AZ-a: ~10.0.2.0/24    AZ-b: ~10.0.3.0/24
│     Contents:
│       • ECS Fargate tasks (all 7 services + Flyway run-task)
│       • NLB (internal, not internet-facing)
│       • Lambda functions (Batch Post-Processor, ML Trigger)
│       • VPC Interface Endpoints (ECR, SQS, EventBridge, CW Logs, Secrets Manager)
│
└── Isolated subnets (/24 — one per AZ, no internet route)
      AZ-a: ~10.0.4.0/24    AZ-b: ~10.0.5.0/24
      Contents:
        • RDS PostgreSQL (single-AZ — primary in AZ-a)
        • RDS Proxy (spans both isolated subnets)

Note: CDK assigns CIDRs automatically. Ranges above are representative;
check cdk.context.json after first synth for actuals.
```

### 2.2 VPC Endpoints

| Endpoint type | Service               | Subnets     | Notes                                |
|---------------|-----------------------|-------------|--------------------------------------|
| Gateway       | S3                    | All         | Free; ECR image pulls + S3 access    |
| Interface     | ECR (`ecr.api`)       | PrivateApp  | ECS image pull without NAT           |
| Interface     | ECR Docker (`ecr.dkr`)| PrivateApp  | Image layer pull                     |
| Interface     | SQS                   | PrivateApp  | ECS → SQS without NAT               |
| Interface     | EventBridge           | PrivateApp  | ECS → EventBridge without NAT       |
| Interface     | CloudWatch Logs       | PrivateApp  | Container log delivery               |
| Interface     | Secrets Manager       | PrivateApp  | Secret injection at task launch      |

All interface endpoints share **sgVpcEndpoints**: ingress TCP 443 from VPC CIDR, no outbound.

### 2.3 Full Topology Diagram

```
                                    INTERNET
                                       │
            ┌──────────────────────────┤───────────────────────────────────────────────────────┐
            │                          │                                                       │
   ┌────────▼─────────────────────┐    │ ┌─────────▼───────────────────────────────────┐       │
   │  Amazon Cognito              │    │ │  Amazon CloudFront (HostingStack)           │       │
   │  (IdentityStack)             │    │ │  HTTPS · *.smartretail.com · PriceClass 100 │       │
   │                              │    │ │  Single distribution with 4 path behaviors  │       │
   │  Internal Pool               │    │ │  (each behavior: OAC SigV4 + SPA rewrite fn)│       │
   │  smartretail-internal-dev    │    │ │    /store-manager/* → store-manager S3      │       │
   │  Groups:                     │    │ │    /sc-planner/*    → sc-planner S3         │       │
   │    • STORE_MANAGER           │    │ │    /executive/*     → executive S3          │       │
   │    • SC_PLANNER              │    │ │    /supplier/*      → supplier S3           │       │
   │    • EXECUTIVE · ADMIN       │    │ │    /* (default)     → 302 /sc-planner/      │       │
   │  Domain: smartretail-dev-    │    │ └───────────────────────┬─────────────────────┘       │
   │          internal            │    │          ┌─────────────┼─────────────┼─────────┐      │
   │                              │    │  ┌───────▼──┐ ┌────────▼─┐ ┌─────────▼┐ ┌──────▼───┐  │
   │  Supplier Pool               │    │  │    S3    │ │    S3    │ │    S3    │ │    S3    │  │
   │  smartretail-supplier-dev    │    │  │  store-  │ │   sc-    │ │executive │ │ supplier │  │
   │  Group: SUPPLIER_ADMIN       │    │  │  manager │ │ planner  │ │  -dev-   │ │  -dev-   │  │
   │  Domain: smartretail-dev-    │    │  │  -dev-   │ │  -dev-   │ │  {acct}  │ │  {acct}  │  │
   │          supplier            │    │  │  {acct}  │ │  {acct}  │ │          │ │          │  │
   │  OAuth: /supplier/callback   │    │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
   └────────┬─────────────────────┘    │                                                       │
            │ JWT Bearer token         │                                                       │
   ┌────────▼──────────────────────────────────────────────────────────────────────────────┐   │
   │                  Amazon API Gateway (Regional REST API)                               │   │
   │              smartretail-api-dev  │  stage: internal                                  │   │
   │                                                                                       │   │
   │  Staff routes (VPC Link → NLB HTTP_PROXY):                                            │   │
   │    /v1/dashboard/{proxy+}       → ARS  :8083                                          │   │
   │    /v1/inventory/{proxy+}       → IMS  :8081                                          │   │
   │    /v1/forecast/{proxy+}        → DFS  :8084                                          │   │
   │    /v1/replenishment/{proxy+}   → RE   :8082                                          │   │
   │    /v1/supplier/{proxy+}        → SUP  :8085                                          │   │
   │    /v1/ingest/{proxy+}          → SIS  :8080  (Firehose delivery target)              │   │
   │    /v1/promotions/{proxy+}      → PPS  :8086                                          │   │
   │                                                                                       │   │
   │  System route (EventBridge AWS direct integration, API key required):                 │   │
   │    POST /system/v1/events/promotions → EventBridge PutEvents                          │   │
   │    Source: external.campaign-management │ DetailType: PromotionActivated              │   │
   │    Rate: 50 rps burst 100 │ Quota: 10,000 req/day                                     │   │
   │                                                                                       │   │
   │  CORS: https://*.smartretail.com  │  4xx/5xx CORS-safe gateway responses              │   │
   └───────────────┬────────────────────────────────────────────────────────────────────┬──┘   │
                   │ VPC Link: smartretail-vpclink-dev                                  │      │
                   │                                                                    │      │
   ┌───────────────┴──── Kinesis Data Firehose ─────────────────────────────────────────┘      │
   │  Stream: smartretail-ingest-dev   Type: DirectPut                                         │
   │  HTTP endpoint: {api-url}/v1/ingest/events                                                │
   │  Auth: X-Access-Key (from Secrets Manager)                                                │
   │  Buffering: 1 MiB / 60 s  │  Retry: 86400 s                                               │
   │  S3 backup: AllData → smartretail-events-dev-{acct}/firehose/…                            │
   │             Compression: GZIP  │  Buffering: 5 MiB / 60 s                                 │
   │  Role: FirehoseRole → S3 write on events bucket                                           │
   └───────────────────────────────────────────────────────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────────────────────────────────────┐
│  VPC: 10.0.0.0/16                                                                           │
│                                                                                             │
│  ┌──────────────── PUBLIC SUBNETS (2 AZs) ──────────────────────────────────────────────┐   │
│  │  NAT Gateway (AZ-a only — shared by both PrivateApp subnets)   Internet Gateway      │   │
│  └──────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
│  ┌──────────── PRIVATEAPP SUBNETS (2 AZs, egress via single NAT) ──────────────────────┐    │
│  │                                                                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐    │    │
│  │  │  NLB: smartretail-nlb-dev  (internal, PrivateApp subnets)                   │    │    │
│  │  │  Listeners → Target Groups (health: HTTP /actuator/health, 30 s):           │    │    │
│  │  │    :8080 → sisContainer   :8081 → imsContainer   :8082 → reContainer        │    │    │
│  │  │    :8083 → arsContainer   :8084 → dfsContainer   :8085 → supContainer       │    │    │
│  │  │    :8086 → ppsContainer   (deregistration delay: 30 s)                      │    │    │
│  │  └──────────────────────────────┬──────────────────────────────────────────────┘    │    │
│  │                                 │                                                   │    │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────────┐   │    │
│  │  │  ECS Cluster: smartretail-dev                                                │   │    │
│  │  │  Launch type: Fargate  │  Arch: x86_64  │  Container Insights V2             │   │    │
│  │  │  Capacity: FARGATE_SPOT (weight 4) + FARGATE (weight 1)                      │   │    │
│  │  │  CloudMap namespace: smartretail.local                                       │   │    │
│  │  │                                                                              │   │    │
│  │  │  Security Group: sgEcsTasks                                                  │   │    │
│  │  │    Ingress: TCP 8080–8086  from VPC CIDR (10.0.0.0/16)                       │   │    │
│  │  │    Ingress: all TCP        from sgEcsTasks (svc-to-svc)                      │   │    │
│  │  │    Egress:  all (0.0.0.0/0 — routed via NAT or VPC endpoints)                │   │    │
│  │  │                                                                              │   │    │
│  │  │  ┌────────────────────────────────────────────────────────────────────────┐  │   │    │
│  │  │  │  Persistent Services                                                   │  │   │    │
│  │  │  │  desired=1 · max=3 · scale on CPU>70% · circuit breaker+rollback       │  │   │    │
│  │  │  │  256 CPU · 512 MiB · assignPublicIp=false · profile=aws                │  │   │    │
│  │  │  │                                                                        │  │   │    │
│  │  │  │  SIS  :8080   sales schema        (+ Firehose access key secret)       │  │   │    │
│  │  │  │  IMS  :8081   inventory schema                                         │  │   │    │
│  │  │  │  RE   :8082   replenishment schema                                     │  │   │    │
│  │  │  │  ARS  :8083   multi-schema reads (no cross-schema JOINs)               │  │   │    │
│  │  │  │  DFS  :8084   forecasting schema                                       │  │   │    │
│  │  │  │  SUP  :8085   supplier schema                                          │  │   │    │
│  │  │  │  PPS  :8086   promotions schema                                        │  │   │    │
│  │  │  │                                                                        │  │   │    │
│  │  │  │  Env vars (all services):                                              │  │   │    │
│  │  │  │    SMARTRETAIL_ENV=dev  AWS_REGION=us-east-1                           │  │   │    │
│  │  │  │    RDS_PROXY_ENDPOINT=<proxy-hostname>  SPRING_PROFILES_ACTIVE=aws     │  │   │    │
│  │  │  │    (no DB_PASSWORD — services use rds-db:connect IAM auth)             │  │   │    │
│  │  │  └────────────────────────────────────────────────────────────────────────┘  │   │    │
│  │  │                                                                              │   │    │
│  │  │  ┌────────────────────────────────────────────────────────────────────────┐  │   │    │
│  │  │  │  Flyway Migration Task (run-task only — not a service)                 │  │   │    │
│  │  │  │  Family: smartretail-flyway-dev                                        │  │   │    │
│  │  │  │  256 CPU · 512 MiB · x86_64 · assignPublicIp=false                     │  │   │    │
│  │  │  │  FLYWAY_URL → RDS Proxy :5432                                          │  │   │    │
│  │  │  │  FLYWAY_PASSWORD injected from Secrets Manager (execution role)        │  │   │    │
│  │  │  │  Logs: /smartretail/flyway/dev (1 month, DESTROY)                      │  │   │    │
│  │  │  └────────────────────────────────────────────────────────────────────────┘  │   │    │
│  │  └──────────────────────────────┬───────────────────────────────────────────────┘   │    │
│  │                                 │                                                   │    │
│  │  ┌──────────────────────────────▼──────────────────────────────────────────────┐    │    │
│  │  │  Lambda: smartretail-batch-post-processor-dev                               │    │    │
│  │  │  Trigger: S3 ObjectCreated on smartretail-sagemaker-dev-{acct}              │    │    │
│  │  │           (prefix: sagemaker/output/, suffix: .csv)                         │    │    │
│  │  │  Timeout: 180 s  │  Memory: 512 MiB  │  x86_64                              │    │    │
│  │  │  VPC: PrivateApp subnets  │  SG: sgBatchProcessor (egress all)              │    │    │
│  │  │  Calls: http://smartretail-dfs-dev.smartretail.local:8084 (CloudMap)        │    │    │
│  │  │  Role: S3 GetObject on sagemaker bucket (sagemaker/output/*)                │    │    │
│  │  └─────────────────────────────────────────────────────────────────────────────┘    │    │
│  │                                                                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐    │    │
│  │  │  Lambda: smartretail-ml-trigger-dev                                         │    │    │
│  │  │  Trigger: EventBridge schedule  cron(0 2 * * ? *)  daily 02:00 UTC          │    │    │
│  │  │  Timeout: 300 s  │  Memory: 512 MiB  │  x86_64                              │    │    │
│  │  │  VPC: PrivateApp subnets  │  SG: sgMlTrigger (egress all)                   │    │    │
│  │  │  Calls: sagemaker:StartPipelineExecution on smartretail-demand-forecast-dev │    │    │
│  │  │  Role: S3 read (events bucket), S3 write (sagemaker bucket),                │    │    │
│  │  │        sagemaker:StartPipelineExecution                                     │    │    │
│  │  └─────────────────────────────────────────────────────────────────────────────┘    │    │
│  │                                                                                     │    │
│  │  VPC Interface Endpoints (sgVpcEndpoints: ingress 443 from VPC CIDR):               │    │
│  │    ecr.api · ecr.dkr · sqs · events · logs · secretsmanager                         │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────── ISOLATED SUBNETS (2 AZs, no internet route) ───────────────────────┐      │
│  │                                                                                   │      │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐ │      │
│  │  │  RDS Proxy: smartretail-rds-proxy-dev                                        │ │      │
│  │  │  Subnets: isolated  │  TLS: not required  │  IAM auth: disabled              │ │      │
│  │  │  Secrets: RDS credentials (Secrets Manager)                                  │ │      │
│  │  │                                                                              │ │      │
│  │  │  Security Group: sgRdsProxy                                                  │ │      │
│  │  │    Ingress: TCP 5432  from sgEcsTasks                                        │ │      │
│  │  │    Egress:  all                                                              │ │      │
│  │  └──────────────────────────────┬───────────────────────────────────────────────┘ │      │
│  │                                 │ TCP :5432                                       │      │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────────┐ │      │
│  │  │  RDS: smartretail-rds-dev                                                    │ │      │
│  │  │  Engine: PostgreSQL 16.13  │  Instance: t4g.small                            │ │      │
│  │  │  Storage: 20 GiB GP2  │  Single-AZ (dev sizing — no standby)                 │ │      │
│  │  │  Backup: 1 day  │  Performance Insights: enabled                             │ │      │
│  │  │  DB name: smartretail  │  Admin: smartretail_admin                           │ │      │
│  │  │  Schemas: public · sales · forecasting · inventory ·                         │ │      │
│  │  │           replenishment · supplier · promotions                              │ │      │
│  │  │  CW Logs: postgresql → /aws/rds/…  (1 month)                                 │ │      │
│  │  │  Secret: auto-generated (Secrets Manager)                                    │ │      │
│  │  │                                                                              │ │      │
│  │  │  Security Group: sgRds                                                       │ │      │
│  │  │    Ingress: TCP 5432  from sgRdsProxy only                                   │ │      │
│  │  │    Egress:  none                                                             │ │      │
│  │  └──────────────────────────────────────────────────────────────────────────────┘ │      │
│  └───────────────────────────────────────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Security Groups

| SG name                | Ingress                                      | Egress    | Placed in           |
|------------------------|----------------------------------------------|-----------|---------------------|
| `sgEcsTasks`           | TCP 8080–8086 from VPC CIDR                  | all       | PrivateApp          |
|                        | all TCP from `sgEcsTasks` (svc-to-svc)       |           |                     |
| `sgRdsProxy`           | TCP 5432 from `sgEcsTasks`                   | all       | Isolated            |
| `sgRds`                | TCP 5432 from `sgRdsProxy`                   | **none**  | Isolated            |
| `sgVpcEndpoints`       | TCP 443 from VPC CIDR (10.0.0.0/16)          | **none**  | PrivateApp          |
| `sgBatchProcessor`     | none                                         | all       | PrivateApp (Lambda) |
| `sgMlTrigger`          | none                                         | all       | PrivateApp (Lambda) |

---

## 4. SQS Queues

| Queue name                           | Type     | Visibility | DLQ (max receive) | Encryption   |
|--------------------------------------|----------|------------|-------------------|--------------|
| `smartretail-ims-sales-dev`          | Standard | 120 s      | …-dlq (3×)        | SQS-managed  |
| `smartretail-re-alert-dev.fifo`      | FIFO     | 120 s      | …-dlq.fifo (3×)   | SQS-managed  |
| `smartretail-ars-updates-dev`        | Standard | default    | …-dlq (3×)        | SQS-managed  |
| `smartretail-pps-inbound-dev`        | Standard | 120 s      | …-dlq (3×)        | SQS-managed  |

DLQ properties: IMS sales DLQ and ARS updates DLQ have 14-day retention. All DLQs are exposed as
public properties on `MessagingStack` so the MonitoringStack can attach CloudWatch alarms.

---

## 5. EventBridge

**Bus:** `smartretail-events-dev`

| Rule name                              | Source                               | Detail type             | Target                        | Notes                            |
|----------------------------------------|--------------------------------------|-------------------------|-------------------------------|----------------------------------|
| `smartretail-sales-to-ims-dev`         | `smartretail.sis`                    | `SalesTransactionEvent` | `ims-sales-dev`               | SIS → IMS pipeline               |
| `smartretail-alert-to-re-dev`          | `smartretail.ims`                    | `InventoryAlertEvent`   | `re-alert-dev.fifo`           | `messageGroupId = $.detail.dcId` |
| `smartretail-all-to-ars-dev`           | `smartretail.sis`, `.ims`, `.re`     | any                     | `ars-updates-dev`             | Dashboard aggregation            |
| `smartretail-promotion-to-pps-dev`     | `external.campaign-management`       | `PromotionActivated`    | `pps-inbound-dev`             | External → API GW system route   |

---

## 6. API Gateway Routes

**API name:** `smartretail-api-dev` · **Stage:** `internal` · **Type:** Regional REST

| Path pattern                       | Method | Backend | Port   | Integration               |
|------------------------------------|--------|---------|--------|---------------------------|
| `/v1/dashboard/{proxy+}`           | ANY    | ARS     | 8083   | HTTP_PROXY / VPC Link     |
| `/v1/inventory/{proxy+}`           | ANY    | IMS     | 8081   | HTTP_PROXY / VPC Link     |
| `/v1/forecast/{proxy+}`            | ANY    | DFS     | 8084   | HTTP_PROXY / VPC Link     |
| `/v1/replenishment/{proxy+}`       | ANY    | RE      | 8082   | HTTP_PROXY / VPC Link     |
| `/v1/supplier/{proxy+}`            | ANY    | SUP     | 8085   | HTTP_PROXY / VPC Link     |
| `/v1/ingest/{proxy+}`              | ANY    | SIS     | 8080   | HTTP_PROXY / VPC Link     |
| `/v1/promotions/{proxy+}`          | ANY    | PPS     | 8086   | HTTP_PROXY / VPC Link     |
| `POST /system/v1/events/promotions`| POST   | EventBridge | — | AWS direct integration (API key) |

Integration URI: `http://{nlb-dns}:{port}/{proxy}` — NLB routes by port to the correct target group.

---

## 7. IAM Roles

### EcsExecutionRole
Assumed by: `ecs-tasks.amazonaws.com`

| Permission                                              | Source                                       |
|---------------------------------------------------------|----------------------------------------------|
| ECR pull, CW Logs stream write                          | `AmazonECSTaskExecutionRolePolicy` (managed) |
| `secretsmanager:GetSecretValue` on Firehose access key  | `grantRead()` — SIS validates Firehose deliveries |
| `secretsmanager:GetSecretValue` on RDS secret           | `grantRead()` — Flyway task only (services use IAM auth) |

### Per-service Task Roles

| Role           | Allowed actions                                                                 | Resources                            |
|----------------|---------------------------------------------------------------------------------|--------------------------------------|
| `sisTaskRole`  | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `imsTaskRole`  | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`                     | `smartretail-ims-sales-dev`          |
|                | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `reTaskRole`   | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`, `ChangeMessageVisibility` | `re-alert-dev.fifo`           |
|                | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `arsTaskRole`  | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `dfsTaskRole`  | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `supTaskRole`  | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |
| `ppsTaskRole`  | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`                     | `smartretail-pps-inbound-dev`        |
|                | `events:PutEvents`                                                              | `smartretail-events-dev` bus         |
|                | `rds-db:connect`                                                                | `dbuser:*/smartretail_admin`         |

### Infrastructure Roles

| Role                       | Trust principal               | Key permissions                                                                         |
|----------------------------|-------------------------------|-----------------------------------------------------------------------------------------|
| `FirehoseRole`             | `firehose.amazonaws.com`      | S3 `PutObject` on `smartretail-events-dev-{acct}`                                      |
| `ApiGwEventBridgeRole`     | `apigateway.amazonaws.com`    | `events:PutEvents` on `smartretail-events-dev` bus                                     |
| `SageMakerExecutionRole`   | `sagemaker.amazonaws.com`     | `sagemaker:Create/Describe/StopTrainingJob`, `Create/Describe/StopTransformJob` on `smartretail-*`; CW Logs write; S3 R/W on SageMaker bucket |
| `BatchPostProcessorRole`   | `lambda.amazonaws.com`        | `AWSLambdaVPCAccessExecutionRole` + `AWSLambdaBasicExecutionRole`; S3 `GetObject` on sagemaker bucket (`sagemaker/output/*`) |
| `MlTriggerRole`            | `lambda.amazonaws.com`        | `AWSLambdaVPCAccessExecutionRole` + `AWSLambdaBasicExecutionRole`; `sagemaker:StartPipelineExecution` on pipeline; S3 read (events), S3 write (sagemaker) |

---

## 8. Data Flows

### Flow 1 — POS Event Ingestion

```
POS terminal / SDK
  → Kinesis Firehose (smartretail-ingest-dev)
      buffer: 1 MiB / 60 s
    → HTTP POST to API Gateway /v1/ingest/events
        X-Access-Key header validated by SIS FirehoseBatchFilter
      → VPC Link → NLB :8080 → SIS :8080
          → INSERT INTO sales.pos_events (idempotency_key checked)
          → publishes SalesTransactionEvent to EventBridge
    → S3 backup (AllData, GZIP) → smartretail-events-dev-{acct}/firehose/…

EventBridge rule: smartretail-sales-to-ims-dev
  → SQS: smartretail-ims-sales-dev
    → IMS polls queue
      → UPDATE inventory.stock_levels
      → if stock < reorder_point:
          publishes InventoryAlertEvent to EventBridge

EventBridge rule: smartretail-alert-to-re-dev
  → SQS: smartretail-re-alert-dev.fifo (grouped by dcId)
    → RE polls queue
      → INSERT INTO replenishment.purchase_orders (status=PENDING_APPROVAL)
      → publishes ReplenishmentOrderCreated to EventBridge

EventBridge rule: smartretail-all-to-ars-dev
  → SQS: smartretail-ars-updates-dev
    → ARS polls queue, updates dashboard aggregates
```

### Flow 2 — RE Auto-approve

```
RE service polls re-alert-dev.fifo
  → evaluates auto-approve rules
  → if approved:
      UPDATE replenishment.purchase_orders
        SET status='APPROVED', version=v+1
        WHERE id=:id AND status='PENDING_APPROVAL' AND version=:v
      → publishes PurchaseOrderApprovedEvent to EventBridge
        → ars-updates-dev → ARS aggregates
```

### Flow 3 — SC Planner Manual Approve / Reject

```
SC Planner MFE (CloudFront → S3)
  → API Gateway /v1/replenishment/v1/purchase-orders/{id}/approve  (POST + JWT)
    → VPC Link → NLB :8082 → RE :8082
      → optimistic-lock UPDATE (version check required)
      → publishes PurchaseOrderApprovedEvent / RejectedEvent to EventBridge
        → ars-updates-dev → ARS aggregates
```

### Flow 4 — Dashboard reads (ARS)

```
Any MFE → API Gateway /v1/dashboard/* → ARS :8083
  ARS reads each schema via RDS Proxy independently (no cross-schema JOINs):
    inventory schema    → stock levels, alerts
    replenishment schema → PO pipeline, lead times
    forecasting schema   → MAPE, P10/P50/P90 forecasts
    supplier schema      → OTD, supplier scorecards
  → merged in Java service layer, returned as single JSON response
```

### Flow 5 — SageMaker Demand Forecasting (nightly)

```
EventBridge schedule: cron(0 2 * * ? *)   [daily 02:00 UTC]
  → Lambda: smartretail-ml-trigger-dev (300 s timeout)
      reads raw POS events from S3 (events bucket)
      → writes training manifest to smartretail-sagemaker-dev-{acct}
      → calls sagemaker:StartPipelineExecution
          pipeline: smartretail-demand-forecast-dev
      SageMaker writes model output CSV to SageMaker bucket (sagemaker/output/*.csv)

S3 ObjectCreated (prefix: sagemaker/output/, suffix: .csv)
  → Lambda: smartretail-batch-post-processor-dev (180 s timeout)
      reads transform output
      → POST to http://smartretail-dfs-dev.smartretail.local:8084
          (CloudMap DNS — DFS internal endpoint)
      DFS ingests forecasts into forecasting.demand_forecasts table
```

### Flow 6 — Promotion Activation (external → PPS)

```
Campaign Management System
  → POST /system/v1/events/promotions  (API key required)
    → API Gateway AWS integration → EventBridge PutEvents
        source: external.campaign-management  │  detailType: PromotionActivated
      → SQS: smartretail-pps-inbound-dev
        → PPS :8086 polls queue
          → INSERT INTO promotions.promotion_events
          → applies pricing rules, publishes to EventBridge
```

### Flyway Migration (run once per deploy)

```
Operator:  make aws-push-flyway ENV=dev
  → docker buildx build --platform linux/amd64 backend/migrations/
  → docker push {ecr}/smartretail-flyway-dev:latest

Operator:  make aws-migrate ENV=dev
  → reads SSM /smartretail/dev/network/ecs-subnet-ids (PrivateApp subnets)
  →          /smartretail/dev/network/sg-ecs-tasks-id
  →          /smartretail/dev/network/assign-public-ip = DISABLED
  → aws ecs run-task --launch-type FARGATE
      --task-definition smartretail-flyway-dev
      --network-configuration {PrivateApp subnets, sgEcsTasks, assignPublicIp=DISABLED}
  → ECS task: Flyway → RDS Proxy :5432 → RDS (password from Secrets Manager)
  → applies pending migrations, exits 0
  → aws ecs wait tasks-stopped → reports result
```

---

## 9. S3 Buckets

| Bucket name                            | Purpose                           | Versioned | Lifecycle   | Removal  |
|----------------------------------------|-----------------------------------|-----------|-------------|----------|
| `smartretail-events-dev-{acct}`        | Firehose S3 backup (AllData)      | No        | Expire 7yr  | DESTROY  |
| `smartretail-sagemaker-dev-{acct}`     | SageMaker training + output       | No        | Expire 1yr  | DESTROY  |
| `smartretail-mfe-dev-store-manager-{acct}` | Store Manager MFE assets     | —         | —           | DESTROY  |
| `smartretail-mfe-dev-sc-planner-{acct}`    | SC Planner MFE assets        | —         | —           | DESTROY  |
| `smartretail-mfe-dev-executive-{acct}`     | Executive Dashboard MFE      | —         | —           | DESTROY  |
| `smartretail-mfe-dev-supplier-{acct}`      | Supplier Portal MFE          | —         | —           | DESTROY  |

---

## 10. Monitoring (Dev-Monitoring stack — dev-only)

The MonitoringStack is only deployed in dev. Prod has no automated CloudWatch alarms.

### SNS Alert Topic

`smartretail-alerts-dev` — optional email subscription via CDK context key `alertEmail`.

### CloudWatch Log Metric Filters

| Filter                 | Log group                        | Metric                      | Namespace         |
|------------------------|----------------------------------|-----------------------------|-------------------|
| ERROR per service (×7) | `/smartretail/{svc}/dev`         | `{SVC}_ErrorCount`          | `SmartRetail/App` |
| POS events ingested    | `/smartretail/sis/dev`           | `POSEventsIngested`         | `SmartRetail/App` |
| Inventory alerts raised| `/smartretail/ims/dev`           | `InventoryAlertsRaised`     | `SmartRetail/App` |
| POs created            | `/smartretail/re/dev`            | `PurchaseOrdersCreated`     | `SmartRetail/App` |

### CloudWatch Alarms

| Alarm name                    | Metric                                         | Threshold          | Periods |
|-------------------------------|------------------------------------------------|--------------------|---------|
| `SR-DLQ-ImsSales-dev`         | `ApproximateNumberOfMessagesVisible` (ims DLQ) | > 0                | 1       |
| `SR-DLQ-ReAlert-dev`          | `ApproximateNumberOfMessagesVisible` (re DLQ)  | > 0                | 1       |
| `SR-DLQ-ArsUpdates-dev`       | `ApproximateNumberOfMessagesVisible` (ars DLQ) | > 0                | 1       |
| `SR-API-5xxErrors-dev`        | API Gateway `5XXError` (Sum, 5 min)            | > 10               | 1       |
| `SR-RDS-CPUHigh-dev`          | RDS `CPUUtilization` (Average, 10 min)         | > 80%              | 2       |
| `SR-Firehose-DeliveryFailed-dev` | Firehose `DataFreshness` (Maximum, 5 min)   | > 600 s            | 2       |

All alarms notify `smartretail-alerts-dev` SNS topic on both ALARM and OK state.

### CloudWatch Dashboard — `SmartRetail-dev-Ops`

| Row | Widgets |
|-----|---------|
| 1   | API request count, API 5xx errors, API latency p99, Firehose DataFreshness |
| 2   | Business pipeline KPIs (POS events / alerts / POs), Application errors by service (stacked) |
| 3   | ECS CPU % for SIS · IMS · RE · ARS |
| 4   | RDS CPU, RDS connections, SQS DLQ depths (IMS / RE / ARS) |
| 5   | Alarm status summary (all 6 alarms) |

---

## 11. Observability

| Signal             | Detail                                                                     |
|--------------------|----------------------------------------------------------------------------|
| Container logs     | CloudWatch Logs `/smartretail/{svc}/dev` · retention 1 month              |
| Flyway logs        | CloudWatch Logs `/smartretail/flyway/dev` · retention 1 month · DESTROY   |
| RDS logs           | `postgresql` log type exported to CW · retention 1 month                  |
| Metrics endpoint   | `GET /actuator/prometheus` (Micrometer) on every service                  |
| Metric tags        | `service`, `flow`, `env` on all custom metrics                            |
| Custom metrics     | `replenishment.orders.created`, `pos.events.received`, `stock.alerts.published` |
| Circuit breaker    | ECS deployment circuit breaker with rollback                               |
| Health checks      | NLB HTTP `/actuator/health` every 30 s (2 healthy / 3 unhealthy)          |
| Correlation IDs    | `X-Correlation-ID` propagated; generated if absent; in every log line     |
| Log format         | Structured JSON — `timestamp`, `level`, `service`, `correlationId`, `traceId` |
| Error format       | RFC 7807 `ProblemDetail` on all 4xx/5xx                                   |

---

## 12. Key Resource Names

| Resource                  | Name / Pattern                                                      |
|---------------------------|---------------------------------------------------------------------|
| ECS cluster               | `smartretail-dev`                                                   |
| RDS instance              | `smartretail-rds-dev`                                               |
| RDS Proxy                 | `smartretail-rds-proxy-dev`                                         |
| RDS secret                | Auto-generated (ARN in SSM `/smartretail/dev/rds/secret-arn`)       |
| Firehose access key       | SSM `/smartretail/dev/firehose/access-key-secret-arn`               |
| NLB                       | `smartretail-nlb-dev`                                               |
| VPC Link                  | `smartretail-vpclink-dev`                                           |
| API Gateway               | `smartretail-api-dev` (stage `internal`)                            |
| Firehose stream           | `smartretail-ingest-dev`                                            |
| EventBridge bus           | `smartretail-events-dev`                                            |
| SageMaker pipeline        | `smartretail-demand-forecast-dev`                                   |
| ECR repos                 | `smartretail-{sis,ims,re,ars,dfs,sup,pps,batch-post-processor,ml-trigger,flyway}-dev` |
| System API key            | `smartretail-system-events-dev`                                     |
| Cognito internal pool     | `smartretail-internal-dev` (domain `smartretail-dev-internal`)      |
| Cognito supplier pool     | `smartretail-supplier-dev` (domain `smartretail-dev-supplier`)      |
| CloudFront distribution   | Single dist; SSM `/smartretail/dev/hosting/cloudfront-url`          |
| CloudMap namespace        | `smartretail.local`                                                 |
| SNS alert topic           | `smartretail-alerts-dev`                                            |
| CloudWatch dashboard      | `SmartRetail-dev-Ops`                                               |
| Flyway task family        | `smartretail-flyway-dev`                                            |
| SSM prefix                | `/smartretail/dev/`                                                 |

---

## 13. CDK Stack Dependency Order

```
Dev-Network
  └── Dev-Data         (needs VPC + SGs for RDS/Proxy placement + S3 buckets)
        └── Dev-Messaging  (SQS + EventBridge — no VPC dependency)
              └── Dev-Hosting    (CloudFront + 4 MFE S3 buckets — no VPC dependency)
                    └── Dev-Identity   (Cognito — needs distributionUrl for OAuth callback)
                          └── Dev-Compute  (needs VPC, Data, Messaging)
                                └── Dev-Api  (needs VPC, Data, Messaging, Compute;
                                              creates NLB, VPC Link, API GW, Firehose)
                                      └── Dev-Monitoring  (needs Compute, Messaging, Data, Api;
                                                           dev-only stack)
```

---

## 14. Key Differences vs Production

| Dimension                | Dev                              | Prod                               |
|--------------------------|----------------------------------|------------------------------------|
| AZs                      | 2                                | 3                                  |
| NAT Gateways             | 1 (shared)                       | 3 (one per AZ)                     |
| RDS instance class       | t4g.small                        | r6g.large (ARM, memory-optimised)  |
| RDS multi-AZ             | No                               | Yes                                |
| RDS backup retention     | 1 day                            | 7 days                             |
| Performance Insights     | Disabled                         | Enabled                            |
| ECS task size            | 256 CPU / 512 MiB                | 512 CPU / 1024 MiB                 |
| ECS desired / max        | 1 / 3                            | 2 / 10                             |
| SPOT ratio               | SPOT×4 + FARGATE×1               | SPOT×2 + FARGATE×1                 |
| Deregistration delay     | 30 s                             | 60 s                               |
| BatchPostProcessor timeout | 180 s                          | 300 s                              |
| SageMaker S3 lifecycle   | 1 year                           | 3 years                            |
| Log retention            | 1 month                          | 3 months                           |
| Removal policy           | DESTROY everywhere               | RETAIN (RDS, ECR, S3, secrets)     |
| MonitoringStack          | Yes (SNS + alarms + dashboard)   | No (manual CloudWatch setup)       |
| CORS origin              | `https://*.smartretail.com`      | `https://*.smartretail.com`        |

---

## Production Environment (Prod-* CDK stacks)

> **Full-stack production deployment.** 7 backend services, live Firehose POS ingestion,
> SageMaker demand forecasting, 3-AZ HA, Multi-AZ RDS, RDS Proxy, 3 MFEs.
> CDK stack prefix: `Prod-*`. Manual deployments only — not wired into the Makefile.

---

## 1. Environment Summary

| Property              | Value                                                                 |
|-----------------------|-----------------------------------------------------------------------|
| Environment name      | `prod`                                                                |
| Spring profile        | `aws`                                                                 |
| CDK stacks            | `Prod-Network` · `Prod-Data` · `Prod-Messaging` · `Prod-Hosting` · `Prod-Identity` · `Prod-Compute` · `Prod-Api` |
| CPU architecture      | x86_64                                                                |
| VPC type              | Custom CDK VPC (10.0.0.0/16), 3 AZs, 3 subnet tiers                 |
| Subnet tiers          | Public · PrivateApp · Isolated                                       |
| NAT Gateways          | 3 (one per AZ in public subnets)                                     |
| RDS proxy             | Yes — all services connect via RDS Proxy in isolated subnets         |
| ECS task min / max    | 2 / 10 (CPU scaling at 70%)                                         |
| ECS task size         | 512 CPU units · 1024 MiB                                            |
| Capacity strategy     | FARGATE_SPOT (weight 2) + FARGATE (weight 1)                        |
| Log retention         | 3 months                                                             |
| Removal policy        | RETAIN (RDS, ECR, S3, secrets)                                       |
| CORS origin           | `https://*.smartretail.com`                                          |

---

## 2. Network Topology

### 2.1 VPC Layout (3 AZs × 3 tiers = 9 subnets)

```
VPC: 10.0.0.0/16
│
├── Public subnets (CDK-assigned /24 blocks — one per AZ)
│     AZ-a: ~10.0.0.0/24    AZ-b: ~10.0.1.0/24    AZ-c: ~10.0.2.0/24
│     Contents:
│       • NAT Gateway × 3 (one per AZ, each with an Elastic IP)
│       • (NLB is placed in PrivateApp — see §2.3)
│
├── PrivateApp subnets (one per AZ, egress via NAT)
│     AZ-a: ~10.0.3.0/24    AZ-b: ~10.0.4.0/24    AZ-c: ~10.0.5.0/24
│     Contents:
│       • ECS Fargate tasks (all 7 services + Flyway run-task)
│       • NLB (internal, not internet-facing)
│       • Lambda functions (Batch Post-Processor, ML Trigger)
│       • VPC Interface Endpoints (ECR, SQS, EventBridge, CW Logs, Secrets Manager)
│
└── Isolated subnets (no route to internet, no NAT)
      AZ-a: ~10.0.6.0/24    AZ-b: ~10.0.7.0/24    AZ-c: ~10.0.8.0/24
      Contents:
        • RDS PostgreSQL (primary in one AZ, standby in another — Multi-AZ)
        • RDS Proxy (spans all isolated subnets)

Note: CDK assigns subnet CIDRs automatically. The /24 ranges above are
representative defaults; check cdk.context.json after first synth for actuals.
```

### 2.2 VPC Endpoints

| Endpoint type | Service              | Subnets     | Notes                                |
|---------------|----------------------|-------------|--------------------------------------|
| Gateway       | S3                   | All         | Free; used by ECR image pulls + S3   |
| Interface     | ECR (`ecr.api`)      | PrivateApp  | ECS image pull without NAT           |
| Interface     | ECR Docker (`ecr.dkr`) | PrivateApp | Image layer pull                     |
| Interface     | SQS                  | PrivateApp  | ECS → SQS without NAT               |
| Interface     | EventBridge          | PrivateApp  | ECS → EventBridge without NAT       |
| Interface     | CloudWatch Logs      | PrivateApp  | Container log delivery               |
| Interface     | Secrets Manager      | PrivateApp  | Secret injection at task launch      |

All interface endpoints share **sgVpcEndpoints**: ingress TCP 443 from VPC CIDR, egress none.

### 2.3 Full Topology Diagram

```
                                    INTERNET
                                       │
            ┌──────────────────────────┤────────────────────────────────────────────────┐
            │                          │                                                │
   ┌────────▼─────────────────────┐    │ ┌─────────▼──────────────────────────────────┐ │
   │  Amazon Cognito              │    │ │  Amazon CloudFront (HostingStack)           │ │
   │  (IdentityStack)             │    │ │  HTTPS · *.smartretail.com · PriceClass 100 │ │
   │                              │    │ │  Single distribution with 4 path behaviors  │ │
   │  Internal Pool               │    │ │  (each behavior: OAC SigV4 + SPA rewrite fn)│ │
   │  smartretail-internal-prod   │    │ │    /store-manager/* → store-manager S3      │ │
   │  Groups:                     │    │ │    /sc-planner/*    → sc-planner S3         │ │
   │    • STORE_MANAGER           │    │ │    /executive/*     → executive S3          │ │
   │    • SC_PLANNER              │    │ │    /supplier/*      → supplier S3           │ │
   │    • EXECUTIVE · ADMIN       │    │ │    /* (default)     → 302 /sc-planner/      │ │
   │  Domain:                     │    │ └─────────────────────────┬───────────────────┘ │
   │    smartretail-prod-internal │    │           ┌───────────────┼──────────────┐      │
   │                              │    │  ┌────────▼──┐  ┌─────────▼┐  ┌─────────▼┐  ┌──▼───────┐ │
   │  Supplier Pool               │    │  │    S3     │  │    S3    │  │    S3    │  │    S3    │ │
   │  smartretail-supplier-prod   │    │  │  store-   │  │   sc-    │  │executive │  │ supplier │ │
   │  Group: SUPPLIER_ADMIN       │    │  │  manager  │  │ planner  │  │ -prod-   │  │  -prod-  │ │
   │  Domain:                     │    │  │  -prod-   │  │  -prod-  │  │  {acct}  │  │  {acct}  │ │
   │    smartretail-prod-supplier │    │  │  {acct}   │  │  {acct}  │  │          │  │          │ │
   │  OAuth: /supplier/callback   │    │  └───────────┘  └──────────┘  └──────────┘  └──────────┘ │
   └────────┬─────────────────────┘    │                                                           │
            │ JWT Bearer token          │                                                           │
   ┌────────▼──────────────────────────────────────────────────────────────────┐ │
   │                  Amazon API Gateway (Regional REST API)                    │ │
   │              smartretail-api-prod  │  stage: internal                     │ │
   │                                                                           │ │
   │  Staff routes (VPC Link → NLB HTTP_PROXY):                                │ │
   │    /v1/dashboard/{proxy+}       → ARS  :8083                              │ │
   │    /v1/inventory/{proxy+}       → IMS  :8081                              │ │
   │    /v1/forecast/{proxy+}        → DFS  :8084                              │ │
   │    /v1/replenishment/{proxy+}   → RE   :8082                              │ │
   │    /v1/supplier/{proxy+}        → SUP  :8085                              │ │
   │    /v1/ingest/{proxy+}          → SIS  :8080  (Firehose delivery target)  │ │
   │    /v1/promotions/{proxy+}      → PPS  :8086                              │ │
   │                                                                           │ │
   │  System route (direct EventBridge AWS integration, API key required):     │ │
   │    POST /system/v1/events/promotions → EventBridge PutEvents              │ │
   │    Source: external.campaign-management │ DetailType: PromotionActivated  │ │
   │    Rate limit: 50 rps burst 100 │ Quota: 10,000 req/day                  │ │
   │                                                                           │ │
   │  CORS: https://*.smartretail.com  │  4xx/5xx CORS-safe gateway responses  │ │
   └──────────────┬──────────────────────────────────────────────────────────┬─┘ │
                  │ VPC Link                                                  │   │
                  │ smartretail-vpclink-prod                                  │   │
   ┌──────────────┴──── Kinesis Data Firehose ──────────────────────────────┐ │   │
   │  Stream: smartretail-ingest-prod   Type: DirectPut                      │ │   │
   │  HTTP endpoint: {api-url}/v1/ingest/events                              │ │   │
   │  Auth: X-Access-Key (from Secrets Manager secret)                       │ │   │
   │  Buffering: 1 MiB / 60 s  │  Retry: 86400 s                            │ │   │
   │  S3 backup: AllData → smartretail-events-prod-{acct}/firehose/…        │ │   │
   │             Compression: GZIP  │  Buffering: 5 MiB / 60 s              │ │   │
   │  Role: FirehoseRole → S3 write on events bucket                         │ │   │
   └─────────────────────────────────────────────────────────────────────────┘ │   │
                  │                                                             │   │
┌─────────────────▼─────────────────────────────────────────────────────────────▼───▼──┐
│  VPC: 10.0.0.0/16                                                                     │
│                                                                                       │
│  ┌────────────────── PUBLIC SUBNETS (3 AZs) ─────────────────────────────────────┐   │
│  │  NAT Gateway (AZ-a) ──── NAT Gateway (AZ-b) ──── NAT Gateway (AZ-c)          │   │
│  │  (each with Elastic IP; PrivateApp subnets route 0.0.0.0/0 through own AZ NAT)│   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                       │
│  ┌──────────────── PRIVATEAPP SUBNETS (3 AZs, egress via NAT) ───────────────────┐   │
│  │                                                                                │   │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │  NLB: smartretail-nlb-prod  (internal, PrivateApp subnets)               │ │   │
│  │  │  Listeners → Target Groups (health: HTTP /actuator/health, 30 s):        │ │   │
│  │  │    :8080 → sisContainer    :8081 → imsContainer    :8082 → reContainer   │ │   │
│  │  │    :8083 → arsContainer    :8084 → dfsContainer    :8085 → supContainer  │ │   │
│  │  │    :8086 → ppsContainer    (deregistration delay: 60 s)                  │ │   │
│  │  └──────────────────────────────┬───────────────────────────────────────────┘ │   │
│  │                                 │                                             │   │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────┐ │   │
│  │  │  ECS Cluster: smartretail-prod                                            │ │   │
│  │  │  Launch type: Fargate  │  Arch: x86_64  │  Container Insights V2         │ │   │
│  │  │  Capacity: FARGATE_SPOT (weight 2) + FARGATE (weight 1)                  │ │   │
│  │  │  CloudMap namespace: smartretail.local                                    │ │   │
│  │  │                                                                           │ │   │
│  │  │  Security Group: sgEcsTasks                                               │ │   │
│  │  │    Ingress: TCP 8080–8086  from VPC CIDR (10.0.0.0/16)                   │ │   │
│  │  │    Ingress: all TCP        from sgEcsTasks (svc-to-svc)                   │ │   │
│  │  │    Egress:  all (0.0.0.0/0 — routed via NAT or VPC endpoints)            │ │   │
│  │  │                                                                           │ │   │
│  │  │  ┌─────────────────────────────────────────────────────────────────────┐ │ │   │
│  │  │  │  Persistent Services                                                │ │ │   │
│  │  │  │  desired=2 · max=10 · scale on CPU>70% · circuit breaker+rollback   │ │ │   │
│  │  │  │  512 CPU · 1024 MiB · assignPublicIp=false · profile=aws           │ │ │   │
│  │  │  │                                                                     │ │ │   │
│  │  │  │  SIS  :8080   sales schema        (+ Firehose access key secret)   │ │ │   │
│  │  │  │  IMS  :8081   inventory schema                                      │ │ │   │
│  │  │  │  RE   :8082   replenishment schema                                  │ │ │   │
│  │  │  │  ARS  :8083   multi-schema reads (no cross-schema JOINs)            │ │ │   │
│  │  │  │  DFS  :8084   forecasting schema                                    │ │ │   │
│  │  │  │  SUP  :8085   supplier schema                                       │ │ │   │
│  │  │  │  PPS  :8086   promotions schema                                     │ │ │   │
│  │  │  │                                                                     │ │ │   │
│  │  │  │  Env vars (all services):                                           │ │ │   │
│  │  │  │    SMARTRETAIL_ENV=prod  AWS_REGION=us-east-1                       │ │ │   │
│  │  │  │    RDS_PROXY_ENDPOINT=<proxy-hostname>                              │ │ │   │
│  │  │  │    (no DB_PASSWORD — services use rds-db:connect IAM auth)          │ │ │   │
│  │  │  └─────────────────────────────────────────────────────────────────────┘ │ │   │
│  │  │                                                                           │ │   │
│  │  │  ┌─────────────────────────────────────────────────────────────────────┐ │ │   │
│  │  │  │  Flyway Migration Task (run-task only — not a service)              │ │ │   │
│  │  │  │  Family: smartretail-flyway-prod                                    │ │ │   │
│  │  │  │  256 CPU · 512 MiB · x86_64 · assignPublicIp=false                 │ │ │   │
│  │  │  │  FLYWAY_URL → RDS Proxy :5432                                       │ │ │   │
│  │  │  │  FLYWAY_PASSWORD injected from Secrets Manager (execution role)     │ │ │   │
│  │  │  │  Logs: /smartretail/flyway/prod (3 months, RETAIN)                 │ │ │   │
│  │  │  └─────────────────────────────────────────────────────────────────────┘ │ │   │
│  │  └──────────────────────────────┬───────────────────────────────────────────┘ │   │
│  │                                 │                                             │   │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────┐ │   │
│  │  │  Lambda: smartretail-batch-post-processor-prod                            │ │   │
│  │  │  Trigger: S3 ObjectCreated on smartretail-events-prod-{acct}             │ │   │
│  │  │  Timeout: 300 s  │  Memory: 512 MiB  │  x86_64                          │ │   │
│  │  │  VPC: PrivateApp subnets  │  SG: sgBatchPostProcessor (egress all)      │ │   │
│  │  │  Calls: http://smartretail-dfs-prod.smartretail.local:8084 (CloudMap)   │ │   │
│  │  │  Role: S3 read (events bucket)                                           │ │   │
│  │  └──────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                                                                │   │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │  Lambda: smartretail-ml-trigger-prod                                     │ │   │
│  │  │  Trigger: EventBridge schedule  cron(0 2 * * ? *)  daily 02:00 UTC      │ │   │
│  │  │  Timeout: 300 s  │  Memory: 512 MiB  │  x86_64                          │ │   │
│  │  │  VPC: PrivateApp subnets  │  SG: sgMlTrigger (egress all)               │ │   │
│  │  │  Calls: sagemaker:StartPipelineExecution                                 │ │   │
│  │  │  Role: S3 read (events bucket), S3 write (sagemaker bucket),            │ │   │
│  │  │        sagemaker:StartPipelineExecution on smartretail-demand-forecast-prod │ │   │
│  │  └──────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                                                                │   │
│  │  VPC Interface Endpoints (sgVpcEndpoints: ingress 443 from VPC CIDR):        │   │
│  │    ecr.api · ecr.dkr · sqs · events · logs · secretsmanager                 │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                       │
│  ┌───────────────── ISOLATED SUBNETS (3 AZs, no internet route) ─────────────────┐   │
│  │                                                                                │   │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │  RDS Proxy: smartretail-rds-proxy-prod                                   │ │   │
│  │  │  Subnets: isolated  │  TLS: not required  │  IAM auth: disabled          │ │   │
│  │  │  Secrets: RDS credentials (Secrets Manager)                              │ │   │
│  │  │                                                                           │ │   │
│  │  │  Security Group: sgRdsProxy                                               │ │   │
│  │  │    Ingress: TCP 5432  from sgEcsTasks                                     │ │   │
│  │  │    Egress:  all                                                           │ │   │
│  │  └──────────────────────────────┬───────────────────────────────────────────┘ │   │
│  │                                 │ TCP :5432                                   │   │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────┐ │   │
│  │  │  RDS: smartretail-rds-prod                                                │ │   │
│  │  │  Engine: PostgreSQL 16.13  │  Instance: r6g.large (ARM, memory-optimised) │ │   │
│  │  │  Storage: 100 GiB GP2  │  Multi-AZ (primary + standby)                   │ │   │
│  │  │  Backup: 7 days  │  Performance Insights: enabled                        │ │   │
│  │  │  Deletion protection: on  │  Removal policy: RETAIN                      │ │   │
│  │  │  DB name: smartretail  │  Admin: smartretail_admin                        │ │   │
│  │  │  Schemas: public · sales · forecasting · inventory ·                     │ │   │
│  │  │           replenishment · supplier · promotions                           │ │   │
│  │  │  Secret: auto-generated (Secrets Manager, no custom name)                │ │   │
│  │  │                                                                           │ │   │
│  │  │  Security Group: sgRds                                                    │ │   │
│  │  │    Ingress: TCP 5432  from sgRdsProxy only                                │ │   │
│  │  │    Egress:  none                                                          │ │   │
│  │  └──────────────────────────────────────────────────────────────────────────┘ │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Security Groups

| SG name              | Ingress                                      | Egress    | Placed in        |
|----------------------|----------------------------------------------|-----------|------------------|
| `sgEcsTasks`         | TCP 8080–8086 from VPC CIDR                  | all       | PrivateApp       |
|                      | all TCP from `sgEcsTasks` (svc-to-svc)       |           |                  |
| `sgRdsProxy`         | TCP 5432 from `sgEcsTasks`                   | all       | Isolated         |
| `sgRds`              | TCP 5432 from `sgRdsProxy`                   | **none**  | Isolated         |
| `sgVpcEndpoints`     | TCP 443 from VPC CIDR (10.0.0.0/16)         | **none**  | PrivateApp       |
| `sgBatchPostProcessor` | none                                       | all       | PrivateApp (Lambda) |
| `sgMlTrigger`        | none                                         | all       | PrivateApp (Lambda) |

---

## 4. SQS Queues

| Queue name                            | Type     | Visibility | DLQ (max receive) | Encryption   |
|---------------------------------------|----------|------------|-------------------|--------------|
| `smartretail-ims-sales-prod`          | Standard | 120 s      | …-dlq (3×)        | SQS-managed  |
| `smartretail-re-alert-prod.fifo`      | FIFO     | 120 s      | …-dlq.fifo (3×)   | SQS-managed  |
| `smartretail-ars-updates-prod`        | Standard | default    | …-dlq (3×)        | SQS-managed  |
| `smartretail-pps-inbound-prod`        | Standard | 120 s      | …-dlq (3×)        | SQS-managed  |

---

## 5. EventBridge

**Bus:** `smartretail-events-prod`

| Rule name                               | Source                                | Detail type             | Target                          | Notes                              |
|-----------------------------------------|---------------------------------------|-------------------------|---------------------------------|------------------------------------|
| `smartretail-sales-to-ims-prod`         | `smartretail.sis`                     | `SalesTransactionEvent` | `ims-sales-prod`                | SIS → IMS pipeline                 |
| `smartretail-alert-to-re-prod`          | `smartretail.ims`                     | `InventoryAlertEvent`   | `re-alert-prod.fifo`            | `messageGroupId = $.detail.dcId`   |
| `smartretail-all-to-ars-prod`           | `smartretail.sis`, `.ims`, `.re`      | any                     | `ars-updates-prod`              | Dashboard aggregation              |
| `smartretail-promotion-to-pps-prod`     | `external.campaign-management`        | `PromotionActivated`    | `pps-inbound-prod`              | External → API GW system route     |

---

## 6. API Gateway Routes

**API name:** `smartretail-api-prod` · **Stage:** `internal` · **Type:** Regional REST

| Path pattern                     | Method | Backend | Port   | Integration               |
|----------------------------------|--------|---------|--------|---------------------------|
| `/v1/dashboard/{proxy+}`         | ANY    | ARS     | 8083   | HTTP_PROXY / VPC Link     |
| `/v1/inventory/{proxy+}`         | ANY    | IMS     | 8081   | HTTP_PROXY / VPC Link     |
| `/v1/forecast/{proxy+}`          | ANY    | DFS     | 8084   | HTTP_PROXY / VPC Link     |
| `/v1/replenishment/{proxy+}`     | ANY    | RE      | 8082   | HTTP_PROXY / VPC Link     |
| `/v1/supplier/{proxy+}`          | ANY    | SUP     | 8085   | HTTP_PROXY / VPC Link     |
| `/v1/ingest/{proxy+}`            | ANY    | SIS     | 8080   | HTTP_PROXY / VPC Link     |
| `/v1/promotions/{proxy+}`        | ANY    | PPS     | 8086   | HTTP_PROXY / VPC Link     |
| `POST /system/v1/events/promotions` | POST | EventBridge | — | AWS direct integration (API key) |

Integration URI pattern for staff routes: `http://{nlb-dns}:{port}/{proxy}` — NLB routes to
the correct target group by port; the full path is passed through via `{proxy}`.

---

## 7. IAM Roles

### EcsExecutionRole
Assumed by: `ecs-tasks.amazonaws.com`

| Permission                                            | Source                                       |
|-------------------------------------------------------|----------------------------------------------|
| ECR pull, CW Logs stream write                        | `AmazonECSTaskExecutionRolePolicy` (managed) |
| `secretsmanager:GetSecretValue` on Firehose access key | `grantRead()` — SIS validates Firehose delivery |
| `secretsmanager:GetSecretValue` on RDS secret         | `grantRead()` — Flyway task only (services use IAM auth) |

### Per-service Task Roles

| Role           | Allowed actions                                             | Resources                              |
|----------------|-------------------------------------------------------------|----------------------------------------|
| `sisTaskRole`  | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `imsTaskRole`  | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes` | `smartretail-ims-sales-prod`           |
|                | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `reTaskRole`   | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`, `ChangeMessageVisibility` | `re-alert-prod.fifo` |
|                | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `arsTaskRole`  | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `dfsTaskRole`  | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `supTaskRole`  | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |
| `ppsTaskRole`  | `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes` | `smartretail-pps-inbound-prod`         |
|                | `events:PutEvents`                                          | `smartretail-events-prod` bus          |
|                | `rds-db:connect`                                            | `dbuser:*/smartretail_admin`           |

### Infrastructure Roles

| Role                       | Trust principal               | Key permissions                                                        |
|----------------------------|-------------------------------|------------------------------------------------------------------------|
| `FirehoseRole`             | `firehose.amazonaws.com`      | S3 `PutObject` on `smartretail-events-prod-{acct}`                    |
| `ApiGwEventBridgeRole`     | `apigateway.amazonaws.com`    | `events:PutEvents` on `smartretail-events-prod` bus                   |
| `SageMakerExecutionRole`   | `sagemaker.amazonaws.com`     | `sagemaker:Create/Describe/StopTrainingJob`, `Create/Describe/StopTransformJob` on `smartretail-*` resources; CW Logs write; S3 R/W on SageMaker bucket |
| `BatchPostProcessorRole`   | `lambda.amazonaws.com`        | `AWSLambdaVPCAccessExecutionRole` + `AWSLambdaBasicExecutionRole`; S3 `GetObject` on events bucket |
| `MlTriggerRole`            | `lambda.amazonaws.com`        | `AWSLambdaVPCAccessExecutionRole` + `AWSLambdaBasicExecutionRole`; `sagemaker:StartPipelineExecution` on `smartretail-demand-forecast-prod`; S3 read (events), S3 write (SageMaker) |

---

## 8. Data Flows

### Flow 1 — POS Event Ingestion

```
POS terminal / SDK
  → Kinesis Firehose (smartretail-ingest-prod)
      buffer: 1 MiB / 60 s
    → HTTP POST to API Gateway /v1/ingest/events
        Access-Key header validated by SIS FirehoseBatchFilter
      → VPC Link → NLB :8080 → SIS :8080
          → INSERT INTO sales.pos_events (idempotency_key checked)
          → publishes SalesTransactionEvent to EventBridge
    → S3 backup (AllData, GZIP) → smartretail-events-prod-{acct}/firehose/…

EventBridge rule: smartretail-sales-to-ims-prod
  → SQS: smartretail-ims-sales-prod
    → IMS polls queue
      → UPDATE inventory.stock_levels (atomic)
      → if stock < reorder_point:
          publishes InventoryAlertEvent to EventBridge

EventBridge rule: smartretail-alert-to-re-prod
  → SQS: smartretail-re-alert-prod.fifo (grouped by dcId)
    → RE polls queue
      → INSERT INTO replenishment.purchase_orders (status=PENDING_APPROVAL)
      → publishes ReplenishmentOrderCreated to EventBridge

EventBridge rule: smartretail-all-to-ars-prod
  → SQS: smartretail-ars-updates-prod
    → ARS polls queue, updates dashboard aggregates
```

### Flow 2 — RE Auto-approve

```
RE service polls re-alert-prod.fifo
  → evaluates auto-approve rules (supplier capacity, stock threshold)
  → if approved:
      UPDATE replenishment.purchase_orders
        SET status='APPROVED', version=v+1
        WHERE id=:id AND status='PENDING_APPROVAL' AND version=:v
      → publishes PurchaseOrderApprovedEvent to EventBridge
        → ars-updates-prod → ARS aggregates
```

### Flow 3 — SC Planner Manual Approve / Reject

```
SC Planner MFE (CloudFront → S3)
  → API Gateway /v1/replenishment/v1/purchase-orders/{id}/approve  (POST + JWT)
    → VPC Link → NLB :8082 → RE :8082
      → optimistic-lock UPDATE (version check required)
      → publishes PurchaseOrderApprovedEvent / RejectedEvent to EventBridge
        → ars-updates-prod → ARS aggregates
```

### Flow 4 — Dashboard reads (ARS)

```
Any MFE → API Gateway /v1/dashboard/* → ARS :8083
  ARS reads each schema via RDS Proxy independently (no cross-schema JOINs):
    inventory schema    → stock levels, alerts
    replenishment schema → PO pipeline, lead times
    forecasting schema   → MAPE, P10/P50/P90 forecasts
    supplier schema      → OTD, supplier scorecards
  → merged in Java service layer, returned as single JSON response
```

### Flow 5 — SageMaker Demand Forecasting (nightly)

```
EventBridge schedule: cron(0 2 * * ? *)   [daily 02:00 UTC]
  → Lambda: smartretail-ml-trigger-prod
      reads raw POS events from S3 (events bucket)
      → writes training manifest to smartretail-sagemaker-prod-{acct}
      → calls sagemaker:StartPipelineExecution
          pipeline: smartretail-demand-forecast-prod
          (training job + batch transform job)
      SageMaker writes model output to SageMaker bucket

S3 ObjectCreated on SageMaker bucket
  → Lambda: smartretail-batch-post-processor-prod
      reads transform output
      → POST to http://smartretail-dfs-prod.smartretail.local:8084
          (CloudMap DNS — DFS internal endpoint)
      DFS ingests forecasts into forecasting.demand_forecasts table
```

### Flow 6 — Promotion Activation (external → PPS)

```
Campaign Management System
  → POST /system/v1/events/promotions  (API key required)
    → API Gateway AWS integration → EventBridge PutEvents
        source: external.campaign-management
        detailType: PromotionActivated
      → SQS: smartretail-pps-inbound-prod
        → PPS :8086 polls queue
          → INSERT INTO promotions.promotion_events
          → applies pricing rules, publishes to EventBridge
```

### Flyway Migration (run once per deploy)

```
Operator:  make aws-push-flyway ENV=prod
  → docker buildx build --platform linux/amd64 backend/migrations/
  → docker push {ecr}/smartretail-flyway-prod:latest

Operator:  make aws-migrate ENV=prod
  → reads SSM /smartretail/prod/network/ecs-subnet-ids (PrivateApp subnets)
  →          /smartretail/prod/network/sg-ecs-tasks-id
  →          /smartretail/prod/network/assign-public-ip = DISABLED
  → aws ecs run-task --launch-type FARGATE
      --task-definition smartretail-flyway-prod
      --network-configuration {PrivateApp subnets, sgEcsTasks, assignPublicIp=DISABLED}
  → ECS task: Flyway → RDS Proxy :5432 → RDS (password from Secrets Manager)
  → applies pending migrations, exits 0
  → aws ecs wait tasks-stopped → reports result
```

---

## 9. S3 Buckets

| Bucket name                              | Purpose                        | Versioned | Lifecycle       | Removal  |
|------------------------------------------|--------------------------------|-----------|-----------------|----------|
| `smartretail-events-prod-{acct}`         | Firehose S3 backup (AllData)   | Yes       | Expire 7 years  | RETAIN   |
| `smartretail-sagemaker-prod-{acct}`      | SageMaker training + output    | Yes       | Expire 3 years  | RETAIN   |
| `smartretail-mfe-prod-store-manager-{acct}` | Store Manager MFE assets    | —         | —               | RETAIN   |
| `smartretail-mfe-prod-sc-planner-{acct}` | SC Planner MFE assets          | —         | —               | RETAIN   |
| `smartretail-mfe-prod-executive-{acct}`  | Executive Dashboard MFE assets | —         | —               | RETAIN   |
| `smartretail-mfe-prod-supplier-{acct}`   | Supplier Portal MFE assets     | —         | —               | RETAIN   |

---

## 10. Observability

| Signal             | Detail                                                                   |
|--------------------|--------------------------------------------------------------------------|
| Container logs     | CloudWatch Logs `/smartretail/{svc}/prod` · retention 3 months          |
| Flyway logs        | CloudWatch Logs `/smartretail/flyway/prod` · retention 3 months · RETAIN |
| RDS Perf Insights  | Enabled on `r6g.large` instance                                          |
| Metrics endpoint   | `GET /actuator/prometheus` (Micrometer) on every service                |
| Metric tags        | `service`, `flow`, `env` on all custom metrics                          |
| Custom metrics     | `replenishment.orders.created`, `pos.events.received`, `stock.alerts.published` |
| Circuit breaker    | ECS deployment circuit breaker with rollback                             |
| Health checks      | NLB HTTP `/actuator/health` every 30 s (2 healthy / 3 unhealthy)        |
| Correlation IDs    | `X-Correlation-ID` propagated; generated if absent; in every log line  |
| Log format         | Structured JSON — `timestamp`, `level`, `service`, `correlationId`, `traceId` |
| Error format       | RFC 7807 `ProblemDetail` on all 4xx/5xx                                 |

---

## 11. Key Resource Names

| Resource                  | Name / Pattern                                                     |
|---------------------------|--------------------------------------------------------------------|
| ECS cluster               | `smartretail-prod`                                                 |
| RDS instance              | `smartretail-rds-prod`                                             |
| RDS Proxy                 | `smartretail-rds-proxy-prod`                                       |
| RDS secret                | Auto-generated (ARN in SSM `/smartretail/prod/rds/secret-arn`)    |
| Firehose access key       | `/smartretail/prod/firehose/ingest-access-key`                    |
| NLB                       | `smartretail-nlb-prod`                                             |
| VPC Link                  | `smartretail-vpclink-prod`                                         |
| API Gateway               | `smartretail-api-prod` (stage `internal`)                          |
| Firehose stream           | `smartretail-ingest-prod`                                          |
| EventBridge bus           | `smartretail-events-prod`                                          |
| SageMaker pipeline        | `smartretail-demand-forecast-prod`                                 |
| ECR repos                 | `smartretail-{sis,ims,re,ars,dfs,sup,pps,batch-post-processor,ml-trigger,flyway}-prod` |
| System API key            | `smartretail-system-events-prod`                                   |
| Cognito internal pool     | `smartretail-internal-prod` (domain `smartretail-prod-internal`)   |
| Cognito supplier pool     | `smartretail-supplier-prod` (domain `smartretail-prod-supplier`)   |
| CloudFront distribution   | Single dist; SSM `/smartretail/prod/hosting/cloudfront-url`        |
| CloudMap namespace        | `smartretail.local`                                                |
| Flyway task family        | `smartretail-flyway-prod`                                          |
| SSM prefix                | `/smartretail/prod/`                                               |

---

## 12. CDK Stack Dependency Order

```
Prod-Network
  └── Prod-Data         (needs VPC + SGs for RDS/Proxy placement + S3 buckets)
        └── Prod-Messaging  (SQS + EventBridge — no VPC dependency)
              └── Prod-Hosting    (CloudFront + 4 MFE S3 buckets — no VPC dependency)
                    └── Prod-Identity   (Cognito — needs distributionUrl for OAuth callback)
                          └── Prod-Compute  (needs VPC, Data, Messaging)
                                └── Prod-Api  (needs VPC, Data, Messaging, Compute;
                                               creates NLB, VPC Link, API GW, Firehose)
```
