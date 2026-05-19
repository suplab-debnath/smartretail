# cdk-min — Demo / Dev Infrastructure (SQS-only)

**This is the stack you run for the demo.** Minimal footprint, no Kinesis, reuses your existing default VPC.

## What this stack deploys

| Layer | Resource |
|---|---|
| Network | Reuses existing **default VPC** (no new VPC created) |
| Data | RDS PostgreSQL t4g.micro (public subnet, SG-restricted), DynamoDB idempotency table, S3 events bucket, MFE S3 buckets |
| Messaging | `smartretail-pos-events` **SQS queue** (POS ingestion) + EventBridge bus + IMS/RE/ARS SQS queues |
| Compute | 6 ECS Fargate services (`SPRING_PROFILES_ACTIVE=dev`) — no Lambda |
| Identity | Cognito User Pool (internal users) |
| API | ALB with path-based routing to all 6 services |
| Hosting | S3 website buckets for 3 MFEs |

## First-time VPC lookup

On your first `cdk synth`, CDK contacts AWS to find the default VPC and caches
the result in `cdk.context.json`. Commit that file after the first synth.

```bash
cd infra/cdk-min
npm install
SMARTRETAIL_ENV=dev AWS_PROFILE=smartretail-dev npx cdk context   # populates cache
```

## Stack names

`Min-NetworkStack`, `Min-DataStack`, `Min-MessagingStack`, `Min-IdentityStack`,
`Min-ComputeStack`, `Min-ApiStack`, `Min-HostingStack`

## Makefile shortcuts

```bash
make dev-bootstrap       # bootstrap CDK in your account (once per account/region)
make dev-deploy-all      # deploy all Min-* stacks
make dev-push-all        # build + push service images to ECR
make dev-deploy-services # build JARs, push images, force ECS redeployment
make dev-migrate         # run Flyway migrations against RDS
make dev-create-users    # create Cognito test users
```

See `docs/CDK_SPEC.md` for full stack specifications.
