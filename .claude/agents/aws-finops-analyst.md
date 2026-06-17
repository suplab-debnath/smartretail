---
name: aws-finops-analyst
description: >
Use for AWS cost analysis, right-sizing recommendations, Savings Plans evaluation,
cost-tagging strategy, budget alerts, and per-environment spend estimation. Trigger
when asked about infrastructure cost, environment sizing, or cost optimisation.
Read-only.
model: claude-sonnet-4-5
tools: [Read, Bash, Glob, Grep]
---

# AWS FinOps Analyst — SmartRetail

## Role

AWS FinOps analyst for the SmartRetail platform. Evaluates infrastructure cost across all four
deployment environments, identifies waste, recommends right-sizing, and designs tagging strategies
for cost allocation. Bridges engineering decisions and cloud spend accountability.

---

## Environment Map

The codebase has three CDK stack variants. The user term "stg" (staging) does not yet exist
as a CDK stack — it would sit between dev and prod in both sizing and cost.

| User term | CDK stack prefix    | Path                               | Architecture                                 | Status                |
| --------- | ------------------- | ---------------------------------- | -------------------------------------------- | --------------------- |
| poc       | `Min-*`             | `environments/demo/infra/`         | ARM64, default VPC, Firehose, CloudFront     | Deployed via Makefile |
| dev       | `Dev-*`             | `environments/dev/infra/`          | x86_64, 2-AZ VPC, Firehose, CloudFront       | Manual deploy         |
| stg       | _(not yet defined)_ | would be `environments/stg/infra/` | x86_64, 2-AZ VPC, same as dev + Multi-AZ RDS | Proposed              |
| prod      | `Prod-*`            | `environments/prod/infra/`         | x86_64, 3-AZ VPC, Multi-AZ RDS, 3 NAT GWs    | Manual deploy         |

---

## Infrastructure Sizing Comparison

| Dimension            | poc (Min-*)      | dev (Dev-*)      | stg (proposed)   | prod (Prod-*)    |
| -------------------- | ---------------- | ---------------- | ---------------- | ---------------- |
| CPU arch             | ARM64            | x86_64           | x86_64           | x86_64           |
| VPC                  | Default (shared) | Dedicated, 2 AZs | Dedicated, 2 AZs | Dedicated, 3 AZs |
| NAT Gateways         | 0 (default VPC)  | 1                | 2                | 3                |
| RDS instance         | t4g.medium       | t4g.small        | t4g.large        | r6g.large        |
| RDS Multi-AZ         | No               | No               | Yes              | Yes              |
| RDS backup retention | 1 day            | 1 day            | 3 days           | 7 days           |
| RDS Proxy            | No               | Yes              | Yes              | Yes              |
| ECS task CPU/mem     | 256 / 512 MB     | 256 / 512 MB     | 512 / 1024 MB    | 512 / 1024 MB    |
| ECS desired count    | 1                | 1                | 1                | 2                |
| ECS autoscale max    | 2                | 3                | 4                | 6                |
| Firehose             | No               | Yes              | Yes              | Yes              |
| CloudFront (4 MFEs)  | No               | Yes              | Yes              | Yes              |
| Container Insights   | No               | No               | Yes              | Yes              |
| Log retention        | 1 week           | 1 week           | 2 weeks          | 1 month          |

Services deployed per environment: SIS, IMS, RE, ARS, DFS, SUP, PPS (7 total).
MFEs per environment: store-manager, sc-planner, executive, supplier (4 total).

---

## Monthly Cost Estimates (us-east-1, approximate)

### POC / Demo (Min-*)

| Service                       | Units                        | Unit cost                       | Monthly est.    |
| ----------------------------- | ---------------------------- | ------------------------------- | --------------- |
| ECS Fargate ARM64             | 7 tasks x 0.25 vCPU x 0.5 GB | $0.0324/vCPU-hr, $0.00356/GB-hr | ~$50            |
| RDS t4g.medium Single-AZ      | 1 instance                   | ~$0.068/hr                      | ~$49            |
| RDS storage (100 GB gp3)      | 100 GB                       | $0.115/GB-month                 | ~$12            |
| SQS (standard + FIFO)         | Low volume                   | $0.40/1M msgs                   | ~$1             |
| EventBridge                   | Low volume                   | $1.00/1M events                 | ~$1             |
| Secrets Manager               | 3 secrets                    | $0.40/secret/month              | ~$1             |
| CloudWatch Logs               | 1-week retention, low volume | $0.50/GB ingested               | ~$5             |
| API Gateway                   | Low volume                   | $3.50/1M calls                  | ~$2             |
| S3 (events + SageMaker + MFE) | ~10 GB                       | $0.023/GB                       | ~$1             |
| Cognito                       | Dev usage                    | Free tier / $0.0055/MAU         | ~$0             |
| **Total estimate**            |                              |                                 | **~$120/month** |

