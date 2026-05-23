# cdk-dev — Development Infrastructure

Dev-tier SmartRetail infrastructure. Mirrors `cdk-prod` in all services and AWS config patterns — same VPC topology, RDS Proxy, CloudFront, VPC endpoints, Kinesis, and supplier Cognito pool. Differs only in sizing, compute, and autoscaling targets.

## Stack names

`Dev-NetworkStack`, `Dev-DataStack`, `Dev-MessagingStack`, `Dev-IdentityStack`,
`Dev-ComputeStack`, `Dev-ApiStack`, `Dev-HostingStack`

## What this stack deploys

| Layer | Resource |
|-------|----------|
| Network | New VPC (2 AZs, public + private-app + isolated subnets, 1 NAT Gateway, 6 interface VPC endpoints) |
| Data | RDS PostgreSQL t4g.small single-AZ (via RDS Proxy), DynamoDB idempotency table, S3 events bucket, 5 private MFE S3 buckets |
| Messaging | Kinesis stream (POS ingestion) + EventBridge bus + IMS/RE/ARS SQS queues |
| Identity | Internal Cognito pool (STORE\_MANAGER / SC\_PLANNER / EXECUTIVE) + Supplier Cognito pool (SUPPLIER\_ADMIN) |
| Compute | 7 ECS Fargate services (X86\_64, 0.25 vCPU / 512 MB, FARGATE\_SPOT 80/20) + Kinesis consumer Lambda (X86\_64) |
| API | ALB with path-based routing to all 7 services |
| Hosting | CloudFront distributions (×5 MFEs) with private S3 origin using OAC (SIGV4) |

## Architecture

```
Internet
  │
  ├── ALB (:80) ──── ECS Fargate tasks (PRIVATE_WITH_EGRESS, X86_64)
  │      path-based routing
  │      /v1/ingest/*        → SIS :8080
  │      /v1/inventory/*     → IMS :8081
  │      /v1/replenishment/* → RE  :8082
  │      /v1/dashboard/*     → ARS :8083
  │      /v1/forecast/*      → DFS :8084
  │      /v1/supplier/*      → SUP :8085
  │      /v1/promotions/*    → PPS :8086
  │                                    │
  │                          RDS Proxy → RDS t4g.small (isolated subnet)
  │
  └── CloudFront (HTTPS) ──── private S3 buckets (OAC)
       store-manager / sc-planner / executive / supplier / demo
```

VPC: 2 AZs · public subnets (ALB) · private-app subnets (ECS + Lambda) · isolated subnets (RDS Proxy + RDS) · 1 NAT Gateway · free Gateway endpoints (S3, DynamoDB) · interface endpoints (ECR API, ECR Docker, SQS, EventBridge, CloudWatch Logs, Secrets Manager)

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
docker build --platform linux/amd64 -t $REPO:latest services/$SERVICE
docker push $REPO:latest
aws ecs update-service --cluster smartretail-dev --service smartretail-$SERVICE-dev --force-new-deployment
```

## Teardown

```bash
npx cdk destroy --all
```

RDS, S3, ECR, and CloudFront have `RemovalPolicy.DESTROY` so they are deleted on teardown.

## Key differences from cdk-min (demo stack)

| Property | cdk-min (demo) | cdk-dev |
|----------|---------------|---------|
| CPU architecture | ARM64 | X86\_64 |
| VPC | Default VPC reused | Dedicated VPC with private subnets |
| RDS | No Proxy, public subnet | RDS Proxy, isolated subnet |
| MFE hosting | S3 static website (HTTP) | CloudFront + OAC (HTTPS) |
| Auth | Internal pool only | Internal + Supplier pool |
| Services | 6 (no PPS) | 7 (includes PPS) |
| MFE buckets | 4 | 5 (includes supplier) |
