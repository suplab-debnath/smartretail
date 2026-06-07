# AWS Demo Environment — SC Planner Demo Deployment

Deploys the **SC Planner demo** on real AWS infrastructure. Intended lifespan: 1–2 days. All resources are tagged `Lifecycle=ephemeral` for easy cost tracking and cleanup.

**What's deployed:** 5 backend services (IMS, RE, ARS, DFS, SUP — no SIS, no Lambda), SC Planner MFE only, REST API Gateway + internal NLB, SQS + EventBridge messaging, single-AZ RDS, CloudFront + S3 (OAC) for MFE hosting, Cognito for auth. Uses `environments/demo/infra/` (Min-* stack names).

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

Use this sequence for a first-time deploy. **Do not run `make demo-cdk-deploy` on its own for a first deploy** — it deploys all stacks at once, which causes ECS to fail because the ECR repos are empty and the circuit breaker fires.

```bash
# 1. Bootstrap CDK (once per account/region)
make demo-bootstrap

# 2a. Deploy pre-compute stacks only (creates ECR repos, RDS, Cognito, SQS)
cd environments/demo/infra && AWS_PROFILE=smartretail-dev SMARTRETAIL_ENV=demo \
  npx cdk deploy Min-NetworkStack Min-DataStack Min-MessagingStack Min-IdentityStack \
  --require-approval never

# 2b. Build and push 5 service images — ECR repos now exist
cd ../../..
make demo-push-services DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

# 2c. Deploy remaining stacks (ECS can now pull images successfully)
cd environments/demo/infra && AWS_PROFILE=smartretail-dev SMARTRETAIL_ENV=demo \
  npx cdk deploy Min-ComputeStack Min-ApiStack Min-HostingStack Min-MonitoringStack \
  --require-approval never

# 3. Build and push Flyway image, then run migrations (V1–V9)
make demo-push-flyway DEMO_ENV=demo DEMO_PROFILE=smartretail-dev
make demo-migrate DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

# 4. Build and deploy SC Planner MFE to S3
make demo-deploy-mfe DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

# 5. Create Cognito test users
make demo-create-users DEMO_ENV=demo
```

> If step 2c fails with a circuit breaker error, `Min-ComputeStack` will be in `ROLLBACK_COMPLETE` and must be deleted before retrying:
> ```bash
> AWS_PROFILE=smartretail-dev aws cloudformation delete-stack --stack-name Min-ComputeStack
> AWS_PROFILE=smartretail-dev aws cloudformation wait stack-delete-complete --stack-name Min-ComputeStack
> ```

---

## Iterative redeployment

`make demo-cdk-deploy` (deploys all stacks) is safe for subsequent updates once images exist in ECR.

| Change | Command |
|--------|---------|
| Service code | `make demo-deploy-services` (build + push + force ECS redeploy) |
| Single service (e.g. re) | `docker buildx build … && docker push … && aws ecs update-service …` |
| Flyway image | `make demo-push-flyway` |
| MFE code | `make demo-deploy-mfe` |
| DB migration | `make demo-migrate` |
| Reset DB between runs | `make demo-reset-db` |
| Rebuild + redeploy services | `make demo-deploy-services` |
| CDK infra only | `make demo-cdk-deploy` |

---

## Overnight cost saving — stop and start

Scale ECS to zero and stop RDS without destroying any infrastructure or data.

```bash
# Stop everything before you leave (saves ~$0.90/night for 9 hours)
make demo-stop

# Resume the next morning
make demo-start
```

**What gets stopped:**

| Resource | Action | Resumes in |
|----------|--------|------------|
| ECS Fargate tasks (×5) | Desired count → 0 | ~30 s |
| RDS `t4g.micro` | `stop-db-instance` | ~2 min |

**What keeps running (serverless / no idle cost):**

| Resource | Idle cost |
|----------|-----------|
| NLB | ~$0.008/hr — unavoidable without destroying it |
| CloudFront, API Gateway, SQS, Cognito, S3 | $0 at idle |

> RDS will auto-start after 7 days if you forget — AWS enforces this limit on stopped instances.

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

### Monthly cost estimate (us-east-1, 30 days, no free tier)

Here's the full breakdown pulled directly from all 7 demo CDK stacks:

| Service | Config | $/month |
|---------|--------|---------|
| RDS | t4g.micro, PostgreSQL 16, 20 GB, single-AZ | ~$14 |
| ECS Fargate | 5 tasks × 0.25 vCPU / 0.5 GB, ARM64, on-demand | ~$36 |
| NLB | 1 internal NLB, 5 listeners, low traffic | ~$7 |
| CloudWatch | 1 dashboard, 6 alarms, 5 log groups | ~$4 |
| Secrets Manager | 1 secret (RDS password) | ~$0.40 |
| API Gateway (REST) | Low demo traffic (~100k calls) | ~$0.50 |
| ECR | 5 repos, ~1 GB images | ~$0.05 |
| S3 / SQS / EventBridge / Cognito / SNS / SSM | Minimal usage, within free tiers | ~$0.50 |
| **Total** | | **~$63/month** |

**Key points:**
- RDS + Fargate = 79% of the bill. Pure on-demand FARGATE trades ~$19/month in savings for reliable deployments — worth it for a 1-2 day demo.
- No NAT Gateway — tasks use public IPs in the default VPC, saving ~$32/month vs a private-subnet setup.
- At ~$2.10/day, a 2-day demo costs ~$4.20. **Run `make demo-destroy` after every demo session.**
- Running `make demo-stop` each evening (9 h off) cuts RDS + Fargate cost by ~37%, saving ~$0.60/night.

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
