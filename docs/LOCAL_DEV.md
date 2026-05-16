# Local Development Guide
 
Two modes: LOCAL (Docker + LocalStack) and AWS (real AWS account).
Switch between them using Spring profiles and environment variables.
No code changes required to switch modes.
 
---
 
## Prerequisites
 
### Required for both modes
```
Java 21              (sdkman: sdk install java 21.0.3-tem)
Maven 3.9.x          (sdkman: sdk install maven)
Docker Desktop 4.x   (https://www.docker.com/products/docker-desktop)
Node.js 20.x         (nvm: nvm install 20)
AWS CLI v2           (https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html)
Python 3.11+         (for test scripts)
psql                 (PostgreSQL client for RDS queries)
```
 
### Required for LOCAL mode only
```
LocalStack CLI       pip install localstack
LocalStack Docker    docker pull localstack/localstack
```
 
### Required for AWS mode only
```
AWS CDK CLI          npm install -g aws-cdk@2
AWS account          with AdministratorAccess or equivalent
```
 
---
 
## Mode 1: LOCAL (Docker Compose + LocalStack)
 
Everything runs locally. No AWS costs. Fast iteration.
 
### Start local environment
 
```bash
# 1. Start all local services
docker-compose up -d
 
# This starts:
#   postgres:15       → localhost:5432
#   localstack        → localhost:4566 (Kinesis, EventBridge, SQS, DynamoDB, S3)
#   localstack-init   → runs setup scripts after LocalStack is ready
 
# 2. Wait for LocalStack to be ready
docker-compose logs -f localstack | grep "Ready."
 
# 3. Run Flyway migrations against local Postgres
cd migrations/flyway
mvn flyway:migrate \
    -Dflyway.url=jdbc:postgresql://localhost:5432/smartretail \
    -Dflyway.user=smartretail_admin \
    -Dflyway.password=local_dev_password \
    -Dflyway.defaultSchema=public
 
# 4. Start services (each in a separate terminal or use Makefile)
cd services/sis
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
 
cd services/ims
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
 
cd services/re
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
 
cd services/ars
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```
 
### docker-compose.yml
 
```yaml
version: '3.9'
 
services:
  postgres:
    image: postgres:15
    container_name: smartretail-postgres
    environment:
      POSTGRES_DB: smartretail
      POSTGRES_USER: smartretail_admin
      POSTGRES_PASSWORD: local_dev_password
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U smartretail_admin -d smartretail"]
      interval: 5s
      timeout: 3s
      retries: 10
 
  localstack:
    image: localstack/localstack:3.x
    container_name: smartretail-localstack
    ports:
      - "4566:4566"
    environment:
      SERVICES: kinesis,events,sqs,dynamodb,s3,ssm,secretsmanager,iam,sts
      DEBUG: 0
      PERSISTENCE: 1
      DATA_DIR: /tmp/localstack/data
    volumes:
      - localstack-data:/tmp/localstack
      - /var/run/docker.sock:/var/run/docker.sock
      - ./scripts/localstack-init.sh:/etc/localstack/init/ready.d/init.sh
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:4566/_localstack/health | grep '\"kinesis\": \"running\"'"]
      interval: 10s
      timeout: 5s
      retries: 20
 
volumes:
  postgres-data:
  localstack-data:
```
 
### scripts/localstack-init.sh
 
This script runs automatically when LocalStack is ready:
 
