---
mode: 'agent'
description: 'Task: Scaffold a new Spring Boot service with hexagonal architecture, OpenAPI YAML, Maven module, and ArchUnit test'
tools: ['codebase', 'new', 'runCommand', 'usages', 'workspaceDetails']
---

Scaffold a new SmartRetail Spring Boot 3.3 service.

## Service details
- **Service name (short):** ${input:serviceName}
  (e.g. `ims`, `dfs` -- used in package names, Maven artifactId, resource file names)
- **Service full name:** ${input:serviceFullName}
  (e.g. `Inventory Management Service`)
- **Server port (local):** ${input:port}
- **PostgreSQL schema owned:** ${input:schema}
- **Primary domain entity:** ${input:domainEntity}
  (e.g. `InventoryPosition`, `PurchaseOrder`)

## What to create

### 1. Maven module: `backend/services/{serviceName}/pom.xml`
- Parent: `backend/pom.xml`
- ArtifactId: `smartretail-{serviceName}`
- Include: spring-boot-starter-web, spring-boot-starter-jdbc, spring-boot-starter-actuator,
  aws-messaging (SQS), aws-eventbridge, openapi-generator-maven-plugin, archunit, testcontainers

### 2. OpenAPI YAML: `src/main/resources/{serviceName}-api.yaml`
- Minimal skeleton with `openapi: 3.1.0`, info, servers (local port + AWS stage), BearerAuth security
- One example endpoint: `GET /v1/{resourceName}/{id}` returning the domain entity
- `ErrorResponse` component

### 3. Hexagonal package structure under `com.smartretail.{serviceName}/`:
```
domain/model/{DomainEntity}.java           <- record with compact constructor validation
domain/usecase/Get{DomainEntity}UseCase.java  <- implements inbound port
port/inbound/Get{DomainEntity}Port.java    <- interface
port/outbound/{DomainEntity}Repository.java <- interface returning Optional<{DomainEntity}>
adapter/inbound/rest/{ServiceName}Controller.java  <- @RestController
adapter/outbound/persistence/{DomainEntity}JdbcRepository.java  <- Spring Data JDBC
config/SecurityConfig.java                <- JWT filter (aws profile) + mock bypass (local profile)
{ServiceName}Application.java             <- @SpringBootApplication
```

### 4. Resources:
- `src/main/resources/application.yml` -- standard config (port, datasource, actuator, flyway.enabled=false)
- `src/main/resources/application-local.yml` -- LocalStack endpoint, mock auth
- `src/main/resources/logback-spring.xml` -- JSON structured logging

### 5. ArchUnit test: `src/test/java/.../ArchitectureTest.java`
Three rules: no AWS imports in domain, use cases only depend on ports, controllers don't call outbound adapters.

After scaffolding, run `mvn compile -pl backend/services/{serviceName}` to verify it compiles.
