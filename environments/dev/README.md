# AWS Dev Environment — Deployment Guide

Deploys the full SmartRetail stack to a dedicated dev-tier AWS account. Mirrors production in all service and CDK patterns — same VPC topology, RDS Proxy, CloudFront, Kinesis Data Firehose, SageMaker pipeline, and supplier Cognito pool. Differs only in sizing and compute targets.

**What's deployed:** 7 backend services + 2 Lambda functions (`ml-trigger`, `batch-post-processor`), Kinesis Data Firehose → API Gateway (VPC Link) → SIS ingestion, the SageMaker demand-forecast pipeline (nightly, active), 2-AZ VPC with dedicated private subnets + VPC interface endpoints, RDS Proxy → RDS t4g.small single-AZ, 5 private MFE S3 buckets with CloudFront OAC, SQS + EventBridge. Uses `environments/dev/infra/` (Dev-* stack names).

> For the full CDK stack spec and resource table see `environments/dev/infra/README.md`.

---

## Prerequisites

| Tool | Version |
|------|---------|
| AWS CLI | v2 |
| CDK CLI | `npm install -g aws-cdk` |
| Docker Desktop | latest |
| Java 21 + Maven 3.9 | for building JARs |
| Node.js 20+ | for MFE builds |

Configure your AWS profile:
```bash
aws configure --profile smartretail-dev
export AWS_PROFILE=smartretail-dev
export SMARTRETAIL_ENV=dev
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=us-east-1
```

---

## First-time deployment

### 1. Bootstrap CDK
```bash
make dev-bootstrap
```

### 2. Deploy all Dev-* CDK stacks
```bash
make dev-deploy-all
```

Stacks are deployed in dependency order: NetworkStack → DataStack → MessagingStack → IdentityStack → ComputeStack → ApiStack → HostingStack.

### 3. Build and push service + Lambda images

```bash
# All 6 ECS services
make dev-push-all

# Both Lambda images
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
# ML Trigger (starts the SageMaker pipeline on the nightly schedule)
mvn clean package -DskipTests -pl backend/adapters/ml-trigger --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-ml-trigger-dev:latest backend/adapters/ml-trigger
docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-ml-trigger-dev:latest

# Batch Post-Processor (S3 SageMaker output → DFS)
mvn clean package -DskipTests -pl backend/adapters/batch-post-processor --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-dev:latest backend/adapters/batch-post-processor
docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-dev:latest
```

### 4. Run DB migrations + seed data
```bash
make dev-migrate
```

### 5. Create Cognito test users
```bash
make dev-create-users
```

### 6. Run smoke tests
```bash
make aws-smoke-test ENV=dev PROFILE=smartretail-dev
# Expected: ✅ 19 passed ❌ 0 failed
```

---

## Iterative redeployment

| Change | Command |
|--------|---------|
| All services | `make dev-deploy-services` |
| Single service (e.g. re) | `make aws-push-re ENV=dev` |
| All MFEs | `make aws-deploy-mfes ENV=dev` |
| Single MFE | `make aws-deploy-mfe-sc-planner ENV=dev` |
| DB migration | `make dev-migrate` |
| CDK infra only | `make dev-deploy-all` |
| Lambda image | build + push as in step 3, then `aws lambda update-function-code …` |

---

## After deployment

```bash
# API Gateway endpoint (backed by internal NLB via VPC Link)
aws cloudformation describe-stacks --stack-name Dev-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text

# MFE CloudFront URLs
aws ssm get-parameters-by-path --path /smartretail/dev/hosting/ --output table
```

---

## Teardown

```bash
make dev-destroy
```

All resources have `RemovalPolicy.DESTROY` — RDS, S3, and ECR are deleted automatically.

---

## Cost estimate

Rough us-east-1, on-demand, 24×7: **~$230/month**. The main cost drivers above demo are the 1 NAT
Gateway (~$33/mo + data), ~12 VPC interface endpoints (~$7.3/AZ/mo each), RDS Proxy on top of the
`t4g.small`, and the nightly SageMaker run (~$0.54/run). ECS cost is trimmed by a FARGATE_SPOT-weighted
capacity provider. SageMaker standing cost is $0 — charges accrue only per pipeline run.

See `docs/ENVIRONMENTS.md` → **Cost & FinOps Summary** for the full demo/dev/prod comparison.

---

## See also

- `environments/dev/infra/README.md` — CDK stack architecture, sizing vs prod
- `docs/CDK_SPEC.md` — full CDK TypeScript specifications
