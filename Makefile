.PHONY: local-up local-migrate local-seed local-sis local-ims local-re local-ars local-dfs local-sup \
        local-mfe-sm local-mfe-scp local-mfe-exec local-free-ports local-down local-clean \
        local-sqs-sis \
        local-demo-server local-mfe-demo local-demo \
        aws-demo-server aws-demo \
        test-unit test-flow1 test-flow2 test-flow3 test-flow4 test-flow8 test-flow9 test-all \
        aws-ecr-login aws-push-all aws-push-lambda \
        aws-deploy-services aws-deploy-mfes \
        aws-migrate aws-create-users aws-smoke-test \
        aws-full-deploy aws-destroy \
        dev-bootstrap dev-deploy-messaging dev-deploy-compute dev-deploy-all \
        dev-push-all dev-deploy-services dev-migrate dev-create-users dev-destroy \
        demo-bootstrap demo-cdk-deploy demo-build-services demo-push-services \
        demo-migrate demo-deploy-mfe demo-create-users demo-full-deploy \
        demo-stop demo-start demo-destroy \
        build-services build-lambda build-mfes build-all \
        docker-build-sis docker-build-all docker-build-lambda \
        coverage-backend coverage-frontend coverage-all coverage-artifacts

include .make/vars.mk
include .make/local.mk
include .make/test.mk
include .make/build.mk
include .make/aws.mk
include .make/demo.mk
include .make/coverage.mk
