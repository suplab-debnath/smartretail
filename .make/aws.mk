# ── AWS / Prod CDK Deploy ──────────────────────────────────────────────────────
# Production CDK (environments/prod/infra) is NOT wired into the Makefile.
# Deploy manually from environments/prod/infra/ when intentional production deployments are needed.
# See environments/prod/README.md for instructions.

aws-ecr-login:
	aws ecr get-login-password --region $(REGION) --profile $(PROFILE) | \
	    docker login --username AWS --password-stdin $(ECR_PREFIX)

aws-push-%: docker-build-%
	docker tag smartretail-$*:local $(ECR_PREFIX)/smartretail-$*-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-$*-$(ENV):latest

aws-push-all: aws-ecr-login
	@for svc in sis ims re ars dfs sup; do \
	    echo "Pushing $$svc..."; \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local backend/services/$$svc/ && \
	    docker tag smartretail-$$svc:local $(ECR_PREFIX)/smartretail-$$svc-$(ENV):latest && \
	    docker push $(ECR_PREFIX)/smartretail-$$svc-$(ENV):latest; \
	done

aws-push-lambda: docker-build-lambda aws-ecr-login
	docker tag smartretail-batch-post-processor:local \
	    $(ECR_PREFIX)/smartretail-batch-post-processor-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-batch-post-processor-$(ENV):latest
	docker tag smartretail-ml-trigger:local \
	    $(ECR_PREFIX)/smartretail-ml-trigger-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-ml-trigger-$(ENV):latest

aws-deploy-mfe-%:
	cd mfe/$* && npm run build
	aws s3 sync mfe/$*/dist/ \
	    s3://smartretail-mfe-$(ENV)-$*-$(ACCOUNT)/ \
	    --delete --profile $(PROFILE)
	@CF_ID=$$(AWS_PROFILE=$(PROFILE) aws ssm get-parameter \
	    --name /smartretail/$(ENV)/hosting/cloudfront-distribution-id \
	    --query Parameter.Value --output text 2>/dev/null); \
	if [ -n "$$CF_ID" ]; then \
	    echo "Invalidating CloudFront distribution $$CF_ID ..."; \
	    AWS_PROFILE=$(PROFILE) aws cloudfront create-invalidation \
	        --distribution-id "$$CF_ID" --paths "/*" --no-cli-pager \
	        --query 'Invalidation.Status' --output text; \
	fi

aws-deploy-mfes: ## Build and deploy all 3 MFEs to S3 + invalidate CloudFront
	@make aws-deploy-mfe-store-manager ENV=$(ENV) PROFILE=$(PROFILE)
	@make aws-deploy-mfe-sc-planner    ENV=$(ENV) PROFILE=$(PROFILE)
	@make aws-deploy-mfe-executive     ENV=$(ENV) PROFILE=$(PROFILE)

aws-deploy-services: ## Build JARs + Docker images, push to ECR, force ECS redeployment
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/shared/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION)

aws-deploy-services-wait: ## Same as aws-deploy-services but waits for ECS steady state
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/shared/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION) --wait

aws-full-deploy: ## End-to-end: CDK infra → push images → deploy MFEs → migrate → create users
	@echo "=== Step 1/5: Deploy CDK stacks ==="
	SMARTRETAIL_ENV=$(ENV) ./environments/dev/scripts/deploy-cdk.sh
	@echo "=== Step 2/5: Push service + Lambda images to ECR ==="
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) AWS_DEFAULT_REGION=$(REGION) \
	    ./scripts/shared/deploy-services.sh --env $(ENV) --profile $(PROFILE) --region $(REGION)
	@echo "=== Step 3/5: Build + deploy MFEs ==="
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) \
	    ./scripts/shared/deploy-mfes.sh --env $(ENV) --profile $(PROFILE)
	@echo "=== Step 4/5: Run DB migrations ==="
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/shared/run-flyway-aws.sh $(ENV)
	@echo "=== Step 5/5: Create Cognito users ==="
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/shared/create-cognito-users.sh $(ENV)
	@echo ""
	@echo "✅  Full deployment complete (env: $(ENV))"

aws-push-flyway: aws-ecr-login docker-build-flyway-amd64 ## Build amd64 Flyway image and push to ECR (dev/prod)
	docker tag smartretail-flyway:local $(ECR_PREFIX)/smartretail-flyway-$(ENV):latest
	docker push $(ECR_PREFIX)/smartretail-flyway-$(ENV):latest

aws-migrate: ## Run Flyway migrations via ECS Fargate (no IP allowlisting needed)
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/shared/run-flyway-aws.sh $(ENV)

aws-create-users:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/shared/create-cognito-users.sh $(ENV)

aws-destroy: ## Full teardown: CDK stacks + S3 + ECR + CloudFront + SSM + logs
	SMARTRETAIL_ENV=$(ENV) AWS_PROFILE=$(PROFILE) ./environments/demo/scripts/destroy-infra.sh
