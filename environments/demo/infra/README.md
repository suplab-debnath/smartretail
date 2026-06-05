# cdk-demo — SC Planner Demo Stack (SQS-only, ephemeral)

Minimal AWS infrastructure for the SmartRetail SC Planner demo.
Designed to live **1-2 days**, then be destroyed cleanly.

## What this stack deploys

| Layer | Resource | Note |
|---|---|---|
| Network | Reuses existing **default VPC** | No new VPC — no NAT gateway cost |
| Data | RDS PostgreSQL t4g.micro (public subnet, SG-restricted) | No isolated subnets in default VPC; access is SG-gated |
| Messaging | EventBridge bus + IMS/RE/ARS SQS queues + DLQs | No POS ingestion queue (SIS not deployed) |
| Compute | **5** ECS Fargate services: IMS · RE · ARS · DFS · SUP | SIS omitted — sales data is pre-seeded |
| Identity | Cognito User Pool (SC Planner users only) | |
| API | ALB with path-based routing to 5 services | One stable endpoint for the MFE |
| Hosting | SC Planner MFE S3 bucket (public website) | store-manager and executive MFEs not deployed |
| Monitoring | CloudWatch Dashboard + 6 alarms + SNS topic | `SmartRetail-{env}-Ops` dashboard |

> **RDS subnet note**: the default VPC used here has only public subnets, so RDS is placed in
> a public subnet with its security group restricted to ECS tasks only (`sgRds`). This is
> acceptable for a short-lived demo. The prod stack uses a dedicated VPC with private subnets.

## Architecture

```
                          ┌─────────────────────────────────────────────────┐
                          │              Default VPC (public subnets)        │
                          │                                                  │
Internet ──── ALB :80 ───►│  path-based routing (single listener)            │
                          │  /v1/inventory/*     ──► IMS :8081  ─────────┐  │
                          │  /v1/replenishment/* ──► RE  :8082  ─────────┤  │
                          │  /v1/dashboard/*     ──► ARS :8083  ─────────┤──┼──► RDS PostgreSQL
                          │  /v1/forecast/*      ──► DFS :8084  ─────────┤  │    t4g.micro (public
                          │  /v1/supplier/*      ──► SUP :8085  ─────────┘  │    subnet, SG-gated)
                          │                                                  │
                          │  ECS Fargate (ARM64, on-demand)                  │
                          │  CloudMap: smartretail.local                     │
                          └─────────────────────────────────────────────────┘

EventBridge bus (smartretail-events-demo)
  InventoryAlertEvent  ──► RE alert SQS FIFO  ──► RE service
  All domain events    ──► ARS updates SQS    ──► ARS service

Cognito User Pool  ──► SC Planner / Admin users

S3 static website (HTTP)  ──► SC Planner MFE
```

> **One ALB, not one-per-service.** All five backend services share a single ALB with
> path-based listener rules. No API Gateway — unnecessary complexity for a short-lived demo.

## CDK stacks

`Min-NetworkStack` → `Min-DataStack` → `Min-MessagingStack` → `Min-IdentityStack`
→ `Min-ComputeStack` → `Min-ApiStack` → `Min-HostingStack` → `Min-MonitoringStack`

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

# Deploy CDK infrastructure
make demo-cdk-deploy DEMO_PROFILE=smartretail-dev

# Build and push the 5 service images (ims re ars dfs sup)
make demo-push-services DEMO_PROFILE=smartretail-dev

# Run Flyway migrations — this includes V7 seed data
make demo-migrate DEMO_PROFILE=smartretail-dev

# Build and sync SC Planner MFE to S3
make demo-deploy-mfe DEMO_PROFILE=smartretail-dev

# Create Cognito SC_PLANNER and ADMIN users
make demo-create-users DEMO_PROFILE=smartretail-dev

# All of the above in one shot
make demo-full-deploy DEMO_PROFILE=smartretail-dev
```

## First-time VPC lookup

On your first `cdk synth`, CDK contacts AWS to find the default VPC and caches the result in
`cdk.context.json`. Commit that file after the first synth.

```bash
cd environments/demo/infra
npm install
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev npx cdk context
```

## CloudWatch Dashboard

After deployment, open the `SmartRetail-{env}-Ops` dashboard in CloudWatch:

```
https://{region}.console.aws.amazon.com/cloudwatch/home#dashboards:name=SmartRetail-demo-Ops
```

The dashboard shows:
- **API Layer** — ALB request count, error rate, response time, unhealthy hosts
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
| `SR-DLQ-ImsSales-demo` | Any message in IMS-Sales DLQ |
| `SR-DLQ-ReAlert-demo` | Any message in RE-Alert DLQ |
| `SR-DLQ-ArsUpdates-demo` | Any message in ARS-Updates DLQ |
| `SR-ALB-UnhealthyHosts-demo` | Any service fails health check |
| `SR-ALB-5xxErrors-demo` | > 10 server errors in 5 min |
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

To find all demo resources in AWS console: filter by `Lifecycle = ephemeral`.

## Teardown

```bash
# Destroy all Min-* stacks (takes ~10 min)
make demo-destroy DEMO_PROFILE=smartretail-dev

# Or directly:
cd environments/demo/infra
SMARTRETAIL_ENV=demo AWS_PROFILE=smartretail-dev npx cdk destroy --all --force
```
