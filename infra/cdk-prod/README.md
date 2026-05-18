# SmartRetail CDK Prod

Production-grade SmartRetail infrastructure. Designed for real workloads with HA networking, larger compute, connection pooling, and HTTPS everywhere.

## What's different from cdk (demo)

| Component | cdk (demo) | **cdk-prod (this)** |
|-----------|------------|---------------------|
| Architecture | ARM64 | **x86_64** |
| ECS tasks / service | 1 | **2 (HA baseline)** |
| ECS CPU / Memory | 0.25 vCPU / 512 MB | **0.5 vCPU / 1 GB** |
| ECS auto-scale ceiling | 2 | **6** |
| RDS instance | t4g.micro | **t3.medium** |
| RDS mode | Single-AZ | **Multi-AZ** |
| RDS storage | 20 GB | **100 GB gp3** |
| RDS backup | 1 day | **7 days** |
| RDS Proxy | No | **Yes** |
| NAT Gateways | 0 | **2 (one per AZ)** |
| VPC Endpoints | 0 | **7 interface** |
| API routing | ALB (HTTP) | **HTTP API v2 + VPC Link** |
| MFE hosting | S3 website (HTTP) | **CloudFront (HTTPS)** |
| Cognito pools | 1 | **2** |
| CloudWatch retention | 1 week | **1 month** |
| Deletion protection | Off | **On (RDS)** |
| Survives AZ failure | No | **Yes** |
| Auth at gateway | No | **Yes (Cognito JWT)** |
| **Est. monthly cost** | **~$985** | **~$1,229** |

## Architecture

```
Internet
  │
  ├── API Gateway HTTP API (HTTPS, JWT authorizer)
  │      VPC Link → ECS Fargate x86_64 (private subnets, 2 tasks each)
  │      /v1/ingest/*        → SIS :8080
  │      /v1/inventory/*     → IMS :8081
  │      /v1/replenishment/* → RE  :8082
  │      /v1/dashboard/*     → ARS :8083
  │      /v1/forecast/*      → DFS :8084
  │      /v1/supplier/*      → SUP :8085
  │                               │
  │                        RDS Proxy → RDS t3.medium Multi-AZ (isolated subnet)
  │
  └── CloudFront (HTTPS) → private S3
       store-manager / sc-planner / executive
```

VPC: 2 AZs · public subnets · private-app subnets (ECS + NAT × 2) · isolated subnets (RDS) · 7 Interface VPC Endpoints

## Deploy

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

Stacks deployed:
1. `Prod-NetworkStack`
2. `Prod-DataStack`
3. `Prod-MessagingStack`
4. `Prod-IdentityStack`
5. `Prod-ComputeStack`
6. `Prod-ApiStack`
7. `Prod-HostingStack`

## After deploy

```bash
# API endpoint
aws cloudformation describe-stacks --stack-name Prod-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text

# MFE CloudFront URLs
aws cloudformation describe-stacks --stack-name Prod-HostingStack \
  --query 'Stacks[0].Outputs' --output table

# Health check (requires Cognito JWT)
TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_SRP_AUTH \
  --client-id <client-id> \
  --auth-parameters USERNAME=<user>,SRP_A=<srp> \
  --query 'AuthenticationResult.AccessToken' --output text)
curl -H "Authorization: Bearer $TOKEN" https://<apigw-url>/v1/inventory/health
```

## Push a service image

```bash
SERVICE=sis   # or ims, re, ars, dfs, sup
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$SERVICE-prod

aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com
docker build --platform linux/amd64 -t $REPO:latest services/$SERVICE
docker push $REPO:latest
```

## Cost breakdown (~$1,229/month at 0 traffic)

| Component | ~Cost/month |
|-----------|------------|
| ECS Fargate x86 × 6 (2 tasks, 0.5 vCPU / 1 GB) | $590 |
| VPC Link | $230 |
| 2 × NAT Gateway | $64 |
| 7 × Interface VPC Endpoint | $50 |
| RDS t3.medium Multi-AZ | $100 |
| RDS Proxy | $20 |
| CloudFront (3 MFEs) | $50 |
| Kinesis + SQS + EventBridge | $65 |
| CloudWatch Logs (1 month) | $40 |
| Cognito (2 pools) | $5 |
| S3 (4 buckets) | $5 |
| HTTP API | $10 |
| **Total** | **~$1,229** |

## Important notes

- RDS has `deletionProtection: true` and `RemovalPolicy.RETAIN` — destroying the stack will **not** delete the database. Remove deletion protection manually before teardown.
- DynamoDB and S3 events bucket also use `RemovalPolicy.RETAIN`.
- MFE S3 buckets use `RemovalPolicy.RETAIN` — delete contents manually before removal.
