# AWS Demo Environment — SC Planner Demo Deployment

Deploys the **SC Planner demo** on real AWS infrastructure. Intended lifespan: 1–2 days. All resources are tagged `Lifecycle=ephemeral` for easy cost tracking and cleanup.

**What's deployed:** 5 backend services (IMS, RE, ARS, DFS, SUP — no SIS, no Lambda), SC Planner MFE only, SQS messaging (no Kinesis), single-AZ RDS, S3 static website hosting (no CloudFront). Uses `environments/demo/infra/` (Min-* stack names).

> For the full CDK stack spec and resource table see `environments/demo/infra/README.md`.

---

## Prerequisites

| Tool | Version |
|------|---------|
| AWS CLI | v2 |
| CDK CLI | `npm install -g aws-cdk` |
| Docker Desktop | latest |
| Java 21 + Maven 3.9 | for building JARs |
| Node.js 20+ | for MFE build |

Configure your AWS profile:
```bash
aws configure --profile smartretail-dev
export AWS_PROFILE=smartretail-dev
export SMARTRETAIL_ENV=demo
```

---

## One-shot full deployment

```bash
./environments/demo/scripts/deploy-demo.sh
```

With CloudWatch alarm email notifications:
```bash
CDK_CONTEXT_alertEmail=you@example.com ./environments/demo/scripts/deploy-demo.sh
```

---

## Granular deployment steps

If you need control over individual steps:

```bash
# 1. Bootstrap CDK (once per account/region)
make demo-bootstrap

# 2. Deploy all Min-* CDK stacks
make demo-cdk-deploy

# 3. Build and push 5 service images to ECR
make demo-push-services

# 4. Run Flyway migrations + seed data (V1–V7)
make demo-migrate

# 5. Build and deploy SC Planner MFE to S3
make demo-deploy-mfe

# 6. Create Cognito test users
make demo-create-users DEMO_ENV=demo
```

---

## Iterative redeployment

| Change | Command |
|--------|---------|
| Service code | `make demo-push-services` (rebuilds all 5 images) |
| Single service (e.g. re) | `docker buildx build … && docker push … && aws ecs update-service …` |
| MFE code | `make demo-deploy-mfe` |
| DB migration | `make demo-migrate` |
| CDK infra | `make demo-cdk-deploy` |

---

## After deployment

**SC Planner URL:**
```bash
aws ssm get-parameter --name /smartretail/demo/hosting/sc-planner-url \
  --query Parameter.Value --output text
```

**CloudWatch dashboard:**
```
https://console.aws.amazon.com/cloudwatch/home?region=us-east-1#dashboards:name=SmartRetail-demo-Ops
```

**Smoke tests:**
```bash
make aws-smoke-test ENV=demo PROFILE=smartretail-dev
```

---

## Cost and tagging

All resources are tagged:
- `Project=SmartRetail`
- `Environment=demo`
- `Lifecycle=ephemeral`

Find all demo resources in Cost Explorer or the Tag Editor by filtering on `Lifecycle=ephemeral`.

---

## Teardown

```bash
make demo-destroy
```

S3 buckets and ECR repos have `RemovalPolicy.DESTROY` — they are emptied and deleted automatically. After `demo-destroy` completes, verify in the console that no orphaned resources remain.

---

## See also

- `environments/demo/infra/README.md` — CDK stack architecture, stack names, sizing
- `docs/CDK_SPEC.md` — full CDK TypeScript specifications
