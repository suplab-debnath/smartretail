# Build Sequence
 
Exact commands in exact order. No assumptions.
Follow Local sequence for development. Follow AWS sequence for prototype demo.
 
---
 
## Local Build Sequence (first time)
 
Run once to set up the full local environment.
Subsequent runs: just `make local-up` + start the services you need.
 
```bash
# 0. Clone and enter the repository
git clone https://github.com/your-org/smartretail.git
cd smartretail
 
# 1. Install Node dependencies for MFEs and CDK
npm install --prefix infra/cdk-demo
npm install --prefix mfe/store-manager
npm install --prefix mfe/sc-planner
npm install --prefix mfe/executive
npm install --prefix mfe/shared/auth
 
# 2. Build shared auth library (MFEs depend on this)
npm run build --prefix mfe/shared/auth
 
# 3. Build Java services (creates JAR files)
mvn clean package -DskipTests \
    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup \
    -am --no-transfer-progress
 
# 4. Start Docker Compose (Postgres + LocalStack)
docker-compose up -d
 
# 5. Wait for Postgres to be ready
until docker exec smartretail-postgres pg_isready -U smartretail_admin; do
    echo "Waiting for Postgres..."
    sleep 2
done
echo "✅ Postgres ready"
 
# 6. Wait for LocalStack to be ready
until curl -s http://localhost:4566/_localstack/health | grep '"kinesis": "running"' > /dev/null; do
    echo "Waiting for LocalStack..."
    sleep 3
done
echo "✅ LocalStack ready"
 
# 7. Run Flyway schema migrations
cd migrations/flyway
mvn flyway:migrate \
    -Dflyway.url="jdbc:postgresql://localhost:5432/smartretail" \
    -Dflyway.user=smartretail_admin \
    -Dflyway.password=local_dev_password \
    -Dflyway.locations=filesystem:src/main/resources/db/migration
cd ../..
 
# 8. Load seed data
PGPASSWORD=local_dev_password psql \
    -h localhost -U smartretail_admin -d smartretail \
    -f migrations/flyway/V7__seed_data.sql
 
# Verify seed data loaded
PGPASSWORD=local_dev_password psql \
    -h localhost -U smartretail_admin -d smartretail \
    -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('sales','forecasting','inventory','replenishment','supplier','promotions') ORDER BY schema_name;"
# Expected: 6 rows
 
# 9. Verify LocalStack resources were created by init script
aws --endpoint-url=http://localhost:4566 kinesis list-streams
# Expected: {"StreamNames": ["smartretail-events-local"]}
 
aws --endpoint-url=http://localhost:4566 sqs list-queues
# Expected: 5 queue URLs
 
aws --endpoint-url=http://localhost:4566 events list-rules \
    --event-bus-name smartretail-events-local
# Expected: 3 rules
 
# 10. Start services (open 4 terminals or use tmux)
# Terminal 1:
cd services/sis && SPRING_PROFILES_ACTIVE=local \
    DB_SCHEMA=sales DB_USERNAME=sis_user \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8080"
 
# Terminal 2:
cd services/ims && SPRING_PROFILES_ACTIVE=local \
    DB_SCHEMA=inventory DB_USERNAME=ims_user \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8081"
 
# Terminal 3:
cd services/re && SPRING_PROFILES_ACTIVE=local \
    DB_SCHEMA=replenishment DB_USERNAME=re_user \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8082"
 
# Terminal 4:
cd services/ars && SPRING_PROFILES_ACTIVE=local \
    DB_SCHEMA=ars_readonly DB_USERNAME=ars_readonly \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8083"
 
# Terminal 5:
cd services/dfs && SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8084"

# Terminal 6:
cd services/sup && SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8085"

# 11. Verify all services healthy
curl http://localhost:8080/actuator/health  # SIS
curl http://localhost:8081/actuator/health  # IMS
curl http://localhost:8082/actuator/health  # RE
curl http://localhost:8083/actuator/health  # ARS
# All should return: {"status":"UP"}
 
curl http://localhost:8084/actuator/health  # DFS
curl http://localhost:8085/actuator/health  # SUP
# All should return: {"status":"UP"}

# 12. Run Flow 1 smoke test (local mode)
SMARTRETAIL_ENV=local SIS_ENDPOINT=http://localhost:8080 \
    python3 scripts/publish-pos-event.py \
    --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
    --direct-api http://localhost:8080
 
# 13. Start MFE dev servers (optional — for UI testing)
# Terminal 5:
cd mfe/store-manager && npm run dev   # http://localhost:5173
 
# Terminal 6:
cd mfe/sc-planner && npm run dev -- --port 5174   # http://localhost:5174
 
# Terminal 7:
cd mfe/executive && npm run dev -- --port 5175    # http://localhost:5175
```
 
