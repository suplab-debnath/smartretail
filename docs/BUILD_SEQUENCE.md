# Build & Deployment Reference

---

## Prerequisites

| Tool           | Version | Install                                                             |
|----------------|---------|---------------------------------------------------------------------|
| Java           | 21      | `sdk install java 21.0.3-tem`                                       |
| Maven          | 3.9.x   | `sdk install maven`                                                 |
| Docker Desktop | 4.x     | https://www.docker.com/products/docker-desktop                      |
| Node.js        | 20.x    | `nvm install 20`                                                    |
| AWS CLI        | v2      | https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html |
| Python         | 3.11+   | For test scripts                                                    |
| psql           | any     | PostgreSQL client (`brew install libpq` / `apt install postgresql-client`) |

---

## Local Quick Start (≈5 minutes)

### 1. Start infrastructure

```bash
make local-up
```

LocalStack automatically creates all resources on startup via `environments/local/scripts/localstack-init.sh`:
- Firehose delivery stream → SIS HTTP endpoint
- EventBridge bus `smartretail-events-local` with routing rules
- SQS queues: `ims-sales-local`, `re-alert-local.fifo`, `ars-updates-local` (each with DLQ)
- S3 bucket `smartretail-events-local`
- SSM parameters read by Spring Boot at startup

### 2. Run schema migrations and seed data

```bash
make local-migrate   # Flyway V1–V9: creates all 6 RDS schemas + seed data
make local-seed      # Re-applies V7 reference data (idempotent)
```

Verify:
```bash
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT schema_name FROM information_schema.schemata \
      WHERE schema_name IN ('sales','inventory','replenishment','forecasting','supplier','promotions') \
      ORDER BY 1;"
# Expected: 6 rows
```

### 3. Start services

```bash
make local-sis   # :8080 — Sales Ingestion Service
make local-ims   # :8081 — Inventory Management Service
make local-re    # :8082 — Replenishment Engine
make local-ars   # :8083 — Analytics & Reporting Service
make local-dfs   # :8084 — Demand Forecasting Service
make local-sup   # :8085 — Supplier Service
make local-pps   # :8086 — Pricing & Promotions Service (stub)
```

Health-check all services:
```bash
for port in 8080 8081 8082 8083 8084 8085 8086; do
  echo -n "Port $port: " && curl -s http://localhost:$port/actuator/health | python3 -m json.tool
done
# All should return: {"status":"UP"}
```

### 4. Run smoke tests

```bash
make test-flow1   # ✅ 5 passed  ❌ 0 failed
make test-flow2   # ✅ 2 passed  — pre-condition: flow1 must have run
make test-flow3   # ✅ 4 passed  — pre-condition: flow2 must have run
make test-flow4   # ✅ 3 passed  — pre-condition: flows1–3 must have run
make test-flow8   # ✅ 11 passed — pre-condition: make local-seed
make test-flow9   # ✅ 11 passed — pre-condition: make local-seed
```

Trigger Flow 1 manually:
```bash
python3 scripts/shared/publish-pos-event.py \
  --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
  --direct-api http://localhost:8080
```

### 5. Stop

```bash
make local-down    # stop containers, preserve data volumes
make local-clean   # stop containers, destroy volumes (clean slate)
```

---

## Port Assignments (local mode)

| Service                     | Port |
|-----------------------------|------|
| SIS — Sales Ingestion       | 8080 |
| IMS — Inventory Management  | 8081 |
| RE — Replenishment Engine   | 8082 |
| ARS — Analytics & Reporting | 8083 |
| DFS — Demand Forecasting    | 8084 |
| SUP — Supplier Service      | 8085 |
| PPS — Pricing & Promotions  | 8086 |
| PostgreSQL                  | 5432 |
| LocalStack (all AWS)        | 4566 |
| Store Manager MFE           | 5173 |
| SC Planner MFE              | 5174 |
| Executive MFE               | 5175 |
| Demo Control Center MFE     | 5176 |
| Supplier Portal MFE         | 5177 |
| Demo Control Server         | 3099 |

---

## Key API Endpoints (local)

### SIS — POST /v1/ingest/events

