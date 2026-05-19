# SmartRetail CDK

Minimal-cost SmartRetail infrastructure. Designed for development, and cost-conscious testing. All six services run end-to-end.

## Key trade-offs

- **HTTP only** — no HTTPS at the API or MFE layer. Acceptable for dev, not for prod.
- **No auth at gateway** — JWT validation happens in Spring Boot only (no API Gateway authorizer).
- **ECS tasks have public IPs** — security is enforced by security groups, not subnet isolation.
- **S3 website SPA caveat** — 404s return the error document with a `404` status (CloudFront remaps to `200`). Apps still load correctly.

## Architecture

```
Internet
  │
  ├── ALB (:80) ──── ECS Fargate tasks (public subnets, ARM64)
  │      path-based routing                │
  │      /v1/ingest/*        → SIS :8080   │
  │      /v1/inventory/*     → IMS :8081   │
  │      /v1/replenishment/* → RE  :8082   │
  │      /v1/dashboard/*     → ARS :8083   │
  │      /v1/forecast/*      → DFS :8084   │
  │      /v1/supplier/*      → SUP :8085   │
  │                                        ↓
  │                              RDS t4g.micro (isolated subnet)
  │
  └── S3 website buckets (HTTP)
       store-manager / sc-planner / executive
```

VPC: 2 AZs · public subnets (ECS + ALB) · isolated subnets (RDS only) · no NAT · free Gateway endpoints for S3/DynamoDB

## Deploy

```bash
cd infra/cdk
npm install

export SMARTRETAIL_ENV=demo        # must differ from other stack sets in the same account
export AWS_PROFILE=smartretail-dev
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=us-east-1

npx cdk bootstrap
npx cdk deploy --all --require-approval never
```

Stacks deployed (in dependency order):
1. `Demo-NetworkStack`
2. `Demo-DataStack`
3. `Demo-MessagingStack`
4. `Demo-IdentityStack`
5. `Demo-ComputeStack`
6. `Demo-ApiStack`
7. `Demo-HostingStack`

## After deploy

```bash
# API endpoint
aws cloudformation describe-stacks --stack-name Demo-ApiStack \
  --query 'Stacks[0].Outputs[?OutputKey==`AlbEndpoint`].OutputValue' --output text

# MFE URLs
aws cloudformation describe-stacks --stack-name Demo-HostingStack \
  --query 'Stacks[0].Outputs' --output table

# Health check
curl http://<alb-dns>/v1/inventory/health
```

## Push a service image

```bash
SERVICE=sis   # or ims, re, ars, dfs, sup
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REPO=$ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/smartretail-$SERVICE-demo

aws ecr get-login-password | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.us-east-1.amazonaws.com
docker build --platform linux/arm64 -t $REPO:latest services/$SERVICE
docker push $REPO:latest
```

## Cost breakdown (~$135/month at 0 traffic)

| Component                                                                   | ~Cost/month |
| --------------------------------------------------------------------------- | ----------- |
| ECS Fargate ARM64 × 6 (1 task, 0.25 vCPU / 512 MB) — 80% Spot / 20% regular | ~$22        |
| ALB                                                                         | $16         |
| Kinesis on-demand                                                           | $50         |
| SQS + EventBridge                                                           | $15         |
| RDS t4g.micro single-AZ                                                     | $13         |
| CloudWatch Logs                                                             | $15         |
| Cognito (1 pool)                                                            | $3          |
| S3 (4 buckets)                                                              | $3          |
| **Total**                                                                   | **~$137**   |

> Fargate Spot (80/20 split) reduces ECS compute cost by ~50%. Spot tasks can be interrupted with a 2-minute warning — acceptable for demo use.
> Use `cdk destroy --all` when not in use to stop all costs.

## Teardown

```bash
npx cdk destroy --all
```

RDS and S3 have `RemovalPolicy.DESTROY` so they are deleted on teardown.
