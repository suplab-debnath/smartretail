# Backend

All JVM/server-side code for the SmartRetail prototype lives here.

| Directory | Contents |
|-----------|----------|
| `services/` | Seven Spring Boot microservices (SIS, IMS, RE, ARS, DFS, SUP, PPS) |
| `lambdas/` | AWS Lambda functions (kinesis-consumer: Kinesis → SIS adapter; batch-post-processor: SageMaker S3 output → DFS adapter) |
| `coverage/` | JaCoCo aggregate coverage report — includes all services and the lambda |

## Build

```bash
# Build all services
mvn clean package -DskipTests \
    -pl backend/services/sis,backend/services/ims,backend/services/re,backend/services/ars,backend/services/dfs,backend/services/sup,backend/services/pps \
    -am --no-transfer-progress

# Build the Lambdas
mvn clean package -DskipTests \
    -pl backend/lambdas/kinesis-consumer,backend/lambdas/batch-post-processor \
    --no-transfer-progress

# Run aggregate coverage report (services + lambda)
mvn verify -pl backend/services/sis,backend/services/ims,backend/services/re,backend/services/ars,backend/services/dfs,backend/services/sup,backend/services/pps,backend/coverage --also-make --no-transfer-progress
```

Or use the Makefile shortcuts: `make build-services`, `make build-lambda`, `make coverage-backend`.

See `docs/SERVICE_SPECS.md` for per-service hexagonal package structure and `docs/BUILD_SEQUENCE.md` for the full build sequence.
