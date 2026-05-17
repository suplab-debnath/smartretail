# Lambdas

AWS Lambda functions that act as infrastructure adapters at the edge of the system. Domain logic stays in the Spring Boot services — lambdas are thin translators and entry points only.

## Functions

| Directory | Trigger | Purpose |
|-----------|---------|---------|
| `kinesis-consumer/` | Kinesis Data Stream | Deduplicates and forwards POS events from the stream to SIS |

## Technology

- **Java 21** — same JVM version as the services
- **Maven** — shares the parent `pom.xml` dependency management
- **AWS Lambda Java runtime** (`aws-lambda-java-core 1.2.3`, `aws-lambda-java-events 3.14.0`)
- **AWS SDK v2** — DynamoDB client for idempotency, no other AWS SDK usage in handler logic

## Build

```bash
# From repository root
JAVA_HOME=<java-21-home> mvn clean package -pl lambdas/kinesis-consumer

# Produces a fat JAR at:
# lambdas/kinesis-consumer/target/kinesis-consumer-1.0.0-SNAPSHOT.jar
```

## Deploy

The JAR is packaged and deployed by the CDK `ComputeStack`. In local mode, a LocalStack Lambda function is created by `scripts/localstack-init.sh`.

```bash
make local-up        # starts LocalStack
make aws-deploy-all  # deploys to real AWS (requires SMARTRETAIL_ENV and AWS_PROFILE)
```

## Design constraints

- Lambdas contain **no domain logic** — ArchUnit would fail if any domain rules were placed here.
- All state is held in RDS or DynamoDB — lambdas are stateless.
- SQS DLQ receives events after 3 consecutive Lambda failures.
