build-services:
	mvn clean package -DskipTests \
	    -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup,services/pps \
	    -am --no-transfer-progress

build-lambda:
	mvn clean package -DskipTests -pl lambdas/kinesis-consumer --no-transfer-progress

build-mfes:
	cd mfe/shared/auth   && npm run build
	cd mfe/store-manager && npm run build
	cd mfe/sc-planner    && npm run build
	cd mfe/executive     && npm run build
	cd mfe/supplier      && npm run build
	cd demo/ui           && npm run build

build-all: build-services build-lambda build-mfes

docker-build-sis:
	docker buildx build --platform linux/arm64 -t smartretail-sis:local services/sis/

docker-build-all:
	for svc in sis ims re ars dfs sup; do \
	    docker buildx build --platform linux/arm64 -t smartretail-$$svc:local services/$$svc/; \
	done

docker-build-lambda:
	docker buildx build --platform linux/arm64 -t smartretail-kinesis-consumer:local lambdas/kinesis-consumer/
