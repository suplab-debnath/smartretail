.PHONY: local-up local-migrate local-seed local-sis local-ims local-re local-ars local-dfs local-sup \
        local-mfe-sm local-mfe-scp local-mfe-exec local-free-ports local-down local-clean \
        local-sqs-sis \
        local-demo-server local-mfe-demo local-demo \
        aws-demo-server aws-demo \
        test-unit test-flow1 test-flow2 test-flow3 test-flow4 test-flow8 test-flow9 test-all \
        aws-ecr-login aws-push-all aws-push-lambda \
        aws-deploy-services aws-deploy-mfes \
        aws-migrate aws-create-users aws-smoke-test \
        aws-full-deploy aws-destroy \
        dev-bootstrap dev-deploy-messaging dev-deploy-compute dev-deploy-all \
        dev-push-all dev-deploy-services dev-migrate dev-create-users dev-destroy \
        build-services build-lambda build-mfes build-all \
        docker-build-sis docker-build-all docker-build-lambda \
        coverage-backend coverage-frontend coverage-all coverage-artifacts

ENV     ?= local
PROFILE ?= smartretail-dev
REGION  ?= us-east-1
ACCOUNT ?= $(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text 2>/dev/null)
ECR_PREFIX = $(ACCOUNT).dkr.ecr.$(REGION).amazonaws.com

# ── Local ─────────────────────────────────────────────────────────────────────

local-up:
	docker compose up -d
	@echo "Waiting for Postgres..."
	@until docker exec smartretail-postgres pg_isready -U smartretail_admin 2>/dev/null; do sleep 2; done
	@echo "Waiting for LocalStack (kinesis)..."
	@until curl -s http://localhost:4566/_localstack/health 2>/dev/null | grep -q '"kinesis": "running"'; do sleep 3; done
	@echo "✅ Local environment ready"

local-migrate:
	cd migrations/flyway && mvn flyway:migrate --no-transfer-progress \
	    -Dflyway.url=jdbc:postgresql://localhost:5432/smartretail \
	    -Dflyway.user=smartretail_admin \
	    -Dflyway.password=local_dev_password \
	    -Dflyway.locations=filesystem:src/main/resources/db/migration

local-seed:
	docker exec -i smartretail-postgres psql -U smartretail_admin -d smartretail \
	    < migrations/flyway/src/main/resources/db/migration/V7__seed_data.sql

local-sis:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=sales DB_USERNAME=smartretail_admin \
	    java -jar services/sis/target/smartretail-sis-1.0.0-SNAPSHOT.jar \
	    --server.port=8080

local-ims:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=inventory DB_USERNAME=smartretail_admin \
	    java -jar services/ims/target/smartretail-ims-1.0.0-SNAPSHOT.jar \
	    --server.port=8081

local-re:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=replenishment DB_USERNAME=smartretail_admin \
	    java -jar services/re/target/smartretail-re-1.0.0-SNAPSHOT.jar \
	    --server.port=8082

local-ars:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=ars_readonly DB_USERNAME=smartretail_admin \
	    java -jar services/ars/target/smartretail-ars-1.0.0-SNAPSHOT.jar \
	    --server.port=8083

local-dfs:
	SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
	    java -jar services/dfs/target/smartretail-dfs-1.0.0-SNAPSHOT.jar \
	    --server.port=8084

local-sup:
	SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
	    java -jar services/sup/target/smartretail-sup-1.0.0-SNAPSHOT.jar \
	    --server.port=8085

local-mfe-sm:
	cd mfe/store-manager && npm run dev -- --port 5173

local-mfe-scp:
	cd mfe/sc-planner && npm run dev -- --port 5174

local-mfe-exec:
	cd mfe/executive && npm run dev -- --port 5175

local-demo-server: ## Start demo control server at :3099 (local mode)
	cd demo-server && npm install --silent && node server.js

local-mfe-demo: ## Start Demo Control Center MFE at :5176
	cd mfe/demo && npm install --silent && npm run dev

local-demo: ## Start full demo experience — demo-server + demo MFE in parallel
	@echo "Starting demo-server (:3099) and Demo MFE (:5176)…"
	@pid=$$(lsof -t -i:3099 2>/dev/null); if [ -n "$$pid" ]; then echo "Freeing port 3099 (pid $$pid)..."; kill -9 $$pid; fi
	@make local-demo-server & make local-mfe-demo

aws-demo-server: ## Start demo control server in AWS mode
	cd demo-server && npm install --silent && SMARTRETAIL_ENV=aws node server.js

aws-demo: ## Start Demo Control Center pointing at AWS (set env vars first)
	@echo "Starting demo-server in AWS mode and Demo MFE…"
	@pid=$$(lsof -t -i:3099 2>/dev/null); if [ -n "$$pid" ]; then echo "Freeing port 3099 (pid $$pid)..."; kill -9 $$pid; fi
	@make aws-demo-server & cd mfe/demo && npm run dev