```bash
curl -X POST http://localhost:8080/v1/ingest/events \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "'"$(python3 -c "import uuid; print(uuid.uuid4())")"'",
    "storeId": "STORE-001",
    "skuId": "SKU-BEV-001",
    "dcId": "DC-LONDON",
    "quantity": 5,
    "unitPrice": 8.50,
    "channel": "POS",
    "eventTimestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"
  }'
# 202 Accepted — {"transactionId":"...","status":"ACCEPTED"}
# 409 Conflict — duplicate transactionId (idempotency)
```

### IMS — inventory queries

```bash
curl "http://localhost:8081/v1/inventory/positions?dcId=DC-LONDON"
curl "http://localhost:8081/v1/inventory/alerts?status=ACTIVE"
```

### RE — replenishment orders

```bash
curl "http://localhost:8082/v1/replenishment/orders"
curl "http://localhost:8082/v1/replenishment/orders?status=PENDING_APPROVAL"
curl "http://localhost:8082/v1/replenishment/orders/{poId}"
```

### ARS — dashboard endpoints

```bash
curl -s http://localhost:8083/v1/dashboard/store-manager?dcId=DC-LONDON | python3 -m json.tool
curl -s http://localhost:8083/v1/dashboard/executive | python3 -m json.tool
curl -s http://localhost:8083/v1/dashboard/planner | python3 -m json.tool
```

---

## AWS Deployment

### Stack variants

| Stack       | Path                        | Purpose                                                | Stack prefix |
|-------------|-----------------------------|--------------------------------------------------------|--------------|
| `cdk-demo`  | `environments/demo/infra/`  | Demo only — SQS, default VPC, ARM64, low cost          | `Min-*`      |
| `cdk-dev`   | `environments/dev/infra/`   | Full dev — Firehose, 2-AZ VPC, RDS Proxy, CloudFront   | `Dev-*`      |
| `cdk-prod`  | `environments/prod/infra/`  | Production — 3-AZ VPC, Multi-AZ RDS (manual deploys)   | `Prod-*`     |

`cdk-demo` is the only stack wired into the Makefile. Use it for demos and initial testing.

### AWS prerequisites

```bash
aws configure --profile smartretail-dev
export AWS_PROFILE=smartretail-dev
export SMARTRETAIL_ENV=dev
export CDK_DEFAULT_REGION=us-east-1
```

### First-time full deployment (≈45 minutes)

```bash
make aws-full-deploy ENV=dev PROFILE=smartretail-dev
```

Steps performed in sequence:
1. `environments/demo/scripts/deploy-demo.sh` — CDK bootstrap + all stacks
2. `scripts/shared/deploy-services.sh` — Maven → Docker → ECR → ECS force-deploy
3. `scripts/shared/deploy-mfes.sh` — npm build → S3 sync → CloudFront invalidation
4. `scripts/shared/run-flyway-aws.sh` — Flyway migrations against RDS
5. `scripts/shared/create-cognito-users.sh` — test Cognito users

### Incremental deploys

```bash
# Service code change
make aws-deploy-services ENV=dev
./scripts/shared/deploy-services.sh --env dev --services re,ims  # subset
./scripts/shared/deploy-services.sh --env dev --wait             # wait for ECS steady state

# MFE change
make aws-deploy-mfes ENV=dev
./scripts/shared/deploy-mfes.sh --env dev --mfes sc-planner      # subset

# CDK infrastructure change
make aws-deploy-all        # safest — all stacks
make aws-deploy-compute    # ComputeStack only
make aws-deploy-hosting    # HostingStack only (CloudFront changes)

# DB migration only
make aws-migrate ENV=dev
```

### Teardown

```bash
make aws-undeploy ENV=dev    # CDK stacks only; S3/ECR untouched
make aws-destroy ENV=dev     # full teardown — all AWS resources
```

`destroy-infra.sh` destroys stacks in reverse dependency order then cleans up orphaned CloudFront distributions, S3 buckets, ECR repos, CloudWatch log groups, SSM parameters, and Cognito pools.

---

## Makefile Reference

All targets accept `ENV` (default: `local`) and `PROFILE` (default: `smartretail-dev`).

### Local development

