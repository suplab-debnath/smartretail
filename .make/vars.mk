ENV     ?= local
PROFILE ?= smartretail-dev
REGION  ?= us-east-1
ACCOUNT ?= $(shell AWS_PROFILE=$(PROFILE) aws sts get-caller-identity --query Account --output text 2>/dev/null)
ECR_PREFIX = $(ACCOUNT).dkr.ecr.$(REGION).amazonaws.com
