local-up:
	docker compose up -d
	@echo "Waiting for Postgres..."
	@until docker exec smartretail-postgres pg_isready -U smartretail_admin 2>/dev/null; do sleep 2; done
	@echo "Waiting for LocalStack (firehose, sqs, events, s3, ssm)..."
	@until curl -sf http://localhost:4566/_localstack/health 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin)['services']; exit(0 if all(d.get(s)=='running' for s in ['firehose','sqs','events','s3','ssm']) else 1)" 2>/dev/null; do sleep 3; done
	@echo "✅ Local environment ready"

local-migrate:
	cd backend/migrations && mvn flyway:migrate --no-transfer-progress \
	    -Dflyway.url=jdbc:postgresql://localhost:5432/smartretail \
	    -Dflyway.user=smartretail_admin \
	    -Dflyway.password=local_dev_password \
	    -Dflyway.locations=filesystem:src/main/resources/db/migration

local-seed:
	docker exec -i smartretail-postgres psql -U smartretail_admin -d smartretail \
	    < backend/migrations/src/main/resources/db/migration/V7__seed_data.sql

local-sis:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=sales DB_USERNAME=smartretail_admin \
	    java -jar backend/services/sis/target/smartretail-sis-1.0.0-SNAPSHOT.jar \
	    --server.port=8080

local-sqs-sis: ## Run SIS with local-sqs profile (SQS POS ingestion via LocalStack, no Kinesis Lambda)
	SPRING_PROFILES_ACTIVE=local-sqs DB_SCHEMA=sales DB_USERNAME=smartretail_admin \
	    java -jar backend/services/sis/target/smartretail-sis-1.0.0-SNAPSHOT.jar \
	    --server.port=8080

local-ims:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=inventory DB_USERNAME=smartretail_admin \
	    java -jar backend/services/ims/target/smartretail-ims-1.0.0-SNAPSHOT.jar \
	    --server.port=8081

local-re:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=replenishment DB_USERNAME=smartretail_admin \
	    java -jar backend/services/re/target/smartretail-re-1.0.0-SNAPSHOT.jar \
	    --server.port=8082

local-ars:
	SPRING_PROFILES_ACTIVE=local DB_SCHEMA=ars_readonly DB_USERNAME=smartretail_admin \
	    java -jar backend/services/ars/target/smartretail-ars-1.0.0-SNAPSHOT.jar \
	    --server.port=8083

local-dfs:
	SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
	    java -jar backend/services/dfs/target/smartretail-dfs-1.0.0-SNAPSHOT.jar \
	    --server.port=8084

local-sup:
	SPRING_PROFILES_ACTIVE=local DB_USERNAME=smartretail_admin \
	    java -jar backend/services/sup/target/smartretail-sup-1.0.0-SNAPSHOT.jar \
	    --server.port=8085

local-mfe-sm:
	cd mfe/store-manager && npm run dev -- --port 5173

local-mfe-scp:
	cd mfe/sc-planner && npm run dev -- --port 5174

local-mfe-exec:
	cd mfe/executive && npm run dev -- --port 5175

local-mfe-supplier:
	cd mfe/supplier && npm run dev -- --port 5177

local-demo-server: ## Start demo control server at :3099 (local mode)
	cd tools/demo/server && npm install --silent && node server.js

local-mfe-demo: ## Start Demo Control Center MFE at :5176
	cd tools/demo/ui && npm install --silent && npm run dev

local-demo: ## Start full demo experience — tools/demo/server + tools/demo/ui in parallel
	@echo "Starting tools/demo/server (:3099) and tools/demo/ui (:5176)..."
	@pid=$$(lsof -t -i:3099 2>/dev/null); if [ -n "$$pid" ]; then echo "Freeing port 3099 (pid $$pid)..."; kill -9 $$pid; fi
	@make local-demo-server & make local-mfe-demo

aws-demo-server: ## Start demo control server in AWS mode
	cd tools/demo/server && npm install --silent && SMARTRETAIL_ENV=aws node server.js

aws-demo: ## Start Demo Control Center pointing at AWS (set env vars first)
	@echo "Starting tools/demo/server in AWS mode and tools/demo/ui..."
	@pid=$$(lsof -t -i:3099 2>/dev/null); if [ -n "$$pid" ]; then echo "Freeing port 3099 (pid $$pid)..."; kill -9 $$pid; fi
	@make aws-demo-server & cd tools/demo/ui && npm run dev

local-free-ports: ## Free up ports 8080-8085, 5173-5177, and 3099
	@echo "Checking and freeing ports..."
	@for port in 3099 8080 8081 8082 8083 8084 8085 5173 5174 5175 5176 5177; do \
		pid=$$(lsof -t -i:$$port 2>/dev/null); \
		if [ -n "$$pid" ]; then \
			echo "Killing process $$pid holding port $$port..."; \
			kill -9 $$pid; \
		fi; \
	done
	@echo "✅ Ports freed"

local-down: local-free-ports
	docker compose down

local-clean: local-free-ports
	docker compose down -v