```bash
#!/bin/bash
# localstack-init.sh — Create all LocalStack resources
 
set -e
 
ENDPOINT="http://localhost:4566"
ENV="local"
REGION="us-east-1"
ACCOUNT="000000000000"  # LocalStack fake account ID
 
echo "Creating LocalStack resources..."
 
# Kinesis stream
aws --endpoint-url=$ENDPOINT kinesis create-stream \
    --stream-name "smartretail-events-${ENV}" \
    --shard-count 1 \
    --region $REGION
 
# EventBridge bus
aws --endpoint-url=$ENDPOINT events create-event-bus \
    --name "smartretail-events-${ENV}" \
    --region $REGION
 
# SQS queues
aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}" \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ims-sales-${ENV}-dlq" \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}.fifo" \
    --attributes FifoQueue=true,ContentBasedDeduplication=false \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-re-alert-${ENV}-dlq.fifo" \
    --attributes FifoQueue=true \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT sqs create-queue \
    --queue-name "smartretail-ars-updates-${ENV}" \
    --region $REGION
 
# EventBridge rules routing to SQS
IMS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ims-sales-${ENV}"
RE_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-re-alert-${ENV}.fifo"
ARS_QUEUE_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:smartretail-ars-updates-${ENV}"
 
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis"],"detail-type":["SalesTransactionEvent"]}' \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "sales-to-ims-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ims-target\",\"Arn\":\"${IMS_QUEUE_ARN}\"}]" \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.ims"],"detail-type":["InventoryAlertEvent"]}' \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "alert-to-re-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"re-target\",\"Arn\":\"${RE_QUEUE_ARN}\",\"SqsParameters\":{\"MessageGroupIdPath\":\"$.detail.dcId\"}}]" \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT events put-rule \
    --name "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --event-pattern '{"source":["smartretail.sis","smartretail.ims","smartretail.re"]}' \
    --region $REGION
 
aws --endpoint-url=$ENDPOINT events put-targets \
    --rule "all-to-ars-${ENV}" \
    --event-bus-name "smartretail-events-${ENV}" \
    --targets "[{\"Id\":\"ars-target\",\"Arn\":\"${ARS_QUEUE_ARN}\"}]" \
    --region $REGION
 
# DynamoDB idempotency table
aws --endpoint-url=$ENDPOINT dynamodb create-table \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --attribute-definitions AttributeName=event_id,AttributeType=S \
    --key-schema AttributeName=event_id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region $REGION
 
# S3 bucket
aws --endpoint-url=$ENDPOINT s3 mb \
    "s3://smartretail-events-${ENV}" \
    --region $REGION
 
# SSM Parameter Store values (read by Spring Boot at startup)
PARAMS=(
    "/smartretail/local/rds/proxy-endpoint=localhost"
    "/smartretail/local/rds/database-name=smartretail"
    "/smartretail/local/eventbridge/bus-name=smartretail-events-local"
    "/smartretail/local/kinesis/stream-name=smartretail-events-local"
    "/smartretail/local/sqs/ims-sales-queue-url=http://localhost:4566/000000000000/smartretail-ims-sales-local"
    "/smartretail/local/sqs/re-alert-queue-url=http://localhost:4566/000000000000/smartretail-re-alert-local.fifo"
    "/smartretail/local/sqs/ars-updates-queue-url=http://localhost:4566/000000000000/smartretail-ars-updates-local"
)
 
for param in "${PARAMS[@]}"; do
    KEY="${param%%=*}"
    VALUE="${param##*=}"
    aws --endpoint-url=$ENDPOINT ssm put-parameter \
        --name "$KEY" \
        --value "$VALUE" \
        --type String \
        --region $REGION
done
 
echo "✅ LocalStack resources created successfully"
```
 
### Spring Boot application-local.yml (all services)
 
Place in each service's `src/main/resources/`:
 
```yaml
# application-local.yml
# Activated by SPRING_PROFILES_ACTIVE=local
 
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smartretail?currentSchema=${DB_SCHEMA:sales}
    username: smartretail_admin
    password: local_dev_password
    hikari:
      maximum-pool-size: 5
 
  flyway:
    enabled: false  # Run migrations separately via Maven
 
cloud:
  aws:
    region:
      static: us-east-1
    credentials:
      access-key: test   # LocalStack accepts any credentials
      secret-key: test
    endpoint:
      static: http://localhost:4566  # Override all AWS SDK endpoints
 
smartretail:
  env: local
  rds:
    proxy-endpoint: localhost
  eventbridge:
    bus-name: smartretail-events-local
  sqs:
    ims-sales-queue-url: http://localhost:4566/000000000000/smartretail-ims-sales-local
    re-alert-queue-url: http://localhost:4566/000000000000/smartretail-re-alert-local.fifo
    ars-updates-queue-url: http://localhost:4566/000000000000/smartretail-ars-updates-local
  kinesis:
    stream-name: smartretail-events-local
 
logging:
  level:
    com.smartretail: DEBUG
    org.springframework.jdbc: DEBUG
```
 
### AWS SDK LocalStack configuration bean
 
