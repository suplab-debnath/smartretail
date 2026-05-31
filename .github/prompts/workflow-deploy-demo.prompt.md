---
mode: 'agent'
description: 'Workflow: Deploy SmartRetail to the demo AWS environment -- CDK stacks, services, MFEs, migrations, smoke test'
tools: ['codebase', 'runCommand', 'terminalLastCommand', 'workspaceDetails']
---

Deploy the SmartRetail demo environment to AWS.

## Prerequisites
Confirm before proceeding:
1. `AWS_PROFILE` is set to a profile with sufficient permissions
2. `SMARTRETAIL_ENV=demo` is set
3. The demo CDK stacks are in `environments/demo/infra/`
4. Docker is running (for Maven build)

## Deployment phases

### Phase 1: Build all artefacts
```bash
make build-all
# Builds: all Maven services (JAR), all MFE npm bundles
# Verify: no compilation errors, all tests green
```

### Phase 2: Bootstrap CDK (first time only)
```bash
make aws-bootstrap
# Runs: cdk bootstrap in the target AWS account/region
```

### Phase 3: Deploy CDK stacks (in order)
```bash
cd environments/demo/infra
npx cdk deploy Min-NetworkStack --require-approval never
npx cdk deploy Min-DataStack --require-approval never
npx cdk deploy Min-MessagingStack --require-approval never
npx cdk deploy Min-IdentityStack --require-approval never
npx cdk deploy Min-ComputeStack --require-approval never
npx cdk deploy Min-ApiStack --require-approval never
```
Capture outputs: API Gateway URL, RDS endpoint, Cognito User Pool ID.

### Phase 4: Run database migrations
```bash
make aws-migrate
# Runs Flyway V1-V7 against RDS via the migration script
```

### Phase 5: Create Cognito users
```bash
make aws-create-users
# Runs scripts/shared/create-cognito-users.sh
# Creates: store_manager, sc_planner, supplier_admin, admin test users
```

### Phase 6: Deploy services
```bash
bash scripts/demo/deploy-services-demo.sh
# Pushes Docker images to ECR and updates ECS services
```

### Phase 7: Deploy MFEs
```bash
bash scripts/demo/deploy-mfes-demo.sh
# Builds MFE bundles with AWS env vars and deploys to S3/ALB
```

### Phase 8: Smoke test
```bash
make aws-smoke-test
# Expected: 19 passed, 0 failed
```

## Rollback
If smoke test fails:
1. Check CloudWatch Logs `/smartretail/{service}/demo` for errors
2. Check ECS service events for task failures
3. If a service is broken: re-deploy that service only
4. If migration failed: run `flyway repair` and re-apply

## Your task
Execute the deployment for target: ${input:target}
(e.g. `full demo deploy`, `services only`, `CDK stack update for Min-MessagingStack`)

Report: which phases succeeded, which failed, and any remediation taken.