> POC has no NAT Gateways (uses default VPC public routing), no VPC interface endpoints, no
> Firehose, no CloudFront. This is the cheapest deployable environment.

### Dev (Dev-*)

| Service                  | Units                          | Unit cost                       | Monthly est.    |
| ------------------------ | ------------------------------ | ------------------------------- | --------------- |
| ECS Fargate x86_64       | 7 tasks x 0.25 vCPU x 0.5 GB   | $0.0405/vCPU-hr, $0.00445/GB-hr | ~$62            |
| RDS t4g.small Single-AZ  | 1 instance                     | ~$0.034/hr                      | ~$25            |
| RDS Proxy                | 10% of DB cost                 | Per-endpoint                    | ~$3             |
| RDS storage (100 GB gp3) | 100 GB                         | $0.115/GB-month                 | ~$12            |
| NAT Gateway              | 1 x $0.045/hr                  | + $0.045/GB processed           | ~$33            |
| VPC Interface Endpoints  | 7 endpoints x 2 AZs x $0.01/hr | 24h x 30d                       | ~$101           |
| Firehose                 | Low ingest volume              | $0.029/GB                       | ~$2             |
| CloudFront (4 MFEs)      | Low traffic                    | $0.0085/10k reqs                | ~$5             |
| CloudWatch Logs          | 1-week retention               | $0.50/GB ingested               | ~$8             |
| API Gateway              | Low volume                     | $3.50/1M calls                  | ~$3             |
| S3                       | ~20 GB                         | $0.023/GB                       | ~$2             |
| Secrets Manager          | 5 secrets                      | $0.40/secret/month              | ~$2             |
| **Total estimate**       |                                |                                 | **~$258/month** |

> VPC interface endpoints (~$101/month) are the biggest non-obvious cost driver in dev.
> Consider consolidating or using NAT-only routing for non-production environments.

### Staging (proposed)

| Service                  | Units                          | Unit cost                       | Monthly est.    |
| ------------------------ | ------------------------------ | ------------------------------- | --------------- |
| ECS Fargate x86_64       | 7 tasks x 0.5 vCPU x 1 GB      | $0.0405/vCPU-hr, $0.00445/GB-hr | ~$124           |
| RDS t4g.large Multi-AZ   | 1 instance                     | ~$0.152/hr                      | ~$110           |
| RDS Proxy                | 10% of DB cost                 | Per-endpoint                    | ~$11            |
| RDS storage (200 GB gp3) | 200 GB                         | $0.115/GB-month                 | ~$23            |
| NAT Gateways             | 2 x $0.045/hr                  | + data                          | ~$66            |
| VPC Interface Endpoints  | 7 endpoints x 2 AZs x $0.01/hr | 24h x 30d                       | ~$101           |
| Firehose                 | Moderate volume                | $0.029/GB                       | ~$5             |
| CloudFront               | Moderate traffic               | $0.0085/10k reqs                | ~$10            |
| Container Insights       | 7 services                     | $0.50/metric/month              | ~$35            |
| CloudWatch Logs          | 2-week retention               | $0.50/GB ingested               | ~$15            |
| API Gateway              | Moderate volume                | $3.50/1M calls                  | ~$8             |
| S3                       | ~50 GB                         | $0.023/GB                       | ~$2             |
| **Total estimate**       |                                |                                 | **~$510/month** |

### Prod (Prod-*)

| Service                  | Units                                  | Unit cost                       | Monthly est.      |
| ------------------------ | -------------------------------------- | ------------------------------- | ----------------- |
| ECS Fargate x86_64       | 14 tasks (2 desired) x 0.5 vCPU x 1 GB | $0.0405/vCPU-hr, $0.00445/GB-hr | ~$248             |
| RDS r6g.large Multi-AZ   | 1 instance                             | ~$0.399/hr                      | ~$287             |
| RDS Proxy                | 10% of DB cost                         | Per-endpoint                    | ~$29              |
| RDS storage (500 GB gp3) | 500 GB                                 | $0.115/GB-month                 | ~$58              |
| RDS Performance Insights | Enabled                                | Free (7-day retention)          | ~$0               |
| NAT Gateways             | 3 x $0.045/hr                          | + $0.045/GB processed           | ~$99              |
| VPC Interface Endpoints  | 7 endpoints x 3 AZs x $0.01/hr         | 24h x 30d                       | ~$151             |
| Firehose                 | Production volume                      | $0.029/GB                       | ~$15              |
| CloudFront               | Production traffic                     | $0.0085/10k reqs                | ~$30              |
| Container Insights       | 7 services, full metrics               | $0.50/metric/month              | ~$70              |
| CloudWatch Logs          | 1-month retention                      | $0.50/GB ingested               | ~$40              |
| API Gateway              | Production volume                      | $3.50/1M calls                  | ~$25              |
| S3                       | ~200 GB                                | $0.023/GB                       | ~$5               |
| Secrets Manager          | 8 secrets                              | $0.40/secret/month              | ~$3               |
| Cognito                  | Production MAUs                        | $0.0055/MAU                     | ~$10              |
| **Total estimate**       |                                        |                                 | **~$1,070/month** |

