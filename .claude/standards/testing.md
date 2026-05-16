# Testing Standards
 
---
 
## Test Types
 
| Type | Suffix | Context | Coverage target |
|------|--------|---------|-----------------|
| Use case unit | `Test` | None (Mockito only) | 80%+ domain package |
| Repository integration | `IT` | TestContainers Postgres | All SQL paths |
| Controller unit | `ControllerTest` | MockMvc + @WebMvcTest | All HTTP scenarios |
| Architecture | `ArchTest` | ArchUnit | Enforced in CI |
| E2E smoke | `smoke-test.sh` | LocalStack / AWS | 6 flows |
 
## Unit Test Rules
 
- `@ExtendWith(MockitoExtension.class)` only — no Spring context
- One test class per use case
- Method names: `should{Outcome}When{Condition}`
- Test all success paths AND all exception paths
- Never assert on log output — test behaviour only
## ArchUnit Rules (in every service)
 
```java
@AnalyzeClasses(packages = "com.smartretail.{service}")
class ArchitectureTest {
 
@ArchTest
ArchRule domainHasNoAwsImports =
noClasses().that().resideInAPackage("..domain..")
.should().dependOnClassesThat()
.resideInAnyPackage("software.amazon..", "com.amazonaws..");
 
@ArchTest
ArchRule controllersOnlyCallInboundPorts =
noClasses().that().resideInAPackage("..adapter.inbound.rest..")
.should().dependOnClassesThat()
.resideInAPackage("..adapter.outbound..");
 
@ArchTest
ArchRule noHandWrittenDtosDuplicatingGenerated =
noClasses().that().resideInAPackage("..adapter.inbound.rest..")
.and().haveSimpleNameEndingWith("Request")
.should().notBeAnnotatedWith(Deprecated.class)
.orShould().resideInAPackage("..generated..");
}
```
 
## TestContainers Integration Test Base
 
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
public abstract class RepositoryTestBase {
 
@Container
static final PostgreSQLContainer<?> POSTGRES =
new PostgreSQLContainer<>("postgres:15-alpine")
.withDatabaseName("smartretail")
.withUsername("test")
.withPassword("test")
.withReuse(true);
 
@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
registry.add("spring.datasource.username", POSTGRES::getUsername);
registry.add("spring.datasource.password", POSTGRES::getPassword);
registry.add("spring.flyway.enabled", () -> "true");
registry.add("spring.flyway.locations", () -> "classpath:db/migration");
}
}
```
 
## Required Test Coverage per Service
 
| Service | Must test |
|---------|----------|
| SIS | Dedup logic · idempotency · 202/409 responses |
| IMS | Inventory decrement · severity classification · optimistic lock |
| RE | Auto-approve threshold · PENDING_APPROVAL path · approve/reject transitions · optimistic lock |
| ARS | Parallel query execution · dataFreshness · STORE_MANAGER dcId scope |
 
 