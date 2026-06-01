---
applyTo: "environments/**/*.ts"
---

# AWS CDK TypeScript Instructions -- SmartRetail

## Stack variants and prefixes
| Path | Prefix | Purpose |
|---|---|---|
| `environments/demo/infra/` | `Min-*` | Demo -- SQS only, default VPC, ARM64 |
| `environments/dev/infra/` | `Dev-*` | Dev -- Firehose, 2-AZ VPC, RDS Proxy |
| `environments/prod/infra/` | `Prod-*` | Prod -- Multi-AZ RDS, 3-AZ VPC |

Deploy order: **Network -> Data -> Messaging -> Identity -> Compute -> API**

## Resource naming
All resource names follow: `smartretail-{resource}-{env}`
Examples: `smartretail-ims-sales-dev`, `smartretail-re-alert-dev.fifo`, `smartretail-events-dev`

## Non-negotiable CDK rules
- ECS tasks connect to RDS via RDS Proxy **only** -- no direct RDS endpoint in JDBC URL
- All secrets in Secrets Manager -- never in ECS task environment variables
- Every SQS queue has a DLQ with `maxReceiveCount: 3`
- EventBridge routes to SQS targets -- never directly to Lambda
- API Gateway has JWT authorizer on all routes except Firehose ingest
- S3 buckets: `blockPublicAccess: BlockPublicAccess.BLOCK_ALL`, SSE-S3 minimum
- ECS tasks in private subnets -- no public ECS endpoints
- Removal policy: `RETAIN` on RDS and S3 in prod; `DESTROY` in demo/dev

## IAM
- Each ECS task has its own `TaskRole` -- no shared roles across services
- No `*` in actions or resources in any policy statement
- Use `grant*` methods on CDK constructs where available (e.g. `queue.grantConsumeMessages`)

## Health checks
ECS service health check path: `/actuator/health`
Health check grace period: minimum 60 seconds (services take time to start)

## Cross-stack values
Pass ARNs and names between stacks via `CfnOutput` + `Fn.importValue` -- no hardcoded ARNs.

## ECS task sizing
Demo: 256 CPU / 512 MB memory, ARM64, desired=1
Dev: 256 CPU / 512 MB memory, x86_64, desired=1, max=3
Prod: 512 CPU / 1024 MB memory, x86_64, desired=2, max=6