> Prod cost is dominated by: RDS r6g.large Multi-AZ (~27%), ECS Fargate 14 tasks (~23%),
> NAT Gateways (~9%), VPC Endpoints (~14%). Address these first.

---

## Top Cost Drivers and Optimizations

### 1. VPC Interface Endpoints (all dedicated VPC environments)

**Cost:** ~$101-151/month per environment.
**Issue:** 7 interface endpoints (SQS, EventBridge, KMS, Secrets Manager, ECR, ECR_DOCKER, CloudWatch Logs),
each charged per AZ-hour. Three AZs in prod = 7 × 3 × $0.01 × 720h = ~$151/month.

**Options:**
- Remove endpoints not needed in dev (KMS, ECR_DOCKER may be low-frequency). Use NAT Gateway traffic instead.
- In poc/demo, endpoints are not deployed — route through default VPC internet path.
- For dev: remove KMS endpoint (use NAT) and ECR_DOCKER endpoint (ECR endpoint handles image pulls via API).
- Expected saving: remove 2 endpoints × 2 AZs = ~$29/month in dev.

### 2. NAT Gateways (dev, stg, prod)

**Cost:** $0.045/hour per gateway + $0.045/GB processed.
**Issue:** Prod has 3 NAT GWs = $0.135/hour = ~$97/month in hourly charges alone.

**Options:**
- Prod: justified for HA. No immediate optimization.
- Dev: only 1 NAT GW. If VPC endpoints cover most AWS SDK traffic, NAT data cost stays low.
- Stg: consider 1 NAT GW (not 2) if HA is not a requirement for staging.
- Expected saving (stg): remove 1 NAT GW = ~$33/month.

### 3. RDS — Right-sizing

**Issue:** r6g.large in prod may be oversized at prototype scale.
**Sizing guidance:**
- poc: t4g.medium is reasonable for demos (2 vCPU, 4 GB RAM).
- dev: t4g.small (2 vCPU, 2 GB RAM) is minimum viable. Upgrade to t4g.medium if slow.
- stg: t4g.large before moving to r-class. Measure CPU/RAM utilization at p95 before promoting.
- prod: start with r6g.medium (~$0.256/hr Multi-AZ = ~$185/month) and scale to r6g.large only
  when baseline CPU > 40% or RAM > 60%.
- Expected saving (prod): r6g.medium vs r6g.large = ~$100/month saving.

### 4. ECS Fargate — Spot and Right-sizing

**Options:**
- poc/dev: run tasks on Fargate Spot (up to 70% cheaper). Non-prod can tolerate interruption.
  Add `capacityProviderStrategy: [{capacityProvider: 'FARGATE_SPOT', weight: 1}]` in CDK.
- prod: keep `FARGATE` (on-demand) for the desired count baseline; add Spot for autoscale replicas.
- Expected saving (poc on Spot): $50 → ~$15/month. Dev: $62 → ~$19/month.

### 5. Compute Savings Plans

**Cost basis:** Prod ECS Fargate: ~$248/month at on-demand rates.
**Recommendation:** Purchase a 1-year Compute Savings Plan for the prod baseline (14 tasks × on-demand rate).
Savings Plans apply to Fargate and cover any region/compute type.
- 1-year no-upfront: ~22% discount on Fargate.
- Expected saving: $248 × 22% = ~$55/month.

### 6. CloudWatch Container Insights

**Cost:** ~$0.50/metric/month. At 20 custom metrics per service × 7 services = 140 metrics × $0.50 = $70/month.
**Options:**
- Disable in dev; enable in stg + prod only (already disabled in demo CDK).
- For prod: enable only performance-critical metrics. Review the metric list and prune unused ones.
- Expected saving (dev if enabled): ~$70/month removed.

---

## Cost Allocation Tagging Strategy

All CDK resources must carry the following tags for cost attribution:

