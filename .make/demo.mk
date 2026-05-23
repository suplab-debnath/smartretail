# ── Demo / SC Planner (cdk-demo, SC Planner only) ────────────────────────────
# Deploys a trimmed SC Planner demo: 5 backend services (no SIS), 1 MFE, 1 ALB.
# Intended lifespan: 1-2 days. Tear down with `make demo-destroy`.
# All resources tagged Lifecycle=ephemeral for easy cost tracking and cleanup.

DEMO_ENV     ?= demo
DEMO_PROFILE ?= $(PROFILE)
DEMO_SERVICES = ims re ars dfs sup

demo-bootstrap: ## Bootstrap CDK for demo environment (run once per account/region)
	cd infra/cdk-demo && npm install --silent && \
	AWS_PROFILE=$(DEMO_PROFILE) npx cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(DEMO_PROFILE) aws sts get-caller-identity --query Account --output text)/$(REGION)

demo-cdk-deploy: ## Deploy all Min-* CDK stacks (trimmed SC Planner demo)
	cd infra/cdk-demo && \
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    npx cdk deploy --all --require-approval never \
	    $(if $(ALERT_EMAIL),-c alertEmail=$(ALERT_EMAIL),)

demo-build-services: ## Build Docker images for the 5 SC Planner backend services
	@for svc in $(DEMO_SERVICES); do \
	    echo "Building $$svc…"; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local services/$$svc/; \
	done

demo-push-services: aws-ecr-login demo-build-services ## Build + push 5 service images to ECR (demo env)
	@ACCOUNT=$(shell AWS_PROFILE=$(DEMO_PROFILE) aws sts get-caller-identity --query Account --output text); \
	for svc in $(DEMO_SERVICES); do \
	    echo "Pushing $$svc ($(DEMO_ENV))…"; \
	    docker tag smartretail-$$svc:local \
	        $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-$$svc-$(DEMO_ENV):latest; \
	    docker push \
	        $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-$$svc-$(DEMO_ENV):latest; \
	done

demo-migrate: ## Run Flyway migrations on demo RDS (includes V7 seed data)
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    ./scripts/run-flyway-aws.sh $(DEMO_ENV)

demo-deploy-mfe: ## Build and deploy SC Planner MFE to demo S3 bucket
	cd mfe/sc-planner && npm install --silent && npm run build
	@BUCKET=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/hosting/sc-planner-bucket-name \
	    --query Parameter.Value --output text); \
	AWS_PROFILE=$(DEMO_PROFILE) aws s3 sync mfe/sc-planner/dist/ s3://$$BUCKET/ --delete
	@echo "SC Planner URL: $$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/hosting/sc-planner-url \
	    --query Parameter.Value --output text)"

demo-create-users: ## Create Cognito users for the demo environment
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    ./scripts/create-cognito-users.sh $(DEMO_ENV)

demo-full-deploy: ## Full demo deployment: CDK → images → migrate → MFE → users
	@echo "=== [1/5] CDK stacks ==="
	@make demo-cdk-deploy   DEMO_ENV=$(DEMO_ENV) DEMO_PROFILE=$(DEMO_PROFILE)
	@echo "=== [2/5] Push service images ==="
	@make demo-push-services DEMO_ENV=$(DEMO_ENV) DEMO_PROFILE=$(DEMO_PROFILE)
	@echo "=== [3/5] DB migrations + seed data ==="
	@make demo-migrate      DEMO_ENV=$(DEMO_ENV) DEMO_PROFILE=$(DEMO_PROFILE)
	@echo "=== [4/5] SC Planner MFE ==="
	@make demo-deploy-mfe   DEMO_ENV=$(DEMO_ENV) DEMO_PROFILE=$(DEMO_PROFILE)
	@echo "=== [5/5] Cognito users ==="
	@make demo-create-users DEMO_ENV=$(DEMO_ENV) DEMO_PROFILE=$(DEMO_PROFILE)
	@echo ""
	@echo "✅  SC Planner demo ready (env: $(DEMO_ENV))"
	@echo "    Dashboard: https://$(REGION).console.aws.amazon.com/cloudwatch/home?region=$(REGION)#dashboards:name=SmartRetail-$(DEMO_ENV)-Ops"

demo-destroy: ## Destroy all Min-* CDK stacks for the demo environment
	cd infra/cdk-demo && \
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    npx cdk destroy --all --force

# ── Dev / Demo (SQS-only, existing default VPC) ───────────────────────────────
# Uses infra/cdk-demo — Kinesis replaced by SQS, reuses account default VPC.
# Run `cdk context` in infra/cdk-demo once to populate VPC lookup cache.
# Spring profile: dev (inherits aws, adds POS_EVENTS_QUEUE_URL)

dev-bootstrap:
	cd infra/cdk-demo && npm install --silent && AWS_PROFILE=$(PROFILE) npx cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text)/$(REGION)

dev-deploy-messaging:
	cd infra/cdk-demo && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-MessagingStack --require-approval never

dev-deploy-compute:
	cd infra/cdk-demo && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-ComputeStack --require-approval never

dev-deploy-all:
	cd infra/cdk-demo && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy --all --require-approval never

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

dev-destroy: ## Destroy all Min-* CDK stacks (dev environment)
	cd infra/cdk-demo && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk destroy --all --force
