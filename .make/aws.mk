# ── AWS / Prod CDK Deploy ──────────────────────────────────────────────────────
# Production CDK (infra/cdk-prod) is NOT wired into the Makefile.
# Deploy manually from infra/cdk-prod/ when intentional production deployments are needed.
# See infra/cdk-prod/README.md for instructions.

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

aws-migrate:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/run-flyway-aws.sh $(ENV)

aws-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/create-cognito-users.sh $(ENV)

aws-destroy: ## Full teardown: CDK stacks + S3 + ECR + CloudFront + SSM + logs
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) ./scripts/destroy-infra.sh
