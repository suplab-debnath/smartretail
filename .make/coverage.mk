coverage-backend: ## Run tests + generate JaCoCo aggregate report for all services
	mvn verify \
	    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup,services/coverage \
	    --also-make \
	    --no-transfer-progress

coverage-frontend: ## Run Vitest coverage on all MFEs and merge into a single LCOV report
	cd mfe/store-manager && npm run test:coverage
	cd mfe/sc-planner    && npm run test:coverage
	cd mfe/executive     && npm run test:coverage
	cd demo/ui           && npm run test:coverage
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