---
 
## AWS Build Sequence (first time)
 
Run once to deploy the full prototype to AWS.
Subsequent deploys: `cdk deploy {StackName}` for individual stacks.
 
### Pre-flight checks
 
```bash
# Confirm AWS CLI profile configured
aws sts get-caller-identity --profile smartretail-dev
# Expected: account ID, ARN, user ID
 
# Confirm CDK installed
cdk --version
# Expected: 2.x.x
 
# Confirm Docker running (needed for CDK asset bundling)
docker info > /dev/null && echo "Docker OK" || echo "Docker not running"
 
# Set environment
export AWS_PROFILE=smartretail-dev
export AWS_REGION=us-east-1
export SMARTRETAIL_ENV=dev
```
 
### Step 1: Bootstrap CDK
 
Run once per AWS account/region:
 
```bash
cd infra/cdk-demo
npm install
cdk bootstrap aws://$(aws sts get-caller-identity --query Account --output text)/us-east-1
```
 
### Step 2: Build all artifacts
 
```bash
# Build Java JARs (needed for CDK to package Lambda and seed task)
mvn clean package -DskipTests \
    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup,lambdas/kinesis-consumer \
    -am --no-transfer-progress
 
# Verify JARs created
ls -la services/sis/target/*.jar
ls -la services/ims/target/*.jar
ls -la services/re/target/*.jar
ls -la services/ars/target/*.jar
ls -la lambdas/kinesis-consumer/target/*.jar
 
# Build MFEs
cd mfe/shared/auth && npm run build && cd ../../..
cd mfe/store-manager && npm run build && cd ../..
cd mfe/sc-planner && npm run build && cd ../..
cd mfe/executive && npm run build && cd ../..
```
 
### Step 3: Deploy NetworkStack
 
```bash
cd infra/cdk-demo
cdk deploy NetworkStack --require-approval never
 
# Verify outputs in Parameter Store
aws ssm get-parameter --name /smartretail/dev/network/vpc-id --query Parameter.Value --output text
aws ssm get-parameter --name /smartretail/dev/network/sg-ecs-tasks-id --query Parameter.Value --output text
```
 
### Step 4: Deploy DataStack
 
```bash
cdk deploy DataStack --require-approval never
 
# Wait for RDS to be available (takes 5-10 minutes)
RDS_ID=$(aws ssm get-parameter --name /smartretail/dev/rds/instance-id --query Parameter.Value --output text)
aws rds wait db-instance-available --db-instance-identifier $RDS_ID
echo "✅ RDS available"
 
# Verify RDS Proxy endpoint
aws ssm get-parameter --name /smartretail/dev/rds/proxy-endpoint --query Parameter.Value --output text
```
 
### Step 5: Run Flyway Migrations
 
Migrations run from a local machine connecting to RDS via a bastion or
from a one-off ECS task. Use the ECS task approach for security:
 
```bash
# scripts/run-flyway-aws.sh handles this automatically
chmod +x scripts/run-flyway-aws.sh
./scripts/run-flyway-aws.sh dev
```
 
