# AWS Dev Environment — Deployment Guide

Deploys the full SmartRetail stack to a dedicated dev-tier AWS account. Mirrors production in all service and CDK patterns — same VPC topology, RDS Proxy, CloudFront, Kinesis, and supplier Cognito pool. Differs only in sizing and compute targets.

**What's deployed:** 7 backend services + 2 Lambda functions (Kinesis consumer, Batch Post-Processor), 2-AZ VPC with dedicated private subnets, RDS Proxy → RDS t4g.small single-AZ, 5 private MFE S3 buckets with CloudFront OAC, SQS + Kinesis + EventBridge. Uses `infra/cdk-dev/` (Dev-* stack names).

> For the full CDK stack spec and resource table see `infra/cdk-dev/README.md`.

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
# Kinesis Consumer
mvn clean package -DskipTests -pl backend/lambdas/kinesis-consumer --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-kinesis-consumer-dev:latest backend/lambdas/kinesis-consumer
docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-kinesis-consumer-dev:latest

# Batch Post-Processor
mvn clean package -DskipTests -pl backend/lambdas/batch-post-processor --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-dev:latest backend/lambdas/batch-post-processor
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
# ALB endpoint
aws cloudformation describe-stacks --stack-name Dev-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`AlbEndpoint`].OutputValue' --output text

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

## See also

- `infra/cdk-dev/README.md` — CDK stack architecture, sizing vs prod
- `docs/CDK_SPEC.md` — full CDK TypeScript specifications