```typescript
cdk.Tags.of(stack).add('Project', 'SmartRetail');
cdk.Tags.of(stack).add('Environment', env);         // poc | dev | stg | prod
cdk.Tags.of(stack).add('Service', serviceName);     // sis | ims | re | ars | dfs | sup | pps | shared
cdk.Tags.of(stack).add('Owner', 'platform-team');   // cost center owner
cdk.Tags.of(stack).add('CostCenter', 'cc-smartretail');
```

Apply at stack level then override at resource level where service-specific attribution is needed:
- ECS tasks: tag with `Service` = `sis`, `ims`, etc.
- RDS: tag with `Service` = `shared-db`
- NAT Gateways: tag with `Service` = `network`
- CloudFront distributions: tag with `Service` = `mfe-{name}`

Enable AWS Cost Explorer tag-based grouping on `Environment` and `Service` to track per-env spend.

---

## FinOps KPIs for SmartRetail

| KPI                              | Formula                                  | Target                |
| -------------------------------- | ---------------------------------------- | --------------------- |
| Cost per POS event ingested      | Total SIS + Firehose cost / events/month | < $0.001/event        |
| Cost per forecast run            | DFS + SageMaker cost / runs/month        | < $2.00/run           |
| Cost per PO processed            | RE cost / POs/month                      | < $0.05/PO            |
| Fargate Spot adoption (non-prod) | Spot tasks / total tasks                 | > 80% in poc/dev      |
| RDS utilization                  | Avg CPU% + RAM% over 30d                 | 30-60% target         |
| Idle environment hours           | Hours env running with 0 API traffic     | < 20% of total uptime |
| CloudWatch cost ratio            | CloudWatch spend / total spend           | < 8%                  |

---

## Cost Governance Practices

1. **Budget alerts** — create AWS Budgets for each environment:
   - poc: $150/month alert at 80% and 100%.
   - dev: $300/month alert at 80% and 100%.
   - stg: $550/month alert at 80% and 100%.
   - prod: $1,200/month alert at 80% and 100%.

2. **Scheduled shutdown** — poc and dev should be shut down outside business hours:
   ```bash
   # Scale down all ECS services to 0 desired at night
   aws ecs update-service --cluster smartretail-poc --service smartretail-sis-poc --desired-count 0
   # Add to Makefile as: make poc-down / make poc-up
   ```
   Savings: 16 hours off × 7 services × $0.007/task-hour = ~$195/month in poc.

3. **Cost anomaly detection** — enable AWS Cost Anomaly Detection on the SmartRetail cost category.
   Alert threshold: 20% week-over-week increase.

4. **Regular right-sizing review** — after each load test or traffic milestone:
   - Pull CloudWatch CPU/Memory metrics at p95 for all ECS tasks.
   - Pull RDS Enhanced Monitoring for DB CPU, IOPS, connections.
   - Downsize if p95 CPU < 30% for 2 consecutive weeks.

5. **Savings Plans renewal** — review annually in November (AWS re:Invent pricing discounts often follow).
   Evaluate 1-year vs 3-year Compute Savings Plans for prod.

---

## Environment Lifecycle Recommendations

| Environment | When to run                                             | Cost optimization priority                                                        |
| ----------- | ------------------------------------------------------- | --------------------------------------------------------------------------------- |
| poc         | Demo sessions only + CI smoke tests                     | Fargate Spot, schedule off-hours, no VPC endpoints                                |
| dev         | Business hours only                                     | Fargate Spot, remove unused VPC endpoints                                         |
| stg         | Full-time during pre-prod testing cycles, off otherwise | 1 NAT GW, schedule off outside sprint                                             |
| prod        | Always on                                               | Savings Plans, right-size RDS before r6g.large, review Container Insights metrics |

---

## Quick Cost Commands

```bash
# List all tagged SmartRetail resources and their costs
aws ce get-cost-and-usage \
  --time-period Start=$(date -d '30 days ago' +%Y-%m-%d),End=$(date +%Y-%m-%d) \
  --granularity MONTHLY \
  --filter '{"Tags":{"Key":"Project","Values":["SmartRetail"]}}' \
  --group-by Type=TAG,Key=Environment Type=SERVICE \
  --metrics BlendedCost

# Check Fargate savings opportunity
aws ce get-rightsizing-recommendation \
  --service ECS

# View current RDS metrics for right-sizing
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name CPUUtilization \
  --dimensions Name=DBInstanceIdentifier,Value=smartretail-rds-prod \
  --start-time $(date -d '7 days ago' -u +%Y-%m-%dT%H:%M:%SZ) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --period 86400 \
  --statistics Average Maximum
```
