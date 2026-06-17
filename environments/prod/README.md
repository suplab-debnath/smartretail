# AWS Production Environment — Deployment Guide

> **Production deployments are intentional manual operations.** This environment is deliberately NOT wired into the Makefile. Every command below must be run explicitly.

Deploys the full SmartRetail stack to production-grade AWS infrastructure: 7 backend services + 2 Lambdas (`ml-trigger`, `batch-post-processor`), Kinesis Data Firehose → API Gateway (VPC Link) → SIS ingestion, the SageMaker demand-forecast pipeline (nightly, active), 3-AZ VPC + VPC interface endpoints, Multi-AZ RDS (r6g.large) + RDS Proxy, 3 NAT Gateways, Container Insights, `RemovalPolicy.RETAIN` on all stateful resources. Uses `environments/prod/infra/` (Prod-* stack names).

> For the full CDK stack spec and resource table see `environments/prod/infra/README.md`.

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
aws configure --profile smartretail-prod
export AWS_PROFILE=smartretail-prod
export SMARTRETAIL_ENV=prod
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=us-east-1
```

---

## First-time CDK deployment

```bash
cd environments/prod/infra
npm install

npx cdk bootstrap
npx cdk deploy --all --require-approval never
```

Stacks are deployed in dependency order: NetworkStack → DataStack → MessagingStack → IdentityStack → ComputeStack → ApiStack → HostingStack.

---

## Push service and Lambda images

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com

# All 7 ECS services (platform linux/amd64 — X86_64)
for svc in sis ims re ars dfs sup pps; do
  docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$svc-prod:latest backend/services/$svc
  docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$svc-prod:latest
  aws ecs update-service --cluster smartretail-prod --service smartretail-$svc-prod --force-new-deployment
done

# ML Trigger Lambda (starts the SageMaker pipeline on the nightly schedule)
mvn clean package -DskipTests -pl backend/adapters/ml-trigger --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-ml-trigger-prod:latest backend/adapters/ml-trigger
docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-ml-trigger-prod:latest

# Batch Post-Processor Lambda
mvn clean package -DskipTests -pl backend/adapters/batch-post-processor --no-transfer-progress
docker build --platform linux/amd64 -t $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-prod:latest backend/adapters/batch-post-processor
docker push $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-batch-post-processor-prod:latest
```

---

## Run DB migrations

```bash
AWS_PROFILE=smartretail-prod SMARTRETAIL_ENV=prod ./scripts/shared/run-flyway-aws.sh prod
```

---

## Create Cognito users

```bash
AWS_PROFILE=smartretail-prod SMARTRETAIL_ENV=prod ./scripts/shared/create-cognito-users.sh prod
```

---

## Smoke tests

```bash
make aws-smoke-test ENV=prod PROFILE=smartretail-prod
```

---

## After deployment

```bash
# API Gateway endpoint (backed by internal NLB via VPC Link)
aws cloudformation describe-stacks --stack-name Prod-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' --output text

# MFE CloudFront URLs
aws ssm get-parameters-by-path --path /smartretail/prod/hosting/ --output table

# Supplier Cognito pool
aws ssm get-parameter --name /smartretail/prod/cognito/supplier-pool-id --output text
```

---

## Iterative redeployment

```bash
# Single service (e.g. re)
AWS_PROFILE=smartretail-prod SMARTRETAIL_ENV=prod \
  ./scripts/shared/deploy-services.sh --env prod --profile smartretail-prod --services re

# All services
AWS_PROFILE=smartretail-prod SMARTRETAIL_ENV=prod \
  ./scripts/shared/deploy-services.sh --env prod --profile smartretail-prod

# MFEs
AWS_PROFILE=smartretail-prod SMARTRETAIL_ENV=prod \
  ./scripts/shared/deploy-mfes.sh --env prod --profile smartretail-prod

# CDK stack change (e.g. ComputeStack only)
cd environments/prod/infra && npx cdk deploy Prod-ComputeStack --require-approval never
```

---

## Teardown

> **Warning:** RDS, S3, and CloudFront have `RemovalPolicy.RETAIN`. CDK destroy will NOT delete them. Manual cleanup required after stack removal.

```bash
cd environments/prod/infra
npx cdk destroy --all
```

After CDK destroy:
1. Manually empty and delete each S3 bucket
2. Manually delete ECR repositories
3. Manually delete CloudFront distributions and OAC

---

## Cost estimate

Rough us-east-1, on-demand, 24×7: **~$850/month**. The largest line items are Multi-AZ RDS `r6g.large`
(~$370/mo for the two instances) + RDS Proxy, 3 NAT Gateways (~$99/mo + data), ~18 VPC interface
endpoints (~$7.3/AZ/mo each across 3 AZs), and 7 services at 2× larger Fargate tasks (Spot-weighted).
The nightly SageMaker run adds ~$0.54/run; standing ML cost is $0. Storage grows over time — S3 buckets
are versioned with multi-year lifecycle retention.

See `docs/ENVIRONMENTS.md` → **Cost & FinOps Summary** for the full demo/dev/prod comparison.

---

## See also

- `environments/prod/infra/README.md` — CDK stack architecture, sizing vs dev
- `docs/CDK_SPEC.md` — full CDK TypeScript specifications
