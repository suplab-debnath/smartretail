build-services:
	mvn clean package -DskipTests \
	    -pl backend/services/sis,backend/services/ims,backend/services/re,backend/services/ars,backend/services/dfs,backend/services/sup,backend/services/pps \
	    -am --no-transfer-progress

build-lambda:
	mvn clean package -DskipTests \
	    -pl backend/adapters/batch-post-processor,backend/adapters/ml-trigger \
	    --no-transfer-progress

build-mfes:
	cd mfe/shared/auth   && npm run build
	cd mfe/store-manager && npm run build
	cd mfe/sc-planner    && npm run build
	cd mfe/executive     && npm run build
	cd mfe/supplier      && npm run build
	cd tools/demo/ui     && npm run build

build-all: build-services build-lambda build-mfes

docker-build-sis:
	docker buildx build --platform linux/arm64 -t smartretail-sis:local backend/services/sis/

docker-build-all:
	for svc in sis ims re ars dfs sup; do \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local backend/services/$$svc/; \
	done

docker-build-batch-post-processor:
	docker buildx build --platform linux/arm64 -t smartretail-batch-post-processor:local backend/adapters/batch-post-processor/

docker-build-ml-trigger:
	docker buildx build --platform linux/arm64 -t smartretail-ml-trigger:local backend/adapters/ml-trigger/

docker-build-lambda: docker-build-batch-post-processor docker-build-ml-trigger