```java
// LocalStackConfig.java — only active on 'local' profile
@Configuration
@Profile("local")
public class LocalStackConfig {
 
    @Value("${cloud.aws.endpoint.static:http://localhost:4566}")
    private String localStackEndpoint;
 
    @Bean
    @Primary
    public EventBridgeClient localEventBridgeClient() {
        return EventBridgeClient.builder()
            .endpointOverride(URI.create(localStackEndpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }
 
    @Bean
    @Primary
    public SqsClient localSqsClient() {
        return SqsClient.builder()
            .endpointOverride(URI.create(localStackEndpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }
 
    @Bean
    @Primary
    public DynamoDbClient localDynamoDbClient() {
        return DynamoDbClient.builder()
            .endpointOverride(URI.create(localStackEndpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }
 
    @Bean
    @Primary
    public S3Client localS3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(localStackEndpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)  // Required for LocalStack S3
            .build();
    }
}
```
 
### Local MFE development
 
```bash
# Start MFE dev server pointing at local Spring Boot services
cd mfe/store-manager
cp .env.local.example .env.local
# Edit .env.local:
#   VITE_API_BASE_URL=http://localhost:8083   (ARS port)
#   VITE_AUTH_MODE=mock                       (bypass Cognito in local mode)
npm install
npm run dev   # Vite dev server on localhost:5173
```
 
### Local mode: mock auth
 
Cognito is not available in LocalStack Community Edition.
In local mode, auth is bypassed using a mock auth provider.
 
```typescript
// mfe/shared/auth/src/mockAuth.ts
// Activated when VITE_AUTH_MODE=mock
 
export const mockUser: User = {
  sub: 'local-sc-planner-user',
  email: 'sc-planner@local.dev',
  groups: ['SC_PLANNER'],
};
 
export const mockToken = 'local-dev-token-bypass';
```
 
Spring Boot services in local mode skip JWT validation:
 
```java
// SecurityConfig.java
@Configuration
@Profile("local")
public class LocalSecurityConfig {
    @Bean
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        // In local mode: accept any request, inject mock claims
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```
 
### Stop local environment
 
```bash
docker-compose down          # Stop containers, keep volumes
docker-compose down -v       # Stop containers, destroy volumes (clean slate)
```
 
---
 
## Mode 2: AWS (Real AWS Account)
 
### AWS CLI profiles
 
Set up named profiles for prototype and production:
 
```bash
# ~/.aws/credentials
[smartretail-dev]
aws_access_key_id = AKIA...
aws_secret_access_key = ...
region = us-east-1
 
[smartretail-prod]
aws_access_key_id = AKIA...
aws_secret_access_key = ...
region = us-east-1
```
 
```bash
# Use dev profile for all commands
export AWS_PROFILE=smartretail-dev
export AWS_REGION=us-east-1
export SMARTRETAIL_ENV=dev
```
 
### Spring Boot application-aws.yml
 
```yaml
# application-aws.yml
# Activated by SPRING_PROFILES_ACTIVE=aws
 
spring:
  datasource:
    # RDS Proxy endpoint injected at container startup from Parameter Store
    url: jdbc:postgresql://${RDS_PROXY_ENDPOINT}:5432/smartretail?currentSchema=${DB_SCHEMA}
    # Username for RDS IAM auth (not a password — IAM token used)
    username: ${DB_USERNAME}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
 
  flyway:
    enabled: false
 
smartretail:
  env: ${SMARTRETAIL_ENV}
 
# All AWS SDK clients use default credential chain (IAM task role in ECS)
# No endpoint override — uses real AWS endpoints
```
 
### Running a service locally against AWS resources
 
Useful for debugging a single service without deploying to ECS:
 
```bash
export AWS_PROFILE=smartretail-dev
export SPRING_PROFILES_ACTIVE=aws
export RDS_PROXY_ENDPOINT=$(aws ssm get-parameter \
    --name /smartretail/dev/rds/proxy-endpoint \
    --query Parameter.Value --output text)
export DB_SCHEMA=sales
export DB_USERNAME=sis_user
export SMARTRETAIL_ENV=dev
 
cd services/sis
mvn spring-boot:run
```
 
This connects to the real RDS Proxy, real SQS, real EventBridge.
Local code changes take effect immediately without a Docker build.
Useful for step-debugging against real data.
 
---
 
## Makefile
 
Root-level Makefile for common operations:
 