`scripts/run-flyway-aws.sh` content:
```bash
#!/bin/bash
# Runs Flyway migrations as a one-off ECS Fargate task
ENV=${1:-dev}
CLUSTER="smartretail-${ENV}"
 
# Get RDS credentials from Secrets Manager
SECRET_ARN=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/rds/secret-arn" \
    --query Parameter.Value --output text)
 
RDS_ENDPOINT=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/rds/proxy-endpoint" \
    --query Parameter.Value --output text)
 
# Run Flyway as a local Maven command connecting to RDS via SSH tunnel
# (For prototype simplicity — production would use an ECS task)
DB_PASSWORD=$(aws secretsmanager get-secret-value \
    --secret-id "$SECRET_ARN" \
    --query SecretString --output text | python3 -c "import json,sys; print(json.load(sys.stdin)['password'])")
 
cd migrations/flyway
mvn flyway:migrate \
    -Dflyway.url="jdbc:postgresql://${RDS_ENDPOINT}:5432/smartretail" \
    -Dflyway.user=smartretail_admin \
    -Dflyway.password="$DB_PASSWORD" \
    -Dflyway.locations=filesystem:src/main/resources/db/migration
 
echo "✅ Migrations complete"
```
 
### Step 6: Deploy MessagingStack
 
```bash
cdk deploy MessagingStack --require-approval never
 
# Verify EventBridge bus created
aws events list-event-buses | grep smartretail-events-dev
 
# Verify SQS queues created
aws sqs list-queues --queue-name-prefix smartretail
```
 
### Step 7: Deploy IdentityStack
 
```bash
cdk deploy IdentityStack --require-approval never
 
# Verify Cognito pools
aws cognito-idp list-user-pools --max-results 10 | grep smartretail
```
 
### Step 8: Create Cognito Test Users
 
```bash
chmod +x scripts/create-cognito-users.sh
./scripts/create-cognito-users.sh dev
```
 
`scripts/create-cognito-users.sh`:
```bash
#!/bin/bash
ENV=${1:-dev}
 
INTERNAL_POOL=$(aws ssm get-parameter \
    --name "/smartretail/${ENV}/cognito/internal-pool-id" \
    --query Parameter.Value --output text)
 
create_user() {
    local pool=$1 username=$2 email=$3 password=$4 group=$5
    aws cognito-idp admin-create-user \
        --user-pool-id "$pool" \
        --username "$username" \
        --user-attributes Name=email,Value="$email" Name=email_verified,Value=true \
        --message-action SUPPRESS 2>/dev/null || true
    aws cognito-idp admin-set-user-password \
        --user-pool-id "$pool" \
        --username "$username" \
        --password "$password" \
        --permanent
    aws cognito-idp admin-add-user-to-group \
        --user-pool-id "$pool" \
        --username "$username" \
        --group-name "$group"
    echo "✅ Created $username ($group)"
}
 
create_user "$INTERNAL_POOL" "store-manager-1" "sm1@test.com"  "Test@12345!" "STORE_MANAGER"
create_user "$INTERNAL_POOL" "sc-planner-1"    "scp1@test.com" "Test@12345!" "SC_PLANNER"
create_user "$INTERNAL_POOL" "executive-1"     "exec1@test.com" "Test@12345!" "EXECUTIVE"
 
echo "✅ All test users created"
```
 
### Step 9: Deploy ComputeStack
 
```bash
cdk deploy ComputeStack --require-approval never
 
# Wait for ECS services to stabilize (takes 3-5 minutes)
for svc in sis ims re ars dfs sup; do
    aws ecs wait services-stable \
        --cluster smartretail-dev \
        --services smartretail-${svc}-dev
    echo "✅ ${svc} service stable"
done
```
 
### Step 10: Deploy ApiStack
 
```bash
cdk deploy ApiStack --require-approval never
 
# Get API endpoint
API_ENDPOINT=$(aws ssm get-parameter \
    --name /smartretail/dev/api-gateway/endpoint \
    --query Parameter.Value --output text)
echo "API Endpoint: $API_ENDPOINT"
 
# Verify health checks
curl "${API_ENDPOINT}/actuator/health" 2>/dev/null | python3 -m json.tool
```
 
### Step 11: Deploy MFEs to S3 + CloudFront
 
