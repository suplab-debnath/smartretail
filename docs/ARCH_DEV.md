# SmartRetail — Dev Environment Architecture

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
            ┌──────────────────────────┤──────────────────────────────────────────────────┐
            │                          │                                                  │
   ┌────────▼─────────────────────┐    │ ┌─────────▼──────────────────────────────────┐   │
   │  Amazon Cognito              │    │ │  Amazon CloudFront (HostingStack)           │   │
   │  (IdentityStack)             │    │ │  HTTPS · *.smartretail.com · PriceClass 100 │   │
   │                              │    │ │  Single distribution with 4 path behaviors  │   │
   │  Internal Pool               │    │ │  (each behavior: OAC SigV4 + SPA rewrite fn)│   │
   │  smartretail-internal-dev    │    │ │    /store-manager/* → store-manager S3      │   │
   │  Groups:                     │    │ │    /sc-planner/*    → sc-planner S3         │   │
   │    • STORE_MANAGER           │    │ │    /executive/*     → executive S3          │   │
   │    • SC_PLANNER              │    │ │    /supplier/*      → supplier S3           │   │
   │    • EXECUTIVE · ADMIN       │    │ │    /* (default)     → 302 /sc-planner/      │   │
   │  Domain: smartretail-dev-    │    │ └───────────────────────┬────────────────────┘   │
   │          internal            │    │          ┌─────────────┼──────────────┐          │
   │                              │    │  ┌───────▼──┐ ┌────────▼─┐ ┌─────────▼┐ ┌──────▼───┐  │
   │  Supplier Pool               │    │  │    S3    │ │    S3    │ │    S3    │ │    S3    │  │
   │  smartretail-supplier-dev    │    │  │  store-  │ │   sc-    │ │executive │ │ supplier │  │
   │  Group: SUPPLIER_ADMIN       │    │  │  manager │ │ planner  │ │  -dev-   │ │  -dev-   │  │
   │  Domain: smartretail-dev-    │    │  │  -dev-   │ │  -dev-   │ │  {acct}  │ │  {acct}  │  │
   │          supplier            │    │  │  {acct}  │ │  {acct}  │ │          │ │          │  │
   │  OAuth: /supplier/callback   │    │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
   └────────┬─────────────────────┘    │                                                        │
            │ JWT Bearer token          │                                                        │
   ┌────────▼──────────────────────────────────────────────────────────────────────────────┐   │
   │                  Amazon API Gateway (Regional REST API)                                │   │
   │              smartretail-api-dev  │  stage: internal                                 │   │
   │                                                                                      │   │
   │  Staff routes (VPC Link → NLB HTTP_PROXY):                                           │   │
   │    /v1/dashboard/{proxy+}       → ARS  :8083                                         │   │
   │    /v1/inventory/{proxy+}       → IMS  :8081                                         │   │
   │    /v1/forecast/{proxy+}        → DFS  :8084                                         │   │
   │    /v1/replenishment/{proxy+}   → RE   :8082                                         │   │
   │    /v1/supplier/{proxy+}        → SUP  :8085                                         │   │
   │    /v1/ingest/{proxy+}          → SIS  :8080  (Firehose delivery target)             │   │
   │    /v1/promotions/{proxy+}      → PPS  :8086                                         │   │
   │                                                                                      │   │
   │  System route (EventBridge AWS direct integration, API key required):                │   │
   │    POST /system/v1/events/promotions → EventBridge PutEvents                         │   │
   │    Source: external.campaign-management │ DetailType: PromotionActivated             │   │
   │    Rate: 50 rps burst 100 │ Quota: 10,000 req/day                                   │   │
   │                                                                                      │   │
   │  CORS: https://*.smartretail.com  │  4xx/5xx CORS-safe gateway responses             │   │
   └───────────────┬──────────────────────────────────────────────────────────────────┬──┘   │
                   │ VPC Link: smartretail-vpclink-dev                                 │      │
                   │                                                                   │      │
   ┌───────────────┴──── Kinesis Data Firehose ───────────────────────────────────────┘      │
   │  Stream: smartretail-ingest-dev   Type: DirectPut                                        │
   │  HTTP endpoint: {api-url}/v1/ingest/events                                               │
   │  Auth: X-Access-Key (from Secrets Manager)                                               │
   │  Buffering: 1 MiB / 60 s  │  Retry: 86400 s                                            │
   │  S3 backup: AllData → smartretail-events-dev-{acct}/firehose/…                          │
   │             Compression: GZIP  │  Buffering: 5 MiB / 60 s                              │
   │  Role: FirehoseRole → S3 write on events bucket                                          │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────────────────────────────────────┐
│  VPC: 10.0.0.0/16                                                                           │
│                                                                                             │
│  ┌──────────────── PUBLIC SUBNETS (2 AZs) ─────────────────────────────────────────────┐   │
│  │  NAT Gateway (AZ-a only — shared by both PrivateApp subnets)   Internet Gateway      │   │
│  └──────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
│  ┌──────────── PRIVATEAPP SUBNETS (2 AZs, egress via single NAT) ─────────────────────┐    │
│  │                                                                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │  NLB: smartretail-nlb-dev  (internal, PrivateApp subnets)                   │   │    │
│  │  │  Listeners → Target Groups (health: HTTP /actuator/health, 30 s):           │   │    │
│  │  │    :8080 → sisContainer   :8081 → imsContainer   :8082 → reContainer        │   │    │
│  │  │    :8083 → arsContainer   :8084 → dfsContainer   :8085 → supContainer       │   │    │
│  │  │    :8086 → ppsContainer   (deregistration delay: 30 s)                      │   │    │
│  │  └──────────────────────────────┬──────────────────────────────────────────────┘   │    │
│  │                                 │                                                  │    │
│  │  ┌──────────────────────────────▼──────────────────────────────────────────────┐   │    │
│  │  │  ECS Cluster: smartretail-dev                                                │   │    │
│  │  │  Launch type: Fargate  │  Arch: x86_64  │  Container Insights V2            │   │    │
│  │  │  Capacity: FARGATE_SPOT (weight 4) + FARGATE (weight 1)                     │   │    │
│  │  │  CloudMap namespace: smartretail.local                                       │   │    │
│  │  │                                                                              │   │    │
│  │  │  Security Group: sgEcsTasks                                                  │   │    │
│  │  │    Ingress: TCP 8080–8086  from VPC CIDR (10.0.0.0/16)                      │   │    │
│  │  │    Ingress: all TCP        from sgEcsTasks (svc-to-svc)                      │   │    │
│  │  │    Egress:  all (0.0.0.0/0 — routed via NAT or VPC endpoints)               │   │    │
│  │  │                                                                              │   │    │
│  │  │  ┌────────────────────────────────────────────────────────────────────────┐  │   │    │
│  │  │  │  Persistent Services                                                   │  │   │    │
│  │  │  │  desired=1 · max=3 · scale on CPU>70% · circuit breaker+rollback       │  │   │    │
│  │  │  │  256 CPU · 512 MiB · assignPublicIp=false · profile=aws                │  │   │    │
│  │  │  │                                                                        │  │   │    │
│  │  │  │  SIS  :8080   sales schema        (+ Firehose access key secret)      │  │   │    │
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
│  │  │  │  256 CPU · 512 MiB · x86_64 · assignPublicIp=false                    │  │   │    │
│  │  │  │  FLYWAY_URL → RDS Proxy :5432                                          │  │   │    │
│  │  │  │  FLYWAY_PASSWORD injected from Secrets Manager (execution role)        │  │   │    │
│  │  │  │  Logs: /smartretail/flyway/dev (1 month, DESTROY)                     │  │   │    │
│  │  │  └────────────────────────────────────────────────────────────────────────┘  │   │    │
│  │  └──────────────────────────────┬──────────────────────────────────────────────┘   │    │
│  │                                 │                                                  │    │
│  │  ┌──────────────────────────────▼──────────────────────────────────────────────┐   │    │
│  │  │  Lambda: smartretail-batch-post-processor-dev                               │   │    │
│  │  │  Trigger: S3 ObjectCreated on smartretail-sagemaker-dev-{acct}             │   │    │
│  │  │           (prefix: sagemaker/output/, suffix: .csv)                        │   │    │
│  │  │  Timeout: 180 s  │  Memory: 512 MiB  │  x86_64                            │   │    │
│  │  │  VPC: PrivateApp subnets  │  SG: sgBatchProcessor (egress all)             │   │    │
│  │  │  Calls: http://smartretail-dfs-dev.smartretail.local:8084 (CloudMap)       │   │    │
│  │  │  Role: S3 GetObject on sagemaker bucket (sagemaker/output/*)               │   │    │
│  │  └─────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │  Lambda: smartretail-ml-trigger-dev                                         │   │    │
│  │  │  Trigger: EventBridge schedule  cron(0 2 * * ? *)  daily 02:00 UTC         │   │    │
│  │  │  Timeout: 300 s  │  Memory: 512 MiB  │  x86_64                             │   │    │
│  │  │  VPC: PrivateApp subnets  │  SG: sgMlTrigger (egress all)                  │   │    │
│  │  │  Calls: sagemaker:StartPipelineExecution on smartretail-demand-forecast-dev │   │    │
│  │  │  Role: S3 read (events bucket), S3 write (sagemaker bucket),               │   │    │
│  │  │        sagemaker:StartPipelineExecution                                     │   │    │
│  │  └─────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                     │    │
│  │  VPC Interface Endpoints (sgVpcEndpoints: ingress 443 from VPC CIDR):             │    │
│  │    ecr.api · ecr.dkr · sqs · events · logs · secretsmanager                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────── ISOLATED SUBNETS (2 AZs, no internet route) ───────────────────────┐     │
│  │                                                                                    │     │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐ │     │
│  │  │  RDS Proxy: smartretail-rds-proxy-dev                                        │ │     │
│  │  │  Subnets: isolated  │  TLS: not required  │  IAM auth: disabled              │ │     │
│  │  │  Secrets: RDS credentials (Secrets Manager)                                  │ │     │
│  │  │                                                                              │ │     │
│  │  │  Security Group: sgRdsProxy                                                  │ │     │
│  │  │    Ingress: TCP 5432  from sgEcsTasks                                        │ │     │
│  │  │    Egress:  all                                                              │ │     │
│  │  └──────────────────────────────┬───────────────────────────────────────────────┘ │     │
│  │                                 │ TCP :5432                                       │     │
│  │  ┌──────────────────────────────▼───────────────────────────────────────────────┐ │     │
│  │  │  RDS: smartretail-rds-dev                                                    │ │     │
│  │  │  Engine: PostgreSQL 16.13  │  Instance: t4g.small                           │ │     │
│  │  │  Storage: 20 GiB GP2  │  Single-AZ (dev sizing — no standby)               │ │     │
│  │  │  Backup: 1 day  │  Performance Insights: disabled                           │ │     │
│  │  │  Deletion protection: off  │  Removal policy: DESTROY                       │ │     │
│  │  │  DB name: smartretail  │  Admin: smartretail_admin                          │ │     │
│  │  │  Schemas: public · sales · forecasting · inventory ·                        │ │     │
│  │  │           replenishment · supplier · promotions                              │ │     │
│  │  │  CW Logs: postgresql → /aws/rds/…  (1 month)                               │ │     │
│  │  │  Secret: auto-generated (Secrets Manager)                                   │ │     │
│  │  │                                                                              │ │     │
│  │  │  Security Group: sgRds                                                       │ │     │
│  │  │    Ingress: TCP 5432  from sgRdsProxy only                                   │ │     │
│  │  │    Egress:  none                                                             │ │     │
│  │  └──────────────────────────────────────────────────────────────────────────────┘ │     │
│  └────────────────────────────────────────────────────────────────────────────────────┘     │
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