local-free-ports: ## Free up ports 8080-8085, 5173-5176, and 3099
	@echo "Checking and freeing ports..."
	@for port in 3099 8080 8081 8082 8083 8084 8085 5173 5174 5175 5176; do \
		pid=$$(lsof -t -i:$$port 2>/dev/null); \
		if [ -n "$$pid" ]; then \
			echo "Killing process $$pid holding port $$port..."; \
			kill -9 $$pid; \
		fi; \
	done
	@echo "✅ Ports freed"

local-sqs-sis: ## Run SIS with local-sqs profile (SQS POS ingestion via LocalStack, no Kinesis Lambda)
	SPRING_PROFILES_ACTIVE=local-sqs DB_SCHEMA=sales DB_USERNAME=smartretail_admin \
	    java -jar services/sis/target/smartretail-sis-1.0.0-SNAPSHOT.jar \
	    --server.port=8080

local-down: local-free-ports
	docker compose down

local-clean: local-free-ports
	docker compose down -v

# ── Test ──────────────────────────────────────────────────────────────────────

test-unit:
	mvn test -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup --no-transfer-progress

test-flow1:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow1

test-flow2:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow2

test-flow3:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow3

test-flow4:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow4

test-flow8:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow8

test-flow9:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow9

test-all:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all

# ── Build ─────────────────────────────────────────────────────────────────────

build-services:
	mvn clean package -DskipTests \
	    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup \
	    -am --no-transfer-progress

build-lambda:
	mvn clean package -DskipTests -pl lambdas/kinesis-consumer --no-transfer-progress

build-mfes:
	cd mfe/shared/auth && npm run build
	cd mfe/store-manager && npm run build
	cd mfe/sc-planner    && npm run build
	cd mfe/executive     && npm run build
	cd mfe/demo          && npm run build

build-all: build-services build-lambda build-mfes

# ── Docker ────────────────────────────────────────────────────────────────────

docker-build-sis:
	docker buildx build --platform linux/arm64 -t smartretail-sis:local services/sis/

docker-build-all:
	for svc in sis ims re ars dfs sup; do \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local services/$$svc/; \
	done

docker-build-lambda:
	docker buildx build --platform linux/arm64 -t smartretail-kinesis-consumer:local lambdas/kinesis-consumer/

# ── AWS / Prod CDK Deploy ──────────────────────────────────────────────────────
# Production CDK (infra/cdk-prod) is NOT wired into the Makefile.
# Deploy manually from infra/cdk-prod/ when intentional production deployments are needed.
# See infra/cdk-prod/README.md for instructions.

# ── AWS ECR & Image Push ──────────────────────────────────────────────────────

aws-ecr-login:
	aws ecr get-login-password --region $(REGION) --profile $(PROFILE) | \
	    docker login --username AWS --password-stdin $(ECR_PREFIX)

aws-push-%: docker-build-%
	docker tag smartretail-$*:local $(ECR_PREFIX)/smartretail-$*-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-$*-$(ENV):latest

aws-push-all: aws-ecr-login
	@for svc in sis ims re ars dfs sup; do \
	    echo "Pushing $$svc…"; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local services/$$svc/ && \
	    docker tag smartretail-$$svc:local $(ECR_PREFIX)/smartretail-$$svc-$(ENV):latest && \
	    docker push $(ECR_PREFIX)/smartretail-$$svc-$(ENV):latest; \
	done

aws-push-lambda: docker-build-lambda aws-ecr-login
	docker tag smartretail-kinesis-consumer:local \
	    $(ECR_PREFIX)/smartretail-kinesis-consumer-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-kinesis-consumer-$(ENV):latest

# ── AWS MFE Deploy ────────────────────────────────────────────────────────────

aws-deploy-mfe-%:
	cd mfe/$* && npm run build
	aws s3 sync mfe/$*/dist/ \
	    s3://smartretail-mfe-$(ENV)-$*-$(ACCOUNT)/ \
	    --delete --profile $(PROFILE)
	@CF_ID=$$(AWS_PROFILE=$(PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(ENV)/cloudfront/$*-distribution-id \
	    --query Parameter.Value --output text 2>/dev/null) && \
	[ -n "$$CF_ID" ] && \
	AWS_PROFILE=$(PROFILE) aws cloudfront create-invalidation \
	    --distribution-id "$$CF_ID" --paths "/*" || true

aws-deploy-mfes: ## Build and deploy all 3 MFEs to S3 + invalidate CloudFront
	@make aws-deploy-mfe-store-manager ENV=$(ENV) PROFILE=$(PROFILE)
	@make aws-deploy-mfe-sc-planner    ENV=$(ENV) PROFILE=$(PROFILE)
	@make aws-deploy-mfe-executive     ENV=$(ENV) PROFILE=$(PROFILE)

# ── Scripted build + deploy ───────────────────────────────────────────────────

aws-deploy-services: ## Build JARs + Docker images, push to ECR, force ECS redeployment
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION)