| Target               | Description                                                        |
|----------------------|--------------------------------------------------------------------|
| `local-up`           | Start Postgres + LocalStack; wait for readiness                    |
| `local-migrate`      | Flyway V1–V9 against local Postgres                                |
| `local-seed`         | Re-apply V7 reference data                                         |
| `local-sis`          | Start SIS on :8080                                                 |
| `local-ims`          | Start IMS on :8081                                                 |
| `local-re`           | Start RE on :8082                                                  |
| `local-ars`          | Start ARS on :8083                                                 |
| `local-dfs`          | Start DFS on :8084                                                 |
| `local-sup`          | Start SUP on :8085                                                 |
| `local-pps`          | Start PPS on :8086                                                 |
| `local-mfe-sm`       | Start Store Manager MFE on :5173                                   |
| `local-mfe-scp`      | Start SC Planner MFE on :5174                                      |
| `local-mfe-exec`     | Start Executive MFE on :5175                                       |
| `local-mfe-supplier` | Start Supplier Portal MFE on :5177                                 |
| `local-demo-server`  | Start Demo Control Server on :3099                                 |
| `local-mfe-demo`     | Start Demo Control Center MFE on :5176                             |
| `local-demo`         | Start demo server + demo MFE in parallel                           |
| `local-free-ports`   | Kill host processes holding ports 8080–8086 and 5173–5177          |
| `local-down`         | Stop containers, preserve volumes                                  |
| `local-clean`        | Stop containers, destroy volumes                                   |

### Testing

| Target       | Description                                             |
|--------------|---------------------------------------------------------|
| `test-unit`  | Unit tests for all 7 services via Maven                 |
| `test-flow1` | Smoke test Flow 1 (POS → SIS → IMS → stock alert)       |
| `test-flow2` | Smoke test Flow 2 (RE auto-approve + PENDING_APPROVAL)  |
| `test-flow3` | Smoke test Flow 3 (SC Planner approve/reject)           |
| `test-flow4` | Smoke test Flow 4 (ARS → Store Manager dashboard)       |
| `test-flow8` | Smoke test Flow 8 (Executive Dashboard)                 |
| `test-flow9` | Smoke test Flow 9 (SC Planner Console)                  |
| `test-all`   | Run smoke tests for all flows                           |

### Build

| Target                | Description                                                       |
|-----------------------|-------------------------------------------------------------------|
| `build-services`      | `mvn clean package -DskipTests` for all 7 services                |
| `build-lambda`        | `mvn clean package -DskipTests` for batch-post-processor Lambda   |
| `build-mfes`          | `npm run build` for all 5 MFEs                                    |
| `build-all`           | All of the above                                                  |
| `docker-build-all`    | Build Docker images for all 7 services locally                    |

### AWS infrastructure

| Target                 | Description                                       |
|------------------------|---------------------------------------------------|
| `aws-bootstrap`        | CDK bootstrap (one-time per account/region)       |
| `aws-deploy-network`   | Deploy NetworkStack                               |
| `aws-deploy-data`      | Deploy DataStack                                  |
| `aws-deploy-messaging` | Deploy MessagingStack                             |
| `aws-deploy-identity`  | Deploy IdentityStack (Cognito)                    |
| `aws-deploy-compute`   | Deploy ComputeStack (ECS, Lambda)                 |
| `aws-deploy-api`       | Deploy ApiStack (API Gateway)                     |
| `aws-deploy-hosting`   | Deploy HostingStack (CloudFront)                  |
| `aws-deploy-all`       | Deploy all stacks                                 |

### AWS artifacts & operations

| Target                     | Description                                                     |
|----------------------------|-----------------------------------------------------------------|
| `aws-ecr-login`            | Authenticate Docker to ECR                                      |
| `aws-push-all`             | Build + push all 7 service images to ECR                        |
| `aws-deploy-services`      | Maven → Docker → ECR → ECS force-deploy                         |
| `aws-deploy-services-wait` | Same as above, waits for ECS steady state                       |
| `aws-deploy-mfes`          | Build + deploy all 5 MFEs                                       |
| `aws-migrate`              | Flyway migrations against RDS                                   |
| `aws-create-users`         | Create test Cognito users                                       |
| `aws-smoke-test`           | All smoke tests against AWS endpoints                           |
| `aws-full-deploy`          | First-time end-to-end: CDK → images → MFEs → migrate → users   |
| `aws-undeploy`             | Destroy all CDK stacks; S3/ECR untouched                        |
| `aws-destroy`              | Full teardown via `destroy-infra.sh`                            |
