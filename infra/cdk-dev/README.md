# cdk-dev — Development Infrastructure

Dev-tier SmartRetail infrastructure. Mirrors `cdk-prod` in all services and AWS config patterns — same VPC topology, RDS Proxy, CloudFront, VPC endpoints, Kinesis, and supplier Cognito pool. Differs only in sizing, compute, and autoscaling targets.

## Stack names

`Dev-NetworkStack`, `Dev-DataStack`, `Dev-MessagingStack`, `Dev-IdentityStack`,
`Dev-ComputeStack`, `Dev-ApiStack`, `Dev-HostingStack`

## What this stack deploys

| Layer | Resource |
|-------|----------|
| Network | New VPC (2 AZs, public + private-app + isolated subnets, 1 NAT Gateway, 6 interface VPC endpoints) |
| Data | RDS PostgreSQL t4g.small single-AZ (via RDS Proxy), DynamoDB idempotency table, S3 events bucket, S3 SageMaker bucket, 5 private MFE S3 buckets |
| Messaging | Kinesis stream (POS ingestion) + EventBridge bus + IMS/RE/ARS SQS queues |
| Identity | Internal Cognito pool (STORE\_MANAGER / SC\_PLANNER / EXECUTIVE) + Supplier Cognito pool (SUPPLIER\_ADMIN) |
| Compute | 7 ECS Fargate services (X86\_64, 0.25 vCPU / 512 MB, FARGATE\_SPOT 80/20) + Kinesis consumer Lambda (X86\_64) + Batch Post-Processor Lambda (X86\_64) |
| API | ALB with path-based routing to all 7 services |
| Hosting | CloudFront distributions (×5 MFEs) with private S3 origin using OAC (SIGV4) |

## Architecture

```
                        ┌──────────────────────────────────────────────────────────────────┐
                        │  Dedicated VPC  (2 AZs)                                          │
                        │                                                                  │
                        │  ┌─ public subnets ──────────────────────────────────────────┐  │
Internet ── ALB :80 ───►│  │  path-based routing (single listener)                     │  │
                        │  │  /v1/ingest/*        ──► SIS :8080 ─────────────────┐     │  │
                        │  └──────────────────────────────────────────────────────┼─────┘  │
                        │                                                         │         │
                        │  ┌─ private-app subnets (ECS + Lambda) ────────────────┤──────┐  │
                        │  │  /v1/inventory/*     ──► IMS :8081                  │      │  │
                        │  │  /v1/replenishment/* ──► RE  :8082                  │      │  │
                        │  │  /v1/dashboard/*     ──► ARS :8083                  │      │  │
                        │  │  /v1/forecast/*      ──► DFS :8084                  │      │  │
                        │  │  /v1/supplier/*      ──► SUP :8085                  │      │  │
                        │  │  /v1/promotions/*    ──► PPS :8086                  │      │  │
                        │  │                                                      ▼      │  │
                        │  │  Kinesis consumer Lambda ─► SIS (CloudMap discovery) │      │  │
                        │  │  Batch post-processor Lambda ◄─ S3 SageMaker bucket  │      │  │
                        │  │                          └──► DFS (CloudMap discovery)│      │  │
                        │  │                                          │            │      │  │
                        │  │  ECS Fargate (X86_64, FARGATE_SPOT 80%) │            │      │  │
                        │  └──────────────────────────────────────────┼────────────┘      │  │
                        │                                             │                    │  │
                        │  ┌─ isolated subnets (RDS Proxy + RDS) ────┘──────────────────┐ │  │
                        │  │  RDS Proxy ──► RDS PostgreSQL t4g.small (single-AZ)        │ │  │
                        │  └────────────────────────────────────────────────────────────┘ │  │
                        │                                                                  │
                        │  NAT Gateway (×1) · VPC interface endpoints (ECR, SQS, EB,      │
                        │  CloudWatch, Secrets Manager) · Gateway endpoints (S3, DDB)      │
                        └──────────────────────────────────────────────────────────────────┘

Kinesis stream (POS ingestion)  ──► Kinesis consumer Lambda  ──► SIS :8080
S3 SageMaker bucket (ObjectCreated) ──► Batch Post-Processor Lambda ──► DFS :8084

EventBridge bus (smartretail-events-dev)
  InventoryAlertEvent  ──► RE alert SQS FIFO  ──► RE service
  All domain events    ──► ARS updates SQS    ──► ARS service

Cognito: Internal pool (STORE_MANAGER / SC_PLANNER / EXECUTIVE)
         Supplier pool (SUPPLIER_ADMIN)

CloudFront (HTTPS, OAC/SigV4) ──► private S3 buckets
  store-manager / sc-planner / executive / supplier / demo
```

