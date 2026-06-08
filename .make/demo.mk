# ── Demo / SC Planner (cdk-demo) ─────────────────────────────────────────────
# Deploys a trimmed SC Planner demo: 6 backend services (SIS, IMS, RE, ARS, DFS, SUP),
# 1 MFE, REST API + NLB. Intended lifespan: 1-2 days. Tear down with `make demo-destroy`.
# All resources tagged Lifecycle=ephemeral for easy cost tracking and cleanup.

DEMO_ENV     ?= demo
DEMO_PROFILE ?= $(PROFILE)
DEMO_SERVICES = sis ims re ars dfs sup

demo-bootstrap: ## Bootstrap CDK for demo environment (run once per account/region)
	cd environments/demo/infra && npm install --silent && \
	AWS_PROFILE=$(DEMO_PROFILE) npx cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(DEMO_PROFILE) aws sts get-caller-identity --query Account --output text)/$(REGION)

demo-cdk-deploy: ## Deploy all Min-* CDK stacks (trimmed SC Planner demo)
	cd environments/demo/infra && \
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    npx cdk deploy --all --require-approval never \
	    $(if $(ALERT_EMAIL),-c alertEmail=$(ALERT_EMAIL),)

demo-build-services: ## Build Docker images for the 6 SC Planner backend services
	@for svc in $(DEMO_SERVICES); do \
	    echo "Building $$svc..."; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local backend/services/$$svc/; \
	done

demo-push-services: aws-ecr-login demo-build-services ## Build + push 6 service images to ECR (demo env)
	@ACCOUNT=$(shell AWS_PROFILE=$(DEMO_PROFILE) aws sts get-caller-identity --query Account --output text); \
	for svc in $(DEMO_SERVICES); do \
	    echo "Pushing $$svc ($(DEMO_ENV))..."; \
	    docker tag smartretail-$$svc:local \
	        $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-$$svc-$(DEMO_ENV):latest; \
	    docker push \
	        $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-$$svc-$(DEMO_ENV):latest; \
	done

demo-deploy-services: demo-push-services ## Build, push and force ECS redeployment for all demo services
	@for svc in $(DEMO_SERVICES); do \
	    echo "Force-redeploying $$svc ($(DEMO_ENV))..."; \
	    AWS_PROFILE=$(DEMO_PROFILE) aws ecs update-service \
	        --cluster smartretail-$(DEMO_ENV) \
	        --service smartretail-$$svc-$(DEMO_ENV) \
	        --force-new-deployment \
	        --query 'service.serviceName' --output text; \
	done

demo-push-flyway: aws-ecr-login docker-build-flyway-amd64 ## Build amd64 Flyway image and push to demo ECR
	@ACCOUNT=$(shell AWS_PROFILE=$(DEMO_PROFILE) aws sts get-caller-identity --query Account --output text); \
	docker tag smartretail-flyway:local \
	    $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-flyway-$(DEMO_ENV):latest; \
	docker push \
	    $$ACCOUNT.dkr.ecr.$(REGION).amazonaws.com/smartretail-flyway-$(DEMO_ENV):latest

demo-migrate: ## Run Flyway migrations via ECS Fargate (no IP allowlisting needed)
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    ./environments/demo/scripts/run-flyway-aws-demo.sh $(DEMO_ENV)

demo-reset-db: ## Wipe and reinitialise the demo DB (flyway clean + migrate) — use between demo runs
	AWS_PROFILE=$(DEMO_PROFILE) \
	    ./scripts/shared/run-flyway-ecs.sh $(DEMO_ENV) --clean

demo-deploy-mfe: ## Build and deploy SC Planner MFE to demo S3 bucket + invalidate CloudFront
	cd mfe/sc-planner && npm install --silent && VITE_BASE_PATH=/sc-planner/ npm run build
	@API_URL=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/api/endpoint \
	    --query Parameter.Value --output text); \
	POOL_ID=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/cognito/internal-pool-id \
	    --query Parameter.Value --output text); \
	CLIENT_ID=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/cognito/internal-client-id \
	    --query Parameter.Value --output text); \
	DOMAIN=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/cognito/internal-domain \
	    --query Parameter.Value --output text); \
	printf 'window.SMARTRETAIL_CONFIG = {\n  apiGatewayEndpoint: "%s",\n  cognitoPoolId:      "%s",\n  cognitoClientId:    "%s",\n  cognitoDomain:      "%s",\n  env:                "%s",\n};\n' \
	    "$$API_URL" "$$POOL_ID" "$$CLIENT_ID" "$$DOMAIN" "$(DEMO_ENV)" \
	    > mfe/sc-planner/dist/config.js; \
	echo "config.js written (api: $$API_URL, domain: $$DOMAIN)"; \
	BUCKET=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/hosting/sc-planner-bucket-name \
	    --query Parameter.Value --output text); \
	AWS_PROFILE=$(DEMO_PROFILE) aws s3 sync mfe/sc-planner/dist/ s3://$$BUCKET/ --delete; \
	CF_ID=$$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/hosting/cloudfront-distribution-id \
	    --query Parameter.Value --output text); \
	echo "Invalidating CloudFront distribution $$CF_ID ..."; \
	AWS_PROFILE=$(DEMO_PROFILE) aws cloudfront create-invalidation \
	    --distribution-id "$$CF_ID" --paths "/*" --no-cli-pager \
	    --query 'Invalidation.Status' --output text; \
	echo "SC Planner URL: $$(AWS_PROFILE=$(DEMO_PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(DEMO_ENV)/hosting/sc-planner-url \
	    --query Parameter.Value --output text)"

