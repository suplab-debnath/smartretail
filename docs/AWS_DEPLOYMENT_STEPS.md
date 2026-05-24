# AWS Deployment — SmartRetail Demo

This document covers deploying the **cdk-demo** stack:
- 5 ECS Fargate services: IMS, RE, ARS, DFS, SUP (ARM64, FARGATE_SPOT 80%)
- EventBridge → SQS → RE / ARS (no Kinesis, no Lambda)
- RDS PostgreSQL direct connection (no RDS Proxy)
- SC Planner MFE only, served via S3 static website (HTTP)

---

## Prerequisites (one-time)

```bash
java --version        # must be 21
mvn --version         # must be 3.9.x
node --version        # must be 20.x
docker info           # must be running
cdk --version         # must be 2.x  (npm install -g aws-cdk@2)
aws --version         # must be v2

# AWS profile configured
aws sts get-caller-identity --profile smartretail-dev
# Expected: your account ID and ARN
```

## Step 1 — Set environment variables
Set these in your shell before running anything. Every subsequent command inherits them.

```bash
export AWS_PROFILE=smartretail-dev
export AWS_REGION=us-east-1
export SMARTRETAIL_ENV=dev
export CDK_DEFAULT_REGION=us-east-1
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
```

## Step 2 — Install CDK dependencies

```bash
cd infra/cdk-demo
npm install
cd ../..
```

## Step 3 — Bootstrap CDK (once per account/region)
Skip if you have already bootstrapped this account.

```bash
cd infra/cdk-demo
cdk bootstrap aws://${CDK_DEFAULT_ACCOUNT}/us-east-1
cd ../..
```

Verify bootstrap:
```bash
aws cloudformation describe-stacks --stack-name CDKToolkit
```

## Steps 4–8 — One-shot full deployment (recommended)
If you want everything deployed in a single command (CDK + images + migrations + MFE):

```bash
chmod +x scripts/deploy-demo.sh
./scripts/deploy-demo.sh
```

Then run Step 6 (create Cognito users) and Step 9 (smoke test) separately. Skip Steps 4–8 below.

---

## Step 4 — Deploy infrastructure stacks only
Deploys in dependency order: network → data → messaging → identity → compute → api → hosting → monitoring.

**CDK-only automated:**
```bash
chmod +x scripts/deploy-cdk.sh
./scripts/deploy-cdk.sh
```

**Manual (easier to debug one stack at a time):**
```bash
cd infra/cdk-demo
cdk deploy Min-NetworkStack   --require-approval never
cdk deploy Min-DataStack      --require-approval never
cdk deploy Min-MessagingStack --require-approval never
cdk deploy Min-IdentityStack  --require-approval never
cdk deploy Min-ComputeStack   --require-approval never
cdk deploy Min-ApiStack       --require-approval never
cdk deploy Min-HostingStack   --require-approval never
cdk deploy Min-MonitoringStack --require-approval never
cd ../..
```

RDS takes 5–10 minutes. Total: ~15 minutes.

> `deploy-cdk.sh` deploys stacks 1–7 (`Min-NetworkStack` through `Min-HostingStack`).
> Deploy `Min-MonitoringStack` separately if you want CloudWatch alarms.

## Step 5 — Run DB migrations
Connects to RDS via the instance endpoint stored in Parameter Store (cdk-demo has no RDS Proxy).

```bash
chmod +x scripts/run-flyway-aws-demo.sh
./scripts/run-flyway-aws-demo.sh dev
```

Runs all Flyway migrations V1–V7, including seed data from `V7__seed_data.sql`.

## Step 6 — Create Cognito test users

```bash
chmod +x scripts/create-cognito-users.sh
./scripts/create-cognito-users.sh dev
```

Creates: `store-manager-1`, `sc-planner-1`, `executive-1` (password: `Test@12345!`)

The SC Planner MFE uses `sc-planner-1` (`SC_PLANNER` group).

## Step 7 — Build and push service images to ECR
Builds 5 services as ARM64 images, pushes to ECR, and forces ECS redeployment.
(SIS is not deployed in cdk-demo. There is no Lambda — SQS-only ingestion.)

```bash
chmod +x scripts/deploy-services-demo.sh
./scripts/deploy-services-demo.sh --env dev --wait
```

`--wait` blocks until all 5 ECS services reach steady state (~3–5 min after push).

