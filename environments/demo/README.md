# AWS Demo Environment — SC Planner Demo Deployment

Deploys the **SC Planner demo** on real AWS infrastructure. Intended lifespan: 1–2 days. All resources are tagged `Lifecycle=ephemeral` for easy cost tracking and cleanup.

**What's deployed:** 6 backend services (SIS, IMS, RE, ARS, DFS, SUP), Kinesis Data Firehose → API GW → SIS ingestion pipeline, S3 events + SageMaker buckets, batch-post-processor Lambda, ml-trigger Lambda (daily schedule, disabled), SageMaker demand-forecast pipeline definition (dormant, $0 standing cost), SC Planner MFE only, REST API Gateway + internal NLB, SQS + EventBridge messaging, single-AZ RDS, CloudFront + S3 (OAC) for MFE hosting, Cognito for auth. Uses `environments/demo/infra/` (Min-* stack names).

> For the full CDK stack spec and resource table see `environments/demo/infra/README.md`.

---

## Prerequisites

| Tool                | Version                  |
| ------------------- | ------------------------ |
| AWS CLI             | v2                       |
| CDK CLI             | `npm install -g aws-cdk` |
| Docker Desktop      | latest                   |
| Java 21 + Maven 3.9 | for building JARs        |
| Node.js 20+         | for MFE build            |

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

# 2b. Build and push 6 service images + 2 Lambda images — ECR repos now exist
cd ../../..
make demo-push-services DEMO_ENV=demo DEMO_PROFILE=smartretail-dev
make demo-push-lambda DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

# 2c. Deploy remaining stacks (ECS can now pull images successfully)
cd environments/demo/infra && AWS_PROFILE=smartretail-dev SMARTRETAIL_ENV=demo \
  npx cdk deploy Min-ComputeStack Min-ApiStack Min-HostingStack Min-MonitoringStack \
  --require-approval never

# 3. Build and push Flyway image, then run migrations (V1–V9)
make demo-push-flyway DEMO_ENV=demo DEMO_PROFILE=smartretail-dev
make demo-migrate DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

# 3a. Reset database between demo runs
make demo-reset-db DEMO_ENV=demo DEMO_PROFILE=smartretail-dev

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

| Change                      | Command                                                              |
| --------------------------- | -------------------------------------------------------------------- |
| Service code                | `make demo-deploy-services` (build + push + force ECS redeploy)      |
| Single service (e.g. re)    | `docker buildx build … && docker push … && aws ecs update-service …` |
| Lambda images               | `make demo-push-lambda`                                              |
| Flyway image                | `make demo-push-flyway`                                              |
| MFE code                    | `make demo-deploy-mfe`                                               |
| DB migration                | `make demo-migrate`                                                  |
| Reset DB between runs       | `make demo-reset-db`                                                 |
| Rebuild + redeploy services | `make demo-deploy-services`                                          |
| CDK infra only              | `make demo-cdk-deploy`                                               |

---

## Overnight cost saving — stop and start

Scale ECS to zero and stop RDS without destroying any infrastructure or data.

```bash
# Stop everything before you leave (saves ~$1.90/night for ~9 hours)
make demo-stop

# Resume the next morning
make demo-start
```

**What gets stopped:**

| Resource               | Action             | Resumes in |
| ---------------------- | ------------------ | ---------- |
| ECS Fargate tasks (×6) | Desired count → 0  | ~30 s      |
| RDS `t4g.micro`        | `stop-db-instance` | ~2 min     |

**What keeps running (serverless / no idle cost):**

| Resource                                                    | Idle cost                                      |
| ----------------------------------------------------------- | ---------------------------------------------- |
| NLB                                                         | ~$0.0225/hr — unavoidable without destroying it |
| CloudFront, API Gateway, SQS, Cognito, S3, Firehose, Lambda | $0 at idle                                     |

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

| Service                                 | Config                                                     | $/month        |
| --------------------------------------- | ---------------------------------------------------------- | -------------- |
| ECS Fargate                             | 6 tasks × 0.25 vCPU / 0.5 GB, ARM64 (Graviton2), on-demand | ~$43           |
| NLB                                     | 1 internal NLB ($0.0225/hr base + minimal LCU)             | ~$17           |
| RDS                                     | t4g.micro, PostgreSQL 16, 20 GB, single-AZ, no backups     | ~$14           |
| CloudWatch                              | 1 dashboard, 6 alarms, 6 log groups (2-wk retention)       | ~$4            |
| Secrets Manager                         | 2 secrets (RDS password + Firehose access key)             | ~$0.80         |
| API Gateway (REST)                      | Low demo traffic (~100k calls)                             | ~$0.50         |
| Kinesis Data Firehose                   | Low event volume (<1 GB/month)                             | ~$0.03         |
| S3                                      | events bucket + SageMaker bucket, minimal data             | ~$0.05         |
| ECR                                     | 8 repos (6 services + 2 Lambdas), ~1.5 GB images           | ~$0.15         |
| Lambda                                  | 2 functions, throttled (`reservedConcurrentExecutions: 0`) | ~$0.00         |
| SageMaker pipeline definition           | `CfnPipeline`, cron disabled (`enabled: false`)            | $0.00          |
| SQS / EventBridge / Cognito / SNS / SSM | Minimal usage, within free tiers                           | ~$0.50         |
| **Total**                               |                                                            | **~$80/month** |

**Key points:**
- ECS Fargate + NLB + RDS = ~92% of the bill. The NLB (~$17/mo) is the one cost that cannot drop to zero without destroying it — it bills hourly even when ECS is scaled to zero.
- No NAT Gateway — tasks use public IPs in the default VPC, saving ~$33/month vs a private-subnet setup.
- The stack is **ephemeral**. At ~$2.65/day, a 2-day demo run costs ~$5 (less with overnight pause). **Run `make demo-destroy` after every demo session.**
- Running `make demo-stop` each evening scales ECS to 0 and stops RDS — saving ~$1.90/night across the ~9 idle hours.
- SageMaker `CfnPipeline` is deployed but the EventBridge cron is disabled and the ml-trigger Lambda is throttled to zero. Zero standing cost — charges only occur when `StartPipelineExecution` is called (~$0.54/run: training ~$0.48 + transform ~$0.06). To activate: `aws events enable-rule --name smartretail-ml-trigger-daily-demo` **and** remove the `reservedConcurrentExecutions: 0` on the Lambda (set `enabled: true` in CDK and redeploy `Min-ApiStack`).

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
