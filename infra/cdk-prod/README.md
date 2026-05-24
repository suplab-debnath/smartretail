# cdk-prod — Production Infrastructure

Production-grade SmartRetail infrastructure. Full footprint with Multi-AZ RDS, RDS Proxy, 3-AZ VPC, CloudFront, container insights, and all production safeguards.

## Stack names

`Prod-NetworkStack`, `Prod-DataStack`, `Prod-MessagingStack`, `Prod-IdentityStack`,
`Prod-ComputeStack`, `Prod-ApiStack`, `Prod-HostingStack`

## What this stack deploys

| Layer | Resource |
|-------|----------|
| Network | New VPC (3 AZs, public + private-app + isolated subnets, 3 NAT Gateways, 6 interface VPC endpoints) |
| Data | RDS PostgreSQL r6g.large Multi-AZ (via RDS Proxy), DynamoDB idempotency table, versioned S3 events bucket, 5 private MFE S3 buckets |
| Messaging | Kinesis stream (POS ingestion) + EventBridge bus + IMS/RE/ARS SQS queues |
| Identity | Internal Cognito pool (STORE\_MANAGER / SC\_PLANNER / EXECUTIVE) + Supplier Cognito pool (SUPPLIER\_ADMIN) |
| Compute | 7 ECS Fargate services (X86\_64, 0.5 vCPU / 1 GB, FARGATE\_SPOT 80/20, desiredCount 2) + Kinesis consumer Lambda (X86\_64) + Container Insights |
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
  │                          RDS Proxy → RDS r6g.large Multi-AZ (isolated subnet)
  │
  └── CloudFront (HTTPS) ──── private S3 buckets (OAC)
       store-manager / sc-planner / executive / supplier / demo
```

VPC: 3 AZs · public subnets (ALB) · private-app subnets (ECS + Lambda) · isolated subnets (RDS Proxy + RDS) · 3 NAT Gateways · free Gateway endpoints (S3, DynamoDB) · interface endpoints (ECR API, ECR Docker, SQS, EventBridge, CloudWatch Logs, Secrets Manager)

## Sizing vs dev

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

## Important

This CDK project is **not wired into the Makefile** — production deployments are intentional manual operations. Deploy from the directory directly:

```bash
cd infra/cdk-prod
npm install

export SMARTRETAIL_ENV=prod
export AWS_PROFILE=smartretail-prod
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=us-east-1

npx cdk bootstrap
npx cdk deploy --all --require-approval never
```

Stacks deployed in dependency order:
1. `Prod-NetworkStack`
2. `Prod-DataStack`
3. `Prod-MessagingStack`
4. `Prod-IdentityStack`
5. `Prod-ComputeStack`
6. `Prod-ApiStack`
7. `Prod-HostingStack`

## After deploy

```bash
# API endpoint (ALB)
aws cloudformation describe-stacks --stack-name Prod-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`AlbEndpoint`].OutputValue' --output text

# MFE CloudFront URLs
aws ssm get-parameters-by-path --path /smartretail/prod/hosting/ --output table

# Supplier Cognito pool
aws ssm get-parameter --name /smartretail/prod/cognito/supplier-pool-id --output text
```

## Push a service image

```bash
SERVICE=pps   # sis | ims | re | ars | dfs | sup | pps
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$SERVICE-prod

aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com
docker build --platform linux/amd64 -t $REPO:latest backend/services/$SERVICE
docker push $REPO:latest
aws ecs update-service --cluster smartretail-prod --service smartretail-$SERVICE-prod --force-new-deployment
```

## Teardown

**Not recommended for production.** If needed:

```bash
npx cdk destroy --all
```

RDS, S3, and CloudFront have `RemovalPolicy.RETAIN` — they must be manually emptied and deleted after the CDK stacks are removed.

See `docs/CDK_SPEC.md` for full stack specifications.
