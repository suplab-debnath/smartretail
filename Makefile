.PHONY: local-up local-migrate local-seed local-sis local-ims local-re local-ars \
        local-mfe-sm local-mfe-scp local-mfe-exec local-down local-clean \
        test-unit test-flow1 test-flow2 test-flow3 test-all \
        aws-bootstrap aws-deploy-network aws-deploy-data aws-deploy-messaging \
        aws-deploy-identity aws-deploy-compute aws-deploy-api aws-deploy-all \
        aws-migrate aws-create-users aws-smoke-test \
        build-services build-lambda build-mfes build-all \
        docker-build-sis docker-build-all

ENV     ?= local
PROFILE ?= smartretail-dev

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
	PGPASSWORD=local_dev_password psql -h localhost -U smartretail_admin -d smartretail \
	    -f migrations/flyway/src/main/resources/db/migration/V7__seed_data.sql

local-sis:
	cd services/sis && SPRING_PROFILES_ACTIVE=local DB_SCHEMA=sales DB_USERNAME=smartretail_admin \
	    mvn spring-boot:run --no-transfer-progress \
	    -Dspring-boot.run.jvmArguments="-Dserver.port=8080"

local-ims:
	cd services/ims && SPRING_PROFILES_ACTIVE=local DB_SCHEMA=inventory DB_USERNAME=smartretail_admin \
	    mvn spring-boot:run --no-transfer-progress \
	    -Dspring-boot.run.jvmArguments="-Dserver.port=8081"

local-re:
	cd services/re && SPRING_PROFILES_ACTIVE=local DB_SCHEMA=replenishment DB_USERNAME=smartretail_admin \
	    mvn spring-boot:run --no-transfer-progress \
	    -Dspring-boot.run.jvmArguments="-Dserver.port=8082"

local-ars:
	cd services/ars && SPRING_PROFILES_ACTIVE=local DB_SCHEMA=ars_readonly DB_USERNAME=smartretail_admin \
	    mvn spring-boot:run --no-transfer-progress \
	    -Dspring-boot.run.jvmArguments="-Dserver.port=8083"

local-mfe-sm:
	cd mfe/store-manager && npm run dev -- --port 5173

local-mfe-scp:
	cd mfe/sc-planner && npm run dev -- --port 5174

local-mfe-exec:
	cd mfe/executive && npm run dev -- --port 5175

local-down:
	docker compose down

local-clean:
	docker compose down -v

# ── Test ──────────────────────────────────────────────────────────────────────

test-unit:
	mvn test -pl services/sis,services/ims,services/re,services/ars --no-transfer-progress

test-flow1:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow1

test-flow2:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow2

test-flow3:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh flow3

test-all:
	SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all

# ── Build ─────────────────────────────────────────────────────────────────────

build-services:
	mvn clean package -DskipTests \
	    -pl services/sis,services/ims,services/re,services/ars \
	    -am --no-transfer-progress

build-lambda:
	mvn clean package -DskipTests -pl lambdas/kinesis-consumer --no-transfer-progress

build-mfes:
	cd mfe/store-manager && npm run build
	cd mfe/sc-planner    && npm run build
	cd mfe/executive     && npm run build

build-all: build-services build-lambda build-mfes

# ── Docker ────────────────────────────────────────────────────────────────────

docker-build-sis:
	docker build -t smartretail-sis:local services/sis/

docker-build-all:
	for svc in sis ims re ars; do \
	    docker build -t smartretail-$$svc:local services/$$svc/; \
	done

# ── AWS Deploy ────────────────────────────────────────────────────────────────

aws-bootstrap:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text)/us-east-1

aws-deploy-network:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy NetworkStack --require-approval never

aws-deploy-data:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy DataStack --require-approval never

aws-deploy-messaging:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy MessagingStack --require-approval never

aws-deploy-identity:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy IdentityStack --require-approval never

aws-deploy-compute:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy ComputeStack --require-approval never

aws-deploy-api:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy ApiStack --require-approval never

aws-deploy-all:
	cd infra/cdk && AWS_PROFILE=$(PROFILE) cdk deploy --all --require-approval never

aws-migrate:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/run-flyway-aws.sh $(ENV)

aws-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/create-cognito-users.sh $(ENV)

aws-smoke-test:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/smoke-test.sh all
