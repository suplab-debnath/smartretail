test-unit:
	mvn test -pl backend/services/sis,backend/services/ims,backend/services/re,backend/services/ars,backend/services/dfs,backend/services/sup --no-transfer-progress

test-flow1:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow1

test-flow2:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow2

test-flow3:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow3

test-flow4:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow4

test-flow8:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow8

test-flow9:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh flow9

test-all:
	SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh all

aws-smoke-test:
	AWS_PROFILE=$(PROFILE) SMARTRETAIL_ENV=$(ENV) ./scripts/shared/smoke-test.sh all