demo-create-users: ## Create Cognito users for the demo environment
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    ./scripts/shared/create-cognito-users.sh $(DEMO_ENV)

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

demo-stop: ## Scale all ECS services to 0 and stop RDS (keeps infra, saves cost overnight)
	@echo "Stopping ECS services ($(DEMO_ENV))..."
	@for svc in $(DEMO_SERVICES); do \
	    AWS_PROFILE=$(DEMO_PROFILE) aws ecs update-service \
	        --cluster smartretail-$(DEMO_ENV) \
	        --service smartretail-$$svc-$(DEMO_ENV) \
	        --desired-count 0 \
	        --no-cli-pager \
	        --query 'service.serviceName' --output text; \
	done
	@echo "Stopping RDS (smartretail-rds-$(DEMO_ENV))..."
	@AWS_PROFILE=$(DEMO_PROFILE) aws rds stop-db-instance \
	    --db-instance-identifier smartretail-rds-$(DEMO_ENV) \
	    --no-cli-pager \
	    --query 'DBInstance.DBInstanceStatus' --output text
	@echo "✅  Demo stopped. Resume with: make demo-start"

demo-start: ## Scale ECS services back to 1 and start RDS
	@echo "Starting RDS (smartretail-rds-$(DEMO_ENV))..."
	@STATUS=$$(AWS_PROFILE=$(DEMO_PROFILE) aws rds describe-db-instances \
	    --db-instance-identifier smartretail-rds-$(DEMO_ENV) \
	    --query 'DBInstances[0].DBInstanceStatus' --output text); \
	if [ "$$STATUS" = "stopped" ]; then \
	    AWS_PROFILE=$(DEMO_PROFILE) aws rds start-db-instance \
	        --db-instance-identifier smartretail-rds-$(DEMO_ENV) \
	        --no-cli-pager \
	        --query 'DBInstance.DBInstanceStatus' --output text; \
	    echo "  RDS starting — wait ~2 min before services can connect"; \
	else \
	    echo "  RDS status: $$STATUS (no action needed)"; \
	fi
	@echo "Scaling ECS services to 1 ($(DEMO_ENV))..."
	@for svc in $(DEMO_SERVICES); do \
	    AWS_PROFILE=$(DEMO_PROFILE) aws ecs update-service \
	        --cluster smartretail-$(DEMO_ENV) \
	        --service smartretail-$$svc-$(DEMO_ENV) \
	        --desired-count 1 \
	        --no-cli-pager \
	        --query 'service.serviceName' --output text; \
	done
	@echo "✅  Demo started. Services will be healthy once RDS is available (~2 min)."

demo-destroy: ## Destroy all Min-* CDK stacks for the demo environment
	cd environments/demo/infra && \
	AWS_PROFILE=$(DEMO_PROFILE) SMARTRETAIL_ENV=$(DEMO_ENV) \
	    npx cdk destroy --all --force

# ── Dev / Demo (SQS-only, existing default VPC) ───────────────────────────────
# Uses environments/demo/infra — Kinesis replaced by SQS, reuses account default VPC.
# Run `cdk context` in environments/demo/infra once to populate VPC lookup cache.
# Spring profile: dev (inherits aws, adds POS_EVENTS_QUEUE_URL)

dev-bootstrap:
	cd environments/demo/infra && npm install --silent && AWS_PROFILE=$(PROFILE) npx cdk bootstrap \
	    aws://$(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text)/$(REGION)

dev-deploy-messaging:
	cd environments/demo/infra && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-MessagingStack --require-approval never

dev-deploy-compute:
	cd environments/demo/infra && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy Min-ComputeStack --require-approval never

dev-deploy-all:
	cd environments/demo/infra && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk deploy --all --require-approval never

dev-push-all: aws-ecr-login ## Build and push service images to ECR (dev env)
	@for svc in sis ims re ars dfs sup; do \
	    echo "Pushing $$svc (dev)..."; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local backend/services/$$svc/ && \
	    docker tag smartretail-$$svc:local $(ECR_PREFIX)/smartretail-$$svc-dev:latest && \
	    docker push $(ECR_PREFIX)/smartretail-$$svc-dev:latest; \
	done

dev-deploy-services: ## Build, push images, force ECS redeployment (dev)
	SMARTRETAIL_ENV=dev AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/shared/deploy-services.sh --env dev --profile $(PROFILE) --region $(REGION)

dev-migrate:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev ./scripts/shared/run-flyway-aws.sh dev

dev-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev ./scripts/shared/create-cognito-users.sh dev

dev-destroy: ## Destroy all Min-* CDK stacks (dev environment)
	cd environments/demo/infra && AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=dev npx cdk destroy --all --force