> **One ALB, not one-per-service.** All seven backend services share a single ALB with
> path-based listener rules. SIS is the only ingest-facing service; the Lambda Kinesis
> consumer forwards POS events to SIS via CloudMap service discovery.

## Sizing vs prod

| Property | cdk-dev | cdk-prod |
|----------|---------|----------|
| AZs | 2 | 3 |
| NAT Gateways | 1 | 3 |
| RDS instance | t4g.small | r6g.large |
| Multi-AZ RDS | No | Yes |
| Backup retention | 1 day | 7 days |
| Performance Insights | No | Yes |
| Task CPU / memory | 256 / 512 | 512 / 1024 |
| Desired count | 1 | 2 |
| Autoscale max | 3 | 6 |
| Container insights | Disabled | Enabled |
| Log retention | 1 week | 1 month |
| Removal policy | DESTROY | RETAIN |

## Deploy

```bash
cd infra/cdk-dev
npm install

export SMARTRETAIL_ENV=dev
export AWS_PROFILE=smartretail-dev
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=us-east-1

npx cdk bootstrap
npx cdk deploy --all --require-approval never
```

Stacks deployed in dependency order:
1. `Dev-NetworkStack`
2. `Dev-DataStack`
3. `Dev-MessagingStack`
4. `Dev-IdentityStack`
5. `Dev-ComputeStack`
6. `Dev-ApiStack`
7. `Dev-HostingStack`

## After deploy

```bash
# API endpoint (ALB)
aws cloudformation describe-stacks --stack-name Dev-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`AlbEndpoint`].OutputValue' --output text

# MFE CloudFront URLs
aws cloudformation describe-stacks --stack-name Dev-HostingStack \
  --query 'Stacks[0].Outputs' --output table

# Or via SSM
aws ssm get-parameters-by-path --path /smartretail/dev/hosting/ --output table
```

## Push a service image

```bash
SERVICE=pps   # sis | ims | re | ars | dfs | sup | pps
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$SERVICE-dev

aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com
docker build --platform linux/amd64 -t $REPO:latest backend/services/$SERVICE
docker push $REPO:latest
aws ecs update-service --cluster smartretail-dev --service smartretail-$SERVICE-dev --force-new-deployment
```

## Push a Lambda image

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com

# Kinesis Consumer Lambda
mvn clean package -DskipTests -pl backend/lambdas/kinesis-consumer
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-kinesis-consumer-dev
docker build --platform linux/amd64 -t $REPO:latest backend/lambdas/kinesis-consumer
docker push $REPO:latest

# Batch Post-Processor Lambda
mvn clean package -DskipTests -pl backend/lambdas/batch-post-processor
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-dev
docker build --platform linux/amd64 -t $REPO:latest backend/lambdas/batch-post-processor
docker push $REPO:latest
```

## Teardown

```bash
npx cdk destroy --all
```

RDS, S3, ECR, and CloudFront have `RemovalPolicy.DESTROY` so they are deleted on teardown.

## Key differences from cdk-demo (demo stack)

| Property | cdk-demo | cdk-dev |
|----------|---------------|---------|
| CPU architecture | ARM64 | X86\_64 |
| VPC | Default VPC reused | Dedicated VPC with private subnets |
| RDS | No Proxy, public subnet | RDS Proxy, isolated subnet |
| MFE hosting | S3 static website (HTTP) | CloudFront + OAC (HTTPS) |
| Auth | Internal pool only | Internal + Supplier pool |
| Services | 6 (no PPS) | 7 (includes PPS) |
| MFE buckets | 4 | 5 (includes supplier) |