To redeploy a single service after a code change:
```bash
./scripts/deploy-services-demo.sh --env dev --services re --wait
```

## Step 8 — Deploy SC Planner MFE
Builds the sc-planner React app and syncs it to the S3 static website bucket.
(cdk-demo serves only the SC Planner MFE. No CloudFront — HTTP only.)

```bash
chmod +x scripts/deploy-mfes-demo.sh
./scripts/deploy-mfes-demo.sh --env dev --profile smartretail-dev
```

The SC Planner URL is printed at the end. You can also retrieve it from Parameter Store:
```bash
aws ssm get-parameter \
  --name /smartretail/dev/hosting/sc-planner-url \
  --query Parameter.Value --output text
```

## Step 9 — Smoke test
```bash
./scripts/smoke-test.sh all
```

## API endpoint
```bash
aws ssm get-parameter \
  --name /smartretail/dev/api/endpoint \
  --query Parameter.Value --output text
```

---

## Iterative redeployment (after code changes)

| Changed             | Command                                                                         |
| ------------------- | ------------------------------------------------------------------------------- |
| Single Java service | `./scripts/deploy-services-demo.sh --env dev --services re --wait`              |
| All 5 services      | `./scripts/deploy-services-demo.sh --env dev --wait`                            |
| CDK infra only      | `cd infra/cdk-demo && npx cdk deploy Min-ComputeStack --require-approval never` |
| SC Planner MFE only | `./scripts/deploy-mfes-demo.sh --env dev --profile smartretail-dev`             |

---

## Teardown

Full resource cleanup (CDK stacks + S3, ECR, CloudWatch logs, SSM, Cognito, security groups):
```bash
./scripts/destroy-infra.sh
```

CDK-only destroy (leaves S3/ECR data intact):
```bash
cd infra/cdk-demo
npx cdk destroy Min-MonitoringStack Min-HostingStack Min-ApiStack Min-ComputeStack \
  Min-IdentityStack Min-MessagingStack Min-DataStack Min-NetworkStack \
  --force
cd ../..
```

Delete bootstrap stack:
```bash
aws cloudformation delete-stack --stack-name CDKToolkit
```

> **Note:** The CDK bootstrap bucket (`cdk-hnb659fds-assets-<account>-<region>`) has `DeletionPolicy: Retain` — CloudFormation intentionally leaves it behind. Delete it manually:
> ```bash
> # Find the bucket
> aws s3 ls | grep cdk-
>
> # Empty then delete
> aws s3 rm s3://cdk-hnb659fds-assets-<account>-us-east-1 --recursive
> aws s3 rb s3://cdk-hnb659fds-assets-<account>-us-east-1 --force
> ```
> `destroy-infra.sh` handles this automatically.

---

## Finding All Demo/Ephemeral Resources Using Tags

1. Open the **AWS Management Console**
2. Search for and go to **Resource Groups & Tag Editor**
3. In the left menu, click on **Tag Editor**
4. Configure the search:
   - **Regions**: Select `All regions` (or the regions you use)
   - **Resource types**: Select `All supported resource types` (or filter specific services)
5. Under **Tags**, add the following filters:
   - Key: `Project` → Value: `smartretail`
   - Key: `Lifecycle` → Value: `ephemeral`
   - (Optional) Key: `Environment` → Value: `demo`
   - (Optional) Key: `Variant` → Value: `min`
6. Click **Search resources**

> **Quick Tip**: Use `Lifecycle = ephemeral` to easily find all your demo resources.

---

## Checking Usage and Cost in AWS Console

### Method A: Using Cost Explorer (Recommended)

1. Go to **AWS Billing** → **Cost Explorer**
2. Click on **Create new report** or open an existing one
3. Apply these **Filters**:
   - Tag: `Project` = `smartretail`
   - Tag: `Lifecycle` = `ephemeral`
   - (Optional) Tag: `Environment` = `demo`
4. Set **Group by**:
   - First: `Service`
   - Second: `Tag` → `Environment` or `Variant` (optional)
5. Choose the **Time range** (Last 7 days, Last 30 days, etc.)
6. Click **Apply**

### Method B: Quick Daily Check (Saved View)

1. In **Cost Explorer**, apply the filters mentioned above
2. Click **Save** → Give it a name like `SmartRetail-Demo-Ephemeral`
3. Next time, just open this saved report for instant view
