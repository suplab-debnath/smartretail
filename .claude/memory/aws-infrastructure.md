# SmartRetail — AWS Infrastructure Reference

## Environment Summary

### local
- AWS services simulated via **LocalStack 3.x** at `http://localhost:4566`
- PostgreSQL at `localhost:5432`
- No Firehose; SIS accepts direct POST at `http://localhost:8080/v1/ingest/events`
- No Cognito; Spring Boot security filter in `local` profile bypasses JWT validation
- SQS queues created by `environments/local/scripts/localstack-init.sh` on container startup

### demo (`Min-*` CDK stacks)
- Reuses AWS default VPC — cheapest, fastest to deploy
- **No Firehose** — SIS exposed directly via API Gateway (HTTP integration)
- SQS queues only (no Firehose DLQs)
- RDS PostgreSQL single-AZ `t4g.medium`
- ECS Fargate ARM64 tasks (cost optimised)
- No RDS Proxy (direct JDBC connection from ECS tasks in private subnet)
- No CloudFront (MFEs served from ALB directly)

### dev (`Dev-*` CDK stacks)
- Dedicated 2-AZ VPC (2 private subnets, 1 NAT Gateway)
- **Firehose stream** (`smartretail-ingest-dev`) with dual delivery: S3 + HTTP endpoint
- **RDS Proxy** between ECS tasks and RDS (for connection pooling)
- **CloudFront** distribution in front of ALB (for MFE caching)
- ECS Fargate x86_64
- RDS PostgreSQL single-AZ `t4g.small`

### prod (`Prod-*` CDK stacks)
- Dedicated 3-AZ VPC (3 private subnets, 3 NAT Gateways)
- Firehose stream
- **RDS Multi-AZ** `r6g.large` with 7-day backup retention
- RDS Proxy
- CloudFront
- ECS desired count: 2, autoscale max: 6
- Container Insights enabled
- 1-month CloudWatch log retention

---

## CDK Stack Deploy Order (all environments)

```
1. Network Stack    — VPC, subnets, NAT, security groups
2. Data Stack       — RDS (+ RDS Proxy for dev/prod), S3 buckets, Secrets Manager entries
3. Messaging Stack  — SQS queues (+ DLQs), EventBridge bus, Firehose (dev/prod)
4. Identity Stack   — Cognito User Pool, App Client, hosted UI
5. Compute Stack    — ECS cluster, task definitions, ALB, ECS services, Lambda functions
6. API Stack        — API Gateway, routes, Lambda authorizer, VPC Link
```

Demo stack path: `environments/demo/infra/`
Dev stack path: `environments/dev/infra/`
Prod stack path: `environments/prod/infra/` (manual deploy only)

---

## AWS Services Used

| Service | Purpose |
|---|---|
| **ECS Fargate** | Runs all 7 Spring Boot services as tasks |
| **RDS PostgreSQL 15** | Primary data store for all 6 owned schemas |
| **RDS Proxy** | Connection pooling between ECS and RDS (dev/prod) |
| **EventBridge** | Custom bus for domain events; rules fan out to SQS targets |
| **SQS Standard** | IMS and ARS event queues |
| **SQS FIFO** | RE alert queue (MessageGroupId = dcId for ordering within DC) |
| **API Gateway** | HTTP API in front of all ECS services + Firehose ingest endpoint |
| **Amazon Data Firehose** | Batched POS event delivery: S3 archive + HTTP endpoint to SIS |
| **S3** | Raw event archive (Firehose delivery) + SageMaker artefacts |
| **Cognito** | User pools, hosted UI, PKCE OAuth2 flow for MFEs |
| **CloudFront** | CDN in front of ALB for MFEs (dev/prod) |
| **Lambda** | Batch Post-Processor (S3 trigger), SageMaker Trigger (EventBridge schedule) |
| **SageMaker** | Demand forecasting: DeepAR training jobs + batch transform |
| **Secrets Manager** | DB credentials, Firehose access key |
| **CloudWatch Logs** | Structured JSON logs from all ECS services |
| **CloudWatch Metrics** | Custom metrics via Micrometer + CloudWatch exporter |
| **X-Ray** | Distributed tracing (dev/prod) |
| **ACM** | TLS certificates for CloudFront and API Gateway |

---

## Lambda Functions

| Lambda | Source | Trigger | Role |
|---|---|---|---|
| `smartretail-batch-post-processor-{env}` | `backend/adapters/batch-post-processor/` | S3 ObjectCreated on `sagemaker/output/` prefix | Read S3 CSV → POST to DFS |
| `smartretail-ml-trigger-{env}` | `backend/adapters/ml-trigger/` | EventBridge scheduled rule (weekly) | Start SageMaker training + batch transform |

Both Lambdas are pure infrastructure adapters — no domain logic.

---

## SQS Configuration

| Queue | Type | DLQ | maxReceiveCount | MessageGroupId (FIFO) |
|---|---|---|---|---|
| `smartretail-ims-sales-{env}` | Standard | `...-dlq` | 3 | n/a |
| `smartretail-re-alert-{env}.fifo` | FIFO | `...-dlq.fifo` | 3 | dcId |
| `smartretail-ars-updates-{env}` | Standard | `...-dlq` | 3 | n/a |

Visibility timeout: 30s (all queues). Message retention: 4 days.

---

## IAM Principles

- Each ECS task has its own Task Role — no shared roles
- Task roles have only the permissions needed (EventBridge:PutEvents, SQS:ReceiveMessage, etc.)
- No wildcard `*` on actions or resources in any policy
- Lambda execution roles limited to S3:GetObject on specific prefix + the DFS HTTP call
- No EC2 instance profiles — Fargate uses task roles via IMDS v2
