# cdk-demo — SC Planner Demo Stack (ephemeral)

Self-contained AWS infrastructure for the SmartRetail SC Planner demo. It runs the **same
pipeline topology as dev/prod** — Firehose ingestion, the SageMaker forecasting pipeline, and the
post-processing Lambdas are all deployed — but right-sized for a stack that lives **1-2 days** and
is then destroyed cleanly. The expensive ML compute is gated off (dormant) so standing cost stays low.

## What this stack deploys

| Layer | Resource | Note |
|---|---|---|
| Network | Reuses existing **default VPC** (public subnets only) | No new VPC — no NAT gateway cost |
| Data | RDS PostgreSQL 16 `t4g.micro` (public subnet, SG-gated) · S3 events + SageMaker buckets · Firehose access-key + RDS secrets | No RDS Proxy, single-AZ, no backups |
| Messaging | EventBridge bus + IMS/RE/ARS SQS queues (+ DLQs) + 3 routing rules | RE-Alert is FIFO; raw POS arrives via Firehose, not SQS |
| Compute | **6** ECS Fargate services: SIS · IMS · RE · ARS · DFS · SUP (ARM64) + one-shot Flyway task | PPS omitted (not in prototype scope) |
| Ingestion / ML | Kinesis Data Firehose → API GW → SIS · SageMaker demand-forecast pipeline · `ml-trigger` + `batch-post-processor` Lambdas | Pipeline + cron **dormant** — $0 standing cost |
| Identity | Cognito **internal** user pool (`STORE_MANAGER` · `SC_PLANNER` · `EXECUTIVE` · `ADMIN` groups) | No supplier pool in demo |
| API | **API Gateway** (REST, regional) → VPC Link → **internal NLB** (6 listeners) | One stable HTTPS endpoint for the MFE & Firehose |
| Hosting | **CloudFront** (OAC + SigV4) → private S3 bucket | SC Planner MFE only |
| Monitoring | CloudWatch dashboard (`SmartRetail-demo-Ops`) + 6 alarms + SNS topic | API Gateway / SQS / RDS / ECS metrics |

> **RDS subnet note**: the default VPC has only public subnets, so RDS is placed in a public
> subnet with its security group (`sgRds`) restricted to the ECS task security group (`sgEcsTasks`)
> on TCP 5432. Acceptable for a short-lived demo; dev/prod use dedicated VPCs with isolated subnets.

> **No raw POS ingestion queue.** POS events flow Firehose → API Gateway → SIS directly (with an S3
> backup copy). The SQS queues carry only the downstream domain events (sales → IMS, alerts → RE,
> all → ARS) — same as dev/prod.

## Architecture

```
 POS events ─► Kinesis Data Firehose (smartretail-ingest-demo) ──┐   (records also archived to S3 events bucket)
                                                                 │
 Browser ─► CloudFront (OAC/SigV4) ─► S3 (SC Planner MFE)        │
 Browser ─► API Gateway (REST · regional · CORS · stage "internal")
                 │                                               │
                 ▼  VPC Link ─► internal NLB (smartretail-nlb-demo · 6 listeners)   [default VPC · public subnets]
                 ├─ /v1/ingest/{proxy+}        ─► SIS :8080   ◄──┘  (Firehose → /v1/ingest/events)
                 ├─ /v1/inventory/{proxy+}     ─► IMS :8081
                 ├─ /v1/replenishment/{proxy+} ─► RE  :8082
                 ├─ /v1/dashboard/{proxy+}     ─► ARS :8083
                 ├─ /v1/forecast/{proxy+}      ─► DFS :8084
                 └─ /v1/supplier/{proxy+}      ─► SUP :8085
                        (6 ECS Fargate services · ARM64 · on-demand · public IP · CloudMap smartretail.local)
                            │
                            ▼
                       RDS PostgreSQL t4g.micro   (public subnet · SG-gated · no RDS Proxy)

 EventBridge bus (smartretail-events-demo)
   SalesTransactionProcessed (source smartretail.sis) ─► IMS-Sales SQS       ─► IMS
   InventoryAlertEvent       (source smartretail.ims) ─► RE-Alert SQS (FIFO) ─► RE
   all smartretail.ims / .re events                   ─► ARS-Updates SQS     ─► ARS

 ML pipeline — deployed but DORMANT ($0 standing cost):
   EventBridge cron 02:00 UTC  [DISABLED] ─► ml-trigger Lambda [throttled, reservedConcurrency=0]
                                              └─► SageMaker pipeline  smartretail-demand-forecast-demo
   SageMaker S3 output (ObjectCreated) ─► batch-post-processor Lambda ─► DFS /v1/forecast
   (both Lambdas run OUTSIDE the VPC — demo has no NAT, so they reach S3/SageMaker/DFS via public + API GW)
```

> **API Gateway + internal NLB, not an ALB.** All six backend services sit behind one REST API
> Gateway that routes `/v1/{service}/{proxy+}` through a VPC Link to an internal NLB (one listener
> per service). SIS is the only ingest-facing service; Firehose delivers POS batches to its
> `/v1/ingest/events` endpoint. CORS is open (`ALL_ORIGINS`) and there is no Cognito authorizer on
> the routes — the demo relies on the MFE's auth flow, not gateway-level enforcement.

## CDK stacks

Deployed in dependency order:

`Min-NetworkStack` → `Min-DataStack` → `Min-MessagingStack` → `Min-HostingStack`
→ `Min-IdentityStack` → `Min-ComputeStack` → `Min-ApiStack` → `Min-MonitoringStack`

