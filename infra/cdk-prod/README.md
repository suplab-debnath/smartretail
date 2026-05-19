# cdk-prod — Production Infrastructure (Kinesis)

**This stack is for production only.** Full footprint with Kinesis for POS event ingestion, dedicated VPC, and all production-grade safeguards.

## What this stack deploys

| Layer | Resource |
|---|---|
| Network | New VPC (3 AZs, public + private + data subnets, NAT Gateways) |
| Data | RDS PostgreSQL (private subnet, RDS Proxy), DynamoDB idempotency table, S3 events bucket, MFE S3 buckets |
| Messaging | Kinesis stream (POS ingestion) + EventBridge bus + IMS/RE/ARS SQS queues |
| Compute | 6 ECS Fargate services + Kinesis consumer Lambda (`SPRING_PROFILES_ACTIVE=aws`) |
| Identity | Cognito User Pool |
| API | ALB with path-based routing to all 6 services |
| Hosting | S3 + CloudFront for 3 MFEs |

## Stack names

`Prod-NetworkStack`, `Prod-DataStack`, `Prod-MessagingStack`, `Prod-IdentityStack`,
`Prod-ComputeStack`, `Prod-ApiStack`, `Prod-HostingStack`

## Important

This CDK project is **not wired into the Makefile** — production deployments are intentional manual operations. Deploy from the directory directly:

```bash
cd infra/cdk-prod
npm install
SMARTRETAIL_ENV=prod AWS_PROFILE=<prod-profile> npx cdk deploy --all --require-approval never
```

See `docs/CDK_SPEC.md` for full stack specifications.