aws-deploy-services-wait: ## Same as aws-deploy-services but waits for ECS steady state
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION) --wait

aws-full-deploy: ## End-to-end: CDK infra → push images → deploy MFEs → migrate → create users
	@echo "=== Step 1/5: Deploy CDK stacks ==="
	SMARTRETAIL_ENV=$(ENV) ./scripts/deploy-cdk.sh
	@echo "=== Step 2/5: Push service + Lambda images to ECR ==="
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION)
	@echo "=== Step 3/5: Build + deploy MFEs ==="
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) \
	    ./scripts/deploy-mfes.sh --env $(ENV) --profile $(PROFILE)
	@echo "=== Step 4/5: Run DB migrations ==="
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/run-flyway-aws.sh $(ENV)
	@echo "=== Step 5/5: Create Cognito users ==="
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/create-cognito-users.sh $(ENV)
	@echo ""
	@echo "✅  Full deployment complete (env: $(ENV))"

# ── Teardown ──────────────────────────────────────────────────────────────────

dev-destroy: ## Destroy all Min-* CDK stacks (demo/dev environment)
	cd infra/cdk-min && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk destroy --all --force

aws-destroy: ## Full teardown: CDK stacks + S3 + ECR + CloudFront + SSM + logs
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) ./scripts/destroy-infra.sh

aws-migrate:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/run-flyway-aws.sh $(ENV)

aws-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/create-cognito-users.sh $(ENV)

# ── Dev / Demo (SQS-only, existing default VPC) ───────────────────────────────
# Uses infra/cdk-min — Kinesis replaced by SQS, reuses account default VPC.
# Run `cdk context` in infra/cdk-min once to populate VPC lookup cache.
# Spring profile: dev (inherits aws, adds POS_EVENTS_QUEUE_URL)

dev-bootstrap:
	cd infra/cdk-min && npm install --silent && AWS_PROFILE=$(PROFILE) npx cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text)/$(REGION)

dev-deploy-messaging:
	cd infra/cdk-min && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-MessagingStack --require-approval never

dev-deploy-compute:
	cd infra/cdk-min && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-ComputeStack --require-approval never

dev-deploy-all:
	cd infra/cdk-min && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy --all --require-approval never

dev-push-all: aws-ecr-login ## Build and push service images to ECR (dev env)
	@for svc in sis ims re ars dfs sup; do \
	    echo "Pushing $$svc (dev)…"; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local services/$$svc/ && \
	    docker tag smartretail-$$svc:local $(ECR_PREFIX)/smartretail-$$svc-dev:latest && \
	    docker push $(ECR_PREFIX)/smartretail-$$svc-dev:latest; \
	done

dev-deploy-services: ## Build, push images, force ECS redeployment (dev)
	SMARTRETAIL_ENV=dev AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/deploy-services.sh --env dev --profile $(PROFILE) --region $(REGION)

dev-migrate:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev ./scripts/run-flyway-aws.sh dev

dev-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev ./scripts/create-cognito-users.sh dev

aws-smoke-test:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all

# ── Coverage ──────────────────────────────────────────────────────────────────

coverage-backend: ## Run tests + generate JaCoCo aggregate report for all services
	mvn verify \
	    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup,services/coverage \
	    --also-make \
	    --no-transfer-progress

coverage-frontend: ## Run Vitest coverage on all MFEs and merge into a single LCOV report
	cd mfe/store-manager && npm run test:coverage
	cd mfe/sc-planner    && npm run test:coverage
	cd mfe/executive     && npm run test:coverage
	cd mfe/demo          && npm run test:coverage
	bash scripts/merge-mfe-coverage.sh

coverage-all: coverage-backend coverage-frontend ## Run backend + frontend coverage

coverage-artifacts: coverage-all ## Bundle both reports into dist/coverage/ and coverage-artifacts.tar.gz
	mkdir -p dist/coverage/backend
	cp -r services/coverage/target/site/jacoco-aggregate/. dist/coverage/backend/
	tar -czf coverage-artifacts.tar.gz -C dist coverage
	@echo ""
	@echo "Coverage artifacts:"
	@echo "  Backend HTML:  dist/coverage/backend/index.html"
	@echo "  Frontend HTML: dist/coverage/frontend/html/index.html"
	@echo "  Tarball:       coverage-artifacts.tar.gz"