(`Min-IdentityStack` depends on `Min-HostingStack` for the CloudFront callback URL; `Min-ApiStack`
defines the Firehose stream, both Lambdas, and the SageMaker pipeline.)

## Quick start (SC Planner demo)

### One-command full deployment

```bash
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev ./environments/demo/scripts/deploy-demo.sh
```

With alarm email notifications:

```bash
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev \
  CDK_CONTEXT_alertEmail=you@example.com \
  ./environments/demo/scripts/deploy-demo.sh
```

### Makefile targets (granular control)

```bash
# First-time bootstrap (once per account/region)
make demo-bootstrap DEMO_PROFILE=smartretail-dev

# Deploy CDK infrastructure (all Min-* stacks)
make demo-cdk-deploy DEMO_PROFILE=smartretail-dev

# Build and push the 6 service images (sis ims re ars dfs sup)
make demo-push-services DEMO_PROFILE=smartretail-dev

# Run Flyway migrations — builds + pushes the Flyway image, includes V7 seed data
make demo-migrate DEMO_PROFILE=smartretail-dev

# Build and sync the SC Planner MFE to S3 (served via CloudFront)
make demo-deploy-mfe DEMO_PROFILE=smartretail-dev

# Create Cognito SC_PLANNER and ADMIN users
make demo-create-users DEMO_PROFILE=smartretail-dev

# All of the above in one shot
make demo-full-deploy DEMO_PROFILE=smartretail-dev

# Optional — only needed if you activate the (dormant) ML pipeline:
# build + push the arm64 ml-trigger and batch-post-processor Lambda images
make demo-push-lambda DEMO_PROFILE=smartretail-dev
```

## First-time VPC lookup

On your first `cdk synth`, CDK contacts AWS to find the default VPC and caches the result in
`cdk.context.json`. Commit that file after the first synth.

```bash
cd environments/demo/infra
npm install
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev npx cdk context
```

## Activating the ML pipeline (optional)

The SageMaker pipeline, its execution role, and the S3 buckets are deployed, but the demo keeps the
ML compute dormant for $0 standing cost: the EventBridge schedule is `enabled: false` and the
`ml-trigger` Lambda has `reservedConcurrentExecutions: 0`. A run costs ~$0.54 (training ~$0.48 +
batch-transform ~$0.06). To activate:

```bash
# 1. Push the Lambda images (if not already pushed)
make demo-push-lambda DEMO_PROFILE=smartretail-dev

# 2. Enable the schedule rule and lift the throttle (or set enabled:true /
#    remove reservedConcurrentExecutions in api-stack.ts and redeploy Min-ApiStack)
aws events enable-rule --name smartretail-ml-trigger-daily-demo
aws lambda put-function-concurrency --function-name smartretail-ml-trigger-demo \
  --reserved-concurrent-executions 1
```

## CloudWatch Dashboard

After deployment, open the `SmartRetail-demo-Ops` dashboard in CloudWatch:

```
https://{region}.console.aws.amazon.com/cloudwatch/home#dashboards:name=SmartRetail-demo-Ops
```

The dashboard shows:
- **API Layer** — API Gateway request count, 4xx / 5xx errors, p99 latency
- **Business Pipeline** — inventory alerts raised and POs created (via log metric filters)
- **Application Errors** — ERROR log count per service (via log metric filters)
- **ECS** — CPU and memory utilisation per service (Container Insights)
- **SQS** — queue depths and DLQ depths
- **RDS** — CPU, connections, free storage
- **Log Insights** — live error tail + pipeline event tail
- **Alarm Status** — state of all 6 alarms

### Alert notifications

Pass `alertEmail` as CDK context to subscribe to alarm notifications:

```bash
cd environments/demo/infra
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev \
  npx cdk deploy --all -c alertEmail=you@example.com
```

Or use `CDK_CONTEXT_alertEmail=you@example.com` with `./environments/demo/scripts/deploy-demo.sh`.

### Alarms (6 total)

| Alarm | Trigger |
|---|---|
| `SR-DLQ-ImsSales-demo` | Any message in the IMS-Sales DLQ |
| `SR-DLQ-ReAlert-demo` | Any message in the RE-Alert DLQ |
| `SR-DLQ-ArsUpdates-demo` | Any message in the ARS-Updates DLQ |
| `SR-APIGW-5xxErrors-demo` | API Gateway 5xx > 10 in 5 min |
| `SR-APIGW-p99Latency-demo` | API Gateway p99 latency > 3000 ms (2 periods) |
| `SR-RDS-CPUHigh-demo` | RDS CPU > 80% for 10 min |

## Resource tagging

All resources carry these tags for easy filtering and cost tracking:

| Tag | Value |
|---|---|
| `Project` | `smartretail` |
| `Variant` | `min` |
| `ManagedBy` | `cdk` |
| `Environment` | `{srEnv}` (e.g. `demo`) |
| `Lifecycle` | `ephemeral` |

To find all demo resources in the AWS console: filter by `Lifecycle = ephemeral`.

## Teardown

```bash
# Destroy all Min-* stacks (takes ~10 min)
make demo-destroy DEMO_PROFILE=smartretail-dev

# Or directly:
cd environments/demo/infra
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev npx cdk destroy --all --force
```

All resources use `RemovalPolicy.DESTROY` (S3 buckets and ECR repos auto-empty on delete), so
teardown leaves nothing behind. See `environments/demo/README.md` for the cost breakdown.
