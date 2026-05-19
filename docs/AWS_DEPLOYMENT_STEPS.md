# AWS Deployment — SmartRetail

## Prerequisites (one-time)

```bash
# Tools needed
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
cd infra/cdk
npm install
cd ../..
```

## Step 3 — Bootstrap CDK (once per account/region)
Skip if you have already bootstrapped this account.

```bash
cd infra/cdk
cdk bootstrap aws://${CDK_DEFAULT_ACCOUNT}/us-east-1
cd ../..
```

Verify bootstrap:
```bash
aws cloudformation describe-stacks --stack-name CDKToolkit
```

## Step 4 — Deploy all infrastructure stacks
This deploys in dependency order: network → data → messaging → identity → compute → api → hosting.

```
cd infra/cdk
cdk deploy --all --require-approval never
cd ../..
```

RDS takes 5–10 minutes. The rest is fast. Total: ~15 minutes.
> If you prefer to deploy one stack at a time (easier to debug):

```bash
cdk deploy NetworkStack   --require-approval never
cdk deploy DataStack      --require-approval never
cdk deploy MessagingStack --require-approval never
cdk deploy IdentityStack  --require-approval never
cdk deploy ComputeStack   --require-approval never
cdk deploy ApiStack       --require-approval never
cdk deploy HostingStack   --require-approval never
```

## Step 5 — Run DB migrations

```bash
chmod +x scripts/run-flyway-aws.sh
./scripts/run-flyway-aws.sh dev
```

This connects to RDS via the proxy endpoint stored in Parameter Store, runs all Flyway migrations, and loads the seed data from V7__seed_data.sql.

## Step 6 — Create Cognito test users

```bash
chmod +x scripts/create-cognito-users.sh
./scripts/create-cognito-users.sh dev
```
Creates: `store-manager-1` , `sc-planner-1` , `executive-1`


## Step 7 — Build and push service images to ECR
This builds all 6 services + the Lambda as ARM64 images and pushes them to ECR, then forces ECS redeployment.
```bash
chmod +x scripts/deploy-services.sh
./scripts/deploy-services.sh --env dev --wait
```

`--wait` blocks until all 6 ECS services reach steady state (~3–5 min after push). Drop it if you want to continue and check later.

To redeploy a single service after a code change:
```bash
./scripts/deploy-services.sh --env dev --services re --wait
```

## Step 8 — Deploy MFEs

```bash
# Generate runtime config pointing at the real API + Cognito pool
./scripts/generate-mfe-config.sh dev

chmod +x scripts/deploy-mfes.sh
./scripts/deploy-mfes.sh --env dev --profile smartretail-dev
```
This builds each React app, syncs to S3, and invalidates CloudFront.

## Step 9 — Smoke test
```bash
./scripts/smoke-test.sh all
```

Expected: `✅ 19 passed ❌ 0 failed`

## API endpoint
```bash
aws ssm get-parameter \
  --name /smartretail/dev/api/endpoint \
  --query Parameter.Value --output text
```


## Iterative redeployment (after code changes)
| Changed             | Command                                                                                             |
| ------------------- | --------------------------------------------------------------------------------------------------- |
| Single Java service | ./scripts/deploy-services.sh --env dev --services sis --wait                                        |
| Lambda only         | ./scripts/deploy-services.sh --env dev --no-lambda → wrong flag, use --services without --no-lambda |
| All services        | ./scripts/deploy-services.sh --env dev --wait                                                       |
| CDK infra only      | cd infra/cdk-min && npx cdk deploy Min-ComputeStack --require-approval never                        |
| MFE only            | ./scripts/deploy-mfes.sh --env dev --profile smartretail-dev                                        |


## Teardown
```bash
./scripts/destroy-infra.sh
```
Or just the CDK stacks (leaves S3/ECR data):
```bash
# Destroy app stacks
cd infra/cdk-min && npx cdk destroy --all --force
```

Delete bootstrap stack:
```bash
aws cloudformation delete-stack --stack-name CDKToolkit

# Verify
aws cloudformation describe-stacks --stack-name CDKToolkit
```