```bash
# Generate config.js for each MFE
./scripts/generate-mfe-config.sh dev
 
# Deploy each MFE
for mfe in store-manager sc-planner executive; do
    BUCKET=$(aws ssm get-parameter \
        --name "/smartretail/dev/s3/mfe-${mfe}-bucket" \
        --query Parameter.Value --output text)
    CF_ID=$(aws ssm get-parameter \
        --name "/smartretail/dev/cloudfront/mfe-${mfe}-id" \
        --query Parameter.Value --output text)
 
    # Upload config.js first
    aws s3 cp "scripts/config-${mfe}.js" "s3://${BUCKET}/config.js" \
        --cache-control "no-cache, no-store"
 
    # Sync build output
    aws s3 sync "mfe/${mfe}/dist/" "s3://${BUCKET}/" \
        --delete \
        --cache-control "max-age=31536000" \
        --exclude "config.js"
 
    # Invalidate CloudFront cache
    aws cloudfront create-invalidation \
        --distribution-id "$CF_ID" \
        --paths "/*"
 
    echo "✅ ${mfe} MFE deployed"
done
```
 
### Step 12: Load Seed Data
 
```bash
# Run seed data migration
./scripts/run-flyway-aws.sh dev
# (This runs V7__seed_data.sql which is idempotent)
```
 
### Step 13: Run Smoke Tests
 
```bash
export SMARTRETAIL_ENV=dev
export AWS_PROFILE=smartretail-dev
./scripts/smoke-test.sh all
```
 
Expected output:
```
SmartRetail Prototype Smoke Tests
ENV: dev
 
--- Flow 1: POS Event Ingestion ---
✅ 1.5 RDS sales_events row created
✅ 1.3 DynamoDB idempotency key written
✅ 1.8 IMS inventory_positions updated
✅ 1.9 Stock alert created
✅ 1.6 Duplicate event rejected with 409
 
--- Flow 2: RE PO Generation ---
✅ 2a.4 Auto-approve PO created (APPROVED)
✅ 2b.1 Manual PO created (PENDING_APPROVAL)
 
--- Flow 3: SC Planner Approval ---
✅ 3a.1 Approve returns 200
✅ 3a.5 RDS workflow_status = APPROVED
✅ 3c STORE_MANAGER role rejected with 403
✅ 3d Wrong status returns 409
 
--- Flow 4: Store Manager Dashboard API ---
✅ 4.1 Dashboard API returns 200
✅ 4.8 dataFreshness present in response
✅ 4.6 Dashboard shows alert counts > 0
 
--- Flow 8: Executive Dashboard API ---
✅ 8.1 Executive dashboard returns 200
✅ 8.2 MAPE history has 30 data points
✅ 8.5 EXECUTIVE cannot access SC Planner (403)
 
--- Flow 9: Supplier Performance Scorecard ---
✅ 9.1 Supplier performance returns 200
✅ 9.1 5 suppliers in response
 
─────────────────────────────────
Results: ✅ 19 passed  ❌ 0 failed
─────────────────────────────────
```
 
---
 
## Iterative Redeploy (subsequent changes)
 
After the initial full deploy, use targeted redeployment:
 
```bash
# Redeploy a single service (after code change)
mvn clean package -DskipTests -pl services/re -am
cdk deploy ComputeStack --require-approval never
 
# Or redeploy just the ECS service (faster — skips CDK diff)
# Build and push Docker image, then force new ECS deployment
docker buildx build --platform linux/arm64 -t smartretail-re:latest services/re/
docker tag smartretail-re:latest {account}.dkr.ecr.us-east-1.amazonaws.com/smartretail-re-dev:latest
aws ecr get-login-password | docker login --username AWS --password-stdin {account}.dkr.ecr.us-east-1.amazonaws.com
docker push {account}.dkr.ecr.us-east-1.amazonaws.com/smartretail-re-dev:latest
aws ecs update-service --cluster smartretail-dev --service smartretail-re-dev --force-new-deployment
 
# Redeploy a single MFE (after UI change)
cd mfe/sc-planner
npm run build
BUCKET=$(aws ssm get-parameter --name /smartretail/dev/s3/mfe-sc-planner-bucket --query Parameter.Value --output text)
aws s3 sync dist/ s3://${BUCKET}/ --delete
```
 
---
 
## Teardown (destroy all AWS resources)
 
```bash
# Destroy all stacks (in reverse order)
cdk destroy ApiStack ComputeStack IdentityStack MessagingStack DataStack NetworkStack \
    --force \
    --profile smartretail-dev
 
# Note: S3 buckets with RETAIN policy must be emptied and deleted manually
aws s3 rb s3://smartretail-events-dev-{account} --force
```