```makefile
# Makefile
 
ENV ?= local
PROFILE ?= smartretail-dev
 
# ─── Local ───────────────────────────────────────────────────────────
local-up:
  docker-compose up -d
  @echo "Waiting for services to be ready..."
  @until docker-compose exec postgres pg_isready -U smartretail_admin; do sleep 2; done
  @until curl -s http://localhost:4566/_localstack/health | grep '"kinesis": "running"'; do sleep 3; done
  @echo "✅ Local environment ready"
 
local-migrate:
  cd migrations/flyway && mvn flyway:migrate \
    -Dflyway.url=jdbc:postgresql://localhost:5432/smartretail \
    -Dflyway.user=smartretail_admin \
    -Dflyway.password=local_dev_password
 
local-seed: local-migrate
  PGPASSWORD=local_dev_password psql -h localhost -U smartretail_admin -d smartretail \
    -f migrations/flyway/V7__seed_data.sql
 
local-sis:
  cd services/sis && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8080"
 
local-ims:
  cd services/ims && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8081"
 
local-re:
  cd services/re  && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8082"
 
local-ars:
  cd services/ars && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8083"
 
local-mfe-sm:
  cd mfe/store-manager && npm run dev
 
local-mfe-scp:
  cd mfe/sc-planner && npm run dev -- --port 5174
 
local-mfe-exec:
  cd mfe/executive && npm run dev -- --port 5175
 
local-down:
  docker-compose down
 
local-clean:
  docker-compose down -v
 
# ─── Test ─────────────────────────────────────────────────────────────
test-unit:
  mvn test -pl services/sis,services/ims,services/re,services/ars
 
test-flow1:
  SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow1
 
test-flow2:
  SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow2
 
test-flow3:
  SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow3
 
test-all:
  SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all
 
# ─── AWS Deploy ───────────────────────────────────────────────────────
aws-bootstrap:
  AWS_PROFILE=$(PROFILE) cdk bootstrap aws://$(shell aws sts get-caller-identity \
    --profile $(PROFILE) --query Account --output text)/us-east-1
 
aws-deploy-network:
  AWS_PROFILE=$(PROFILE) cdk deploy NetworkStack --require-approval never
 
aws-deploy-data:
  AWS_PROFILE=$(PROFILE) cdk deploy DataStack --require-approval never
 
aws-migrate:
  AWS_PROFILE=$(PROFILE) ./scripts/run-flyway-aws.sh $(ENV)
 
aws-deploy-messaging:
  AWS_PROFILE=$(PROFILE) cdk deploy MessagingStack --require-approval never
 
aws-deploy-identity:
  AWS_PROFILE=$(PROFILE) cdk deploy IdentityStack --require-approval never
 
aws-deploy-compute:
  AWS_PROFILE=$(PROFILE) cdk deploy ComputeStack --require-approval never
 
aws-deploy-api:
  AWS_PROFILE=$(PROFILE) cdk deploy ApiStack --require-approval never
 
aws-deploy-all:
  AWS_PROFILE=$(PROFILE) cdk deploy --all --require-approval never
 
aws-create-users:
  AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/create-cognito-users.sh
 
aws-smoke-test:
  AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all
 
# ─── Build ────────────────────────────────────────────────────────────
build-services:
  mvn clean package -pl services/sis,services/ims,services/re,services/ars \
    -am --no-transfer-progress
 
build-lambda:
  mvn clean package -pl lambdas/kinesis-consumer --no-transfer-progress
 
build-mfes:
  cd mfe/store-manager && npm run build
  cd mfe/sc-planner    && npm run build
  cd mfe/executive     && npm run build
 
build-all: build-services build-lambda build-mfes
 
# ─── Docker ───────────────────────────────────────────────────────────
docker-build-sis:
  docker build -t smartretail-sis:local services/sis/
 
docker-build-all:
  for svc in sis ims re ars; do \
    docker build -t smartretail-$$svc:local services/$$svc/; \
  done
```
 
---
 
## Port Assignments (local mode)
 
| Service | Port |
|---------|------|
| SIS ECS | 8080 |
| IMS ECS | 8081 |
| RE ECS  | 8082 |
| ARS ECS | 8083 |
| PostgreSQL | 5432 |
| LocalStack | 4566 |
| Store Manager MFE | 5173 |
| SC Planner MFE | 5174 |
| Executive MFE | 5175 |
 
 
 