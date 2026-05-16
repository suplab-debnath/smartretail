# Maven and Code Generation Standards
 
---
 
## Project Structure
 
```
smartretail-prototype/
├── pom.xml ← parent POM (BOM + plugin management)
├── services/
│ ├── pom.xml ← services aggregator POM
│ ├── sis/pom.xml ← inherits from parent
│ ├── ims/pom.xml
│ ├── re/pom.xml
│ └── ars/pom.xml
├── lambdas/
│ └── kinesis-consumer/pom.xml
└── openapi/
├── sis-api.yaml
├── ims-api.yaml
├── re-api.yaml
├── ars-api.yaml
└── components/
├── schemas.yaml
├── responses.yaml
└── parameters.yaml
```
 
---
 
## Parent POM (root pom.xml)
 
The parent POM manages ALL dependency versions and plugin versions.
Service POMs never declare versions — they inherit from parent.
 
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
http://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion>
 
<groupId>com.smartretail</groupId>
<artifactId>smartretail-prototype-parent</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>pom</packaging>
 
<modules>
<module>services</module>
<module>lambdas</module>
</modules>
 
<parent>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-parent</artifactId>
<version>3.3.2</version>
<relativePath/>
</parent>
 
<properties>
<java.version>21</java.version>
<maven.compiler.source>21</maven.compiler.source>
<maven.compiler.target>21</maven.compiler.target>
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
 
<!-- Dependency versions — managed here, never in child POMs -->
<aws-sdk.version>2.26.12</aws-sdk.version>
<openapi-generator.version>7.7.0</openapi-generator.version>
<flyway.version>10.15.0</flyway.version>
<postgresql.version>42.7.3</postgresql.version>
<testcontainers.version>1.20.1</testcontainers.version>
<archunit.version>1.3.0</archunit.version>
<logstash-encoder.version>7.4</logstash-encoder.version>
<springdoc.version>2.6.0</springdoc.version>
<jackson.version>2.17.2</jackson.version>
<spring-cloud-aws.version>3.1.1</spring-cloud-aws.version>
</properties>
 
<dependencyManagement>
<dependencies>
<!-- AWS SDK BOM — manages all SDK module versions -->
<dependency>
<groupId>software.amazon.awssdk</groupId>
<artifactId>bom</artifactId>
<version>${aws-sdk.version}</version>
<type>pom</type>
<scope>import</scope>
</dependency>
 
<!-- TestContainers BOM -->
<dependency>
<groupId>org.testcontainers</groupId>
<artifactId>testcontainers-bom</artifactId>
<version>${testcontainers.version}</version>
<type>pom</type>
<scope>import</scope>
</dependency>
 
<!-- Spring Cloud AWS BOM -->
<dependency>
<groupId>io.awspring.cloud</groupId>
<artifactId>spring-cloud-aws-dependencies</artifactId>
<version>${spring-cloud-aws.version}</version>
<type>pom</type>
<scope>import</scope>
</dependency>
 
<!-- Shared internal modules -->
<dependency>
<groupId>com.smartretail</groupId>
<artifactId>smartretail-common</artifactId>
<version>${project.version}</version>
</dependency>
</dependencies>
</dependencyManagement>
 
<build>
<pluginManagement>
<plugins>
<!-- OpenAPI Generator — managed version, config in child POMs -->
<plugin>
<groupId>org.openapitools</groupId>
<artifactId>openapi-generator-maven-plugin</artifactId>
<version>${openapi-generator.version}</version>
</plugin>
 
<!-- Flyway -->
<plugin>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-maven-plugin</artifactId>
<version>${flyway.version}</version>
</plugin>
 
<!-- Surefire — JUnit 5 -->
<plugin>
<groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-surefire-plugin</artifactId>
<version>3.3.1</version>
<configuration>
<includes>
<include>**/*Test.java</include>
</includes>
<excludes>
<exclude>**/*IT.java</exclude>
</excludes>
</configuration>
</plugin>
 
<!-- Failsafe — integration tests -->
<plugin>
<groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-failsafe-plugin</artifactId>
<version>3.3.1</version>
<executions>
<execution>
<goals>
<goal>integration-test</goal>
<goal>verify</goal>
</goals>
</execution>
</executions>
<configuration>
<includes>
<include>**/*IT.java</include>
</includes>
</configuration>
</plugin>
 
<!-- JaCoCo — coverage enforcement -->
<plugin>
<groupId>org.jacoco</groupId>
<artifactId>jacoco-maven-plugin</artifactId>
<version>0.8.12</version>
<executions>
<execution>
<id>prepare-agent</id>
<goals><goal>prepare-agent</goal></goals>
</execution>
<execution>
<id>report</id>
<phase>test</phase>
<goals><goal>report</goal></goals>
</execution>
<execution>
<id>check</id>
<goals><goal>check</goal></goals>
<configuration>
<rules>
<rule>
<element>PACKAGE</element>
<limits>
<limit>
<counter>LINE</counter>
<value>COVEREDRATIO</value>
<minimum>0.80</minimum>
</limit>
</limits>
<includes>
<include>com.smartretail.*.domain.*</include>
</includes>
</rule>
</rules>
</configuration>
</execution>
</executions>
</plugin>
</plugins>
</pluginManagement>
</build>
</project>
```
 
---
 
## Service POM Template (e.g. services/re/pom.xml)
 
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
<modelVersion>4.0.0</modelVersion>
 
<parent>
<groupId>com.smartretail</groupId>
<artifactId>smartretail-services-parent</artifactId>
<version>1.0.0-SNAPSHOT</version>
<relativePath>../pom.xml</relativePath>
</parent>
 
<artifactId>smartretail-re</artifactId>
<name>SmartRetail - Replenishment Engine</name>
 
<!-- NO <version> tag here — inherited from parent -->
<!-- NO dependency versions here — all from parent BOM -->
 
<dependencies>
<!-- Spring Boot starters -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-data-jdbc</artifactId>
</dependency>
 
<!-- Spring Cloud AWS — SQS listener, SSM parameter store -->
<dependency>
<groupId>io.awspring.cloud</groupId>
<artifactId>spring-cloud-aws-starter-sqs</artifactId>
</dependency>
<dependency>
<groupId>io.awspring.cloud</groupId>
<artifactId>spring-cloud-aws-starter-parameter-store</artifactId>
</dependency>
 
<!-- AWS SDK — EventBridge (direct SDK, not Spring Cloud AWS) -->
<dependency>
<groupId>software.amazon.awssdk</groupId>
<artifactId>eventbridge</artifactId>
</dependency>
 
<!-- Database -->
<dependency>
<groupId>org.postgresql</groupId>
<artifactId>postgresql</artifactId>
</dependency>
<dependency>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-core</artifactId>
</dependency>
<dependency>
<groupId>org.flywaydb</groupId>
<artifactId>flyway-database-postgresql</artifactId>
</dependency>
 
<!-- OpenAPI / SpringDoc — generates /v3/api-docs from annotations -->
<dependency>
<groupId>org.springdoc</groupId>
<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
<version>${springdoc.version}</version>
</dependency>
 
<!-- Jackson -->
<dependency>
<groupId>com.fasterxml.jackson.core</groupId>
<artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
<groupId>com.fasterxml.jackson.datatype</groupId>
<artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
 
<!-- Structured logging -->
<dependency>
<groupId>net.logstash.logback</groupId>
<artifactId>logstash-logback-encoder</artifactId>
<version>${logstash-encoder.version}</version>
</dependency>
 
<!-- Shared common module -->
<dependency>
<groupId>com.smartretail</groupId>
<artifactId>smartretail-common</artifactId>
</dependency>
 
<!-- Test dependencies — scope=test, no version needed (parent BOM) -->
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-test</artifactId>
<scope>test</scope>
</dependency>
<dependency>
<groupId>org.testcontainers</groupId>
<artifactId>postgresql</artifactId>
<scope>test</scope>
</dependency>
<dependency>
<groupId>org.testcontainers</groupId>
<artifactId>junit-jupiter</artifactId>
<scope>test</scope>
</dependency>
<dependency>
<groupId>com.tngtech.archunit</groupId>
<artifactId>archunit-junit5</artifactId>
<version>${archunit.version}</version>
<scope>test</scope>
</dependency>
</dependencies>
 
<build>
<plugins>
<!-- Spring Boot executable JAR -->
<plugin>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
 
<!-- OpenAPI Generator — Spring server stubs from YAML -->
<plugin>
<groupId>org.openapitools</groupId>
<artifactId>openapi-generator-maven-plugin</artifactId>
<executions>
<execution>
<id>generate-re-api</id>
<goals><goal>generate</goal></goals>
<configuration>
<!-- Source spec — relative to project root -->
<inputSpec>${project.basedir}/../../openapi/re-api.yaml</inputSpec>
 
<!-- Spring server generator -->
<generatorName>spring</generatorName>
 
<!-- Output to target — never committed -->
<output>${project.build.directory}/generated-sources/openapi</output>
 
<!-- Base package -->
<apiPackage>com.smartretail.re.adapter.inbound.rest.generated.api</apiPackage>
<modelPackage>com.smartretail.re.adapter.inbound.rest.generated.model</modelPackage>
<invokerPackage>com.smartretail.re.adapter.inbound.rest.generated</invokerPackage>
 
<configOptions>
<!-- Generate interfaces — our controllers implement them -->
<interfaceOnly>true</interfaceOnly>
 
<!-- Use Java 21 features -->
<useSpringBoot3>true</useSpringBoot3>
<useJakartaEe>true</useJakartaEe>
 
<!-- Use records for model classes -->
<useRecords>true</useRecords>
 
<!-- Delegate pattern — clean separation -->
<delegatePattern>false</delegatePattern>
 
<!-- Spring annotations on generated interfaces -->
<useTags>true</useTags>
<documentationProvider>springdoc</documentationProvider>
 
<!-- Date/time mapping -->
<dateLibrary>java8</dateLibrary>
 
<!-- Serialisation -->
<serializableModel>false</serializableModel>
<skipDefaultInterface>true</skipDefaultInterface>
 
<!-- Bean validation annotations on models -->
<useBeanValidation>true</useBeanValidation>
<performBeanValidation>true</performBeanValidation>
 
<!-- OpenAPI 3.1 -->
<openApiSpecVersion>3.1.0</openApiSpecVersion>
</configOptions>
 
<!-- Do not generate files we don't need -->
<generateApiTests>false</generateApiTests>
<generateModelTests>false</generateModelTests>
<generateApiDocumentation>false</generateApiDocumentation>
<generateModelDocumentation>true</generateModelDocumentation>
<generateSupportingFiles>false</generateSupportingFiles>
</configuration>
</execution>
</executions>
</plugin>
 
<!-- Add generated sources to compile path -->
<plugin>
<groupId>org.codehaus.mojo</groupId>
<artifactId>build-helper-maven-plugin</artifactId>
<version>3.6.0</version>
<executions>
<execution>
<id>add-generated-sources</id>
<phase>generate-sources</phase>
<goals><goal>add-source</goal></goals>
<configuration>
<sources>
<source>${project.build.directory}/generated-sources/openapi/src/main/java</source>
</sources>
</configuration>
</execution>
</executions>
</plugin>
 
<!-- JaCoCo — inherited from parent pluginManagement, just activate -->
<plugin>
<groupId>org.jacoco</groupId>
<artifactId>jacoco-maven-plugin</artifactId>
</plugin>
</plugins>
</build>
</project>
```
 
---
 
## How Controllers Use Generated Interfaces
 
The generator produces an interface. Your controller implements it.
 
```java
// Generated (do not edit):
// target/generated-sources/openapi/.../ReplenishmentApi.java
@RequestMapping("/v1/replenishment")
public interface ReplenishmentApi {
 
@PostMapping("/orders/{poId}/approve")
ResponseEntity<ApproveResponse> approvePurchaseOrder(
@PathVariable UUID poId,
@RequestHeader(value = "X-Idempotency-Key") UUID idempotencyKey,
@RequestBody(required = false) ApproveRequest approveRequest
);
}
 
// Your controller implements the generated interface:
// adapter/inbound/rest/ReplenishmentController.java
@RestController
@Tag(name = "purchase-orders", description = "Purchase order lifecycle management")
public class ReplenishmentController implements ReplenishmentApi {
 
private final ApprovalPort approvalPort;
private final JwtValidator jwtValidator;
 
public ReplenishmentController(ApprovalPort approvalPort, JwtValidator jwtValidator) {
this.approvalPort = approvalPort;
this.jwtValidator = jwtValidator;
}
 
@Override
public ResponseEntity<ApproveResponse> approvePurchaseOrder(
UUID poId,
UUID idempotencyKey,
ApproveRequest request) {
 
JwtClaims claims = jwtValidator.extractFromContext();
if (!claims.hasRole("SC_PLANNER") && !claims.hasRole("ADMIN")) {
throw new UnauthorizedException("SC_PLANNER or ADMIN role required");
}
 
ApprovalResult result = approvalPort.approve(
poId,
claims.getSub(),
idempotencyKey.toString()
);
 
// ApproveResponse is a generated record — use its builder/constructor
return ResponseEntity.ok(new ApproveResponse(
result.poId(),
result.status().name(),
result.approvedBy(),
result.approvedAt(),
result.version()
));
}
}
```
 
---
 
## TypeScript Client Generation (package.json scripts)
 
In `mfe/shared/api-client/package.json`:
 
```json
{
"name": "@smartretail/api-client",
"version": "1.0.0",
"scripts": {
"generate:sis": "openapi-generator-cli generate -i ../../../openapi/sis-api.yaml -g typescript-axios -o src/generated/sis --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,modelPropertyNaming=camelCase,enumPropertyNaming=UPPERCASE",
"generate:ims": "openapi-generator-cli generate -i ../../../openapi/ims-api.yaml -g typescript-axios -o src/generated/ims --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,modelPropertyNaming=camelCase,enumPropertyNaming=UPPERCASE",
"generate:re": "openapi-generator-cli generate -i ../../../openapi/re-api.yaml -g typescript-axios -o src/generated/re --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,modelPropertyNaming=camelCase,enumPropertyNaming=UPPERCASE",
"generate:ars": "openapi-generator-cli generate -i ../../../openapi/ars-api.yaml -g typescript-axios -o src/generated/ars --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,modelPropertyNaming=camelCase,enumPropertyNaming=UPPERCASE",
"generate": "npm run generate:sis && npm run generate:ims && npm run generate:re && npm run generate:ars",
"build": "npm run generate && tsc"
},
"devDependencies": {
"@openapitools/openapi-generator-cli": "^2.13.4",
"typescript": "^5.5.0"
},
"dependencies": {
"axios": "^1.7.2"
}
}
```
 
### Using the generated client in MFEs
 
```typescript
// mfe/shared/api-client/src/index.ts — re-exports generated clients
 
import { Configuration } from './generated/re/configuration';
import { PurchaseOrdersApi } from './generated/re/api';
import { DashboardApi } from './generated/ars/api';
 
export function createReApi(basePath: string, getToken: () => string | null) {
const config = new Configuration({
basePath,
accessToken: () => getToken() ?? '',
});
return new PurchaseOrdersApi(config);
}
 
export function createArsApi(basePath: string, getToken: () => string | null) {
const config = new Configuration({
basePath,
accessToken: () => getToken() ?? '',
});
return new DashboardApi(config);
}
 
// Re-export all generated model types
export type {
PurchaseOrderSummary,
WorkflowStatus,
ApproveRequest,
ApproveResponse,
ErrorResponse,
} from './generated/re/model';
 
export type {
StoreManagerDashboardResponse,
ScPlannerDashboardResponse,
ExecutiveDashboardResponse,
SupplierPerformanceResponse,
} from './generated/ars/model';
```
 
### In an MFE component
 
```typescript
// CORRECT — using generated types
import { createReApi, PurchaseOrderSummary } from '@smartretail/api-client';
import { useAuth } from '@smartretail/auth';
 
function PendingApprovalTable() {
const { token } = useAuth();
const api = createReApi(import.meta.env.VITE_RE_API_URL, () => token);
 
const [orders, setOrders] = useState<PurchaseOrderSummary[]>([]);
 
// PurchaseOrderSummary type is generated from re-api.yaml — fully typed
const loadOrders = async () => {
const response = await api.listPurchaseOrders({ status: 'PENDING_APPROVAL' });
setOrders(response.data.orders ?? []);
};
}
 
// WRONG — hand-written interface duplicating the generated type
interface PurchaseOrder { ← never do this
poId: string;
workflowStatus: string;
}
```
 
---
 
## Maven Build Commands
 
```bash
# Generate sources only (runs openapi-generator, no compile)
mvn generate-sources -pl services/re
 
# Full build with tests
mvn clean verify -pl services/re -am
 
# Skip integration tests (faster for development)
mvn clean test -pl services/re -am
 
# Run only integration tests
mvn failsafe:integration-test -pl services/re
 
# Generate coverage report
mvn jacoco:report -pl services/re
open services/re/target/site/jacoco/index.html
 
# Build all services
mvn clean package -DskipTests -pl services/sis,services/ims,services/re,services/ars -am
 
# Validate OpenAPI spec before generation
mvn openapi-generator:validate -pl services/re
```
 
---
 
## .gitignore additions
 
Add to root `.gitignore`:
```gitignore
# Generated API code — never commit
services/*/target/generated-sources/
mfe/shared/api-client/src/generated/
 
# Maven build output
target/
*.jar
!*-sources.jar
 
# Generated SpringDoc spec
**/v3/api-docs/
```
 
 