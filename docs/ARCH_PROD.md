# SmartRetail — Production Environment Architecture

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
