# Developer Guide
 
For human engineers working on the prototype.
Read this after reading CLAUDE.md.
 
---
 
## Onboarding (Day 1)
 
### 1. Read these documents in order
 
| Document | Time | Why |
|----------|------|-----|
| CLAUDE.md | 10 min | Repository overview and architecture rules |
| ARCHITECTURE.md | 20 min | All confirmed decisions — read fully before writing code |
| SCHEMAS.md | 15 min | Database schemas — understand ownership before writing any SQL |
| API_CONTRACTS.md | 20 min | REST contracts — understand before implementing controllers |
| FLOWS.md | 15 min | The six prototype flows — these are your acceptance criteria |
 
### 2. Set up local environment
 
Follow docs/LOCAL_DEV.md Mode 1 exactly.
Estimated time: 45 minutes including Docker pulls.
 
### 3. Verify your setup
 
```bash
# All services healthy
curl http://localhost:8080/actuator/health  # {"status":"UP"}
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health  # DFS
curl http://localhost:8085/actuator/health  # SUP
 
# Seed data loaded
PGPASSWORD=local_dev_password psql -h localhost -U smartretail_admin -d smartretail \
    -c "SELECT COUNT(*) FROM inventory.inventory_positions;"
# Expected: 60
 
# Publish a test event end-to-end
python3 scripts/shared/publish-pos-event.py \
    --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
    --sku-id SKU-BEV-001 --dc-id DC-LONDON \
    --store-id STORE-001 --quantity 5 --unit-price 8.50 \
    --direct-api http://localhost:8080
# Expected: ✅ SIS accepted event: 202
```
 
---
 
## Daily Workflow
 
### Starting a work session
 
```bash
# 1. Pull latest
git pull origin main
 
# 2. Start local environment
make local-up
 
# 3. Start only the services you are working on
# (no need to start all 4 every time)
make local-re    # if working on RE
make local-ars   # if working on ARS
 
# 4. Start MFE dev server if working on UI
make local-mfe-scp
```
 
### Before pushing code
 
```bash
# 1. Run unit tests
mvn test -pl backend/services/{your-service}
 
# 2. Check for architecture violations
mvn verify -pl backend/services/{your-service}
# The ArchUnit tests enforce hexagonal boundaries (see below)
 
# 3. Run relevant flow smoke test
make test-flow1   # or test-flow2, test-flow3 etc.
```
 
### Ending a work session
 
```bash
make local-down   # stops containers, keeps volumes
# or
make local-clean  # stops containers, deletes volumes (use when starting fresh tomorrow)
```
 
---
 
## Architecture Rules in Code
 
These rules are enforced by ArchUnit tests in each service.
They will fail your build if violated.
They exist because the architecture decisions in ARCHITECTURE.md must be
enforced mechanically — code review alone is not enough.
 
### Rule 1: No AWS imports in domain
 
```java
// ArchUnit test — runs in every service's test suite
@Test
void domainShouldNotDependOnAWS() {
    JavaClasses classes = new ClassFileImporter()
        .importPackages("com.smartretail." + SERVICE_NAME + ".domain");
 
    noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAPackage("software.amazon..")
        .check(classes);
}
```
 
**If this test fails:** You added an AWS SDK import to a domain class.
Move the AWS call to an adapter class that implements an outbound port.
 
### Rule 2: Adapters must implement ports
 
```java
@Test
void adaptersShouldImplementPorts() {
    JavaClasses classes = new ClassFileImporter()
        .importPackages("com.smartretail." + SERVICE_NAME + ".adapter.outbound");
 
    classes()
        .that().resideInAPackage("..adapter.outbound..")
        .should().implement(resideInAPackage("..port.outbound.."))
        .check(classes);
}
```
 
**If this test fails:** You created an adapter class that does not implement
an outbound port interface. Create the port interface first.
 
### Rule 3: Controllers only call inbound ports
 
```java
@Test
void controllersShouldOnlyCallInboundPorts() {
    JavaClasses classes = new ClassFileImporter()
        .importPackages("com.smartretail." + SERVICE_NAME + ".adapter.inbound.rest");
 
    noClasses()
        .that().resideInAPackage("..adapter.inbound.rest..")
        .should().dependOnClassesThat()
        .resideInAPackage("..adapter.outbound..")
        .check(classes);
}
```
 
**If this test fails:** A REST controller is directly calling a repository or
AWS SDK client. It must only call an inbound port interface.
 
---
 
## Common Mistakes and How to Fix Them
 
### Mistake: Cross-schema query in ARS
 
```java
// WRONG — cross-schema JOIN
String sql = """
    SELECT ip.on_hand, df.p50
    FROM inventory.inventory_positions ip
    JOIN forecasting.demand_forecasts df
        ON ip.sku_id = df.sku_id AND ip.dc_id = df.dc_id
    WHERE ip.dc_id = ?
    """;
```
 
```java
// CORRECT — two separate queries, merge in Java
List<InventoryPosition> positions = inventoryRepo.findByDc(dcId);
List<DemandForecast> forecasts = forecastRepo.findLatestByDc(dcId);
// Merge by skuId+dcId in Java using Map<String, DemandForecast>
```
 
**Why:** Cross-schema JOINs couple ARS to the internal structure of other
bounded contexts. A schema migration in IMS would require ARS changes.
More importantly: it violates the bounded context isolation rule.
 
---
 
### Mistake: Forgetting optimistic locking
 
```java
// WRONG — no version check
jdbcTemplate.update(
    "UPDATE replenishment.purchase_orders SET workflow_status = ? WHERE po_id = ?",
    newStatus, poId
);
```
 
```java
// CORRECT — include version in WHERE, check rows updated
int rows = jdbcTemplate.update(
    "UPDATE replenishment.purchase_orders SET workflow_status = ?, version = version + 1 " +
    "WHERE po_id = ? AND version = ?",
    newStatus, poId, currentVersion
);
if (rows == 0) throw new OptimisticLockException("Concurrent modification: " + poId);
```
 
**Why:** Multiple SC Planners can approve/reject the same PO simultaneously.
Without optimistic locking, both updates succeed and one silently overwrites the other.
 
---
 
### Mistake: Approving from DRAFT status
 
```java
// WRONG — allowing approve from any status
public void approve(UUID poId, String approvedBy) {
    replenishmentRepo.updateStatus(poId, APPROVED, approvedBy, now());
}
```
 
```java
// CORRECT — validate pre-condition
public void approve(UUID poId, String approvedBy) {
    PurchaseOrder po = replenishmentRepo.findById(poId).orElseThrow();
    if (po.getWorkflowStatus() != PENDING_APPROVAL) {
        throw new InvalidStatusTransitionException(
            "PO cannot be approved from status " + po.getWorkflowStatus() +
            ". Status must be PENDING_APPROVAL.",
            po.getWorkflowStatus()
        );
    }
    // ... proceed
}
```
 
**Why:** A DRAFT PO has not been evaluated against the auto-approve threshold yet.
Allowing approve from DRAFT bypasses the threshold check entirely.
 
---
 
### Mistake: JWT validation only at API Gateway
 
```java
// WRONG — trusting API Gateway to have validated the role
@PostMapping("/orders/{poId}/approve")
public ResponseEntity<?> approve(@PathVariable UUID poId) {
    // Assuming API Gateway validated SC_PLANNER role
    return approvalPort.approve(poId, "unknown");
}
```
 
```java
// CORRECT — validate role at service layer too
@PostMapping("/orders/{poId}/approve")
public ResponseEntity<?> approve(
        @PathVariable UUID poId,
        @RequestHeader("Authorization") String bearerToken) {
    JwtClaims claims = jwtValidator.validate(bearerToken);
    if (!claims.hasRole("SC_PLANNER") && !claims.hasRole("ADMIN")) {
        return ResponseEntity.status(403)
            .body(errorResponse("UNAUTHORIZED", "SC_PLANNER or ADMIN role required"));
    }
    return approvalPort.approve(poId, claims.getSub());
}
```
 
**Why:** Defense in depth. API Gateway is the first line but not the only line.
A misconfigured API Gateway route or a direct VPC network path could bypass it.
 
---
 
### Mistake: Direct RDS connection (not via RDS Proxy)
 
```yaml
# WRONG — connecting directly to RDS instance
spring:
  datasource:
    url: jdbc:postgresql://smartretail-rds.xxx.us-east-1.rds.amazonaws.com:5432/smartretail
```
 
```yaml
# CORRECT — connecting via RDS Proxy endpoint
spring:
  datasource:
    url: jdbc:postgresql://${RDS_PROXY_ENDPOINT}:5432/smartretail
```
 
**Why:** Direct connections bypass connection pooling. Under ECS horizontal
scaling, direct connections can exhaust the RDS max_connections limit (which
is instance-class bounded). RDS Proxy multiplexes connections safely.
 
---
 
## Debugging Tips
 
### Check EventBridge events are routing correctly
 
```bash
# Local (LocalStack)
aws --endpoint-url=http://localhost:4566 events \
    list-rules --event-bus-name smartretail-events-local
 
# Check a specific queue has messages
aws --endpoint-url=http://localhost:4566 sqs \
    get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/smartretail-ims-sales-local \
    --attribute-names ApproximateNumberOfMessages
```
 
### Check RDS data directly
 
```bash
# Local
PGPASSWORD=local_dev_password psql -h localhost -U smartretail_admin -d smartretail
 
# Useful queries:
\dn                                          -- list schemas
\dt sales.*                                  -- list tables in sales schema
SELECT * FROM replenishment.purchase_orders ORDER BY created_at DESC LIMIT 5;
SELECT workflow_status, COUNT(*) FROM replenishment.purchase_orders GROUP BY 1;
SELECT * FROM inventory.stock_alerts WHERE status = 'ACTIVE' ORDER BY raised_at DESC LIMIT 10;
```
 
### Check CloudWatch Logs (AWS mode)
 
```bash
# Tail logs for a service
aws logs tail /smartretail/re/dev --follow --format short
 
# Search for errors
aws logs filter-log-events \
    --log-group-name /smartretail/re/dev \
    --filter-pattern "ERROR" \
    --start-time $(date -d '1 hour ago' +%s000)
 
# Find a specific transaction
aws logs filter-log-events \
    --log-group-name /smartretail/sis/dev \
    --filter-pattern '"transactionId":"your-uuid-here"'
```
 
### Service not starting (AWS mode)
 
```bash
# Check ECS service events
aws ecs describe-services \
    --cluster smartretail-dev \
    --services smartretail-re-dev \
    --query 'services[0].events[:10]'
 
# Check stopped tasks for error reason
aws ecs list-tasks \
    --cluster smartretail-dev \
    --service-name smartretail-re-dev \
    --desired-status STOPPED
 
aws ecs describe-tasks \
    --cluster smartretail-dev \
    --tasks {task-arn} \
    --query 'tasks[0].stoppedReason'
```
 
### SQS message stuck / not consumed
 
```bash
# Check DLQ for failed messages
aws sqs get-queue-attributes \
    --queue-url $(aws ssm get-parameter --name /smartretail/dev/sqs/re-alert-queue-url \
        --query Parameter.Value --output text) \
    --attribute-names All | grep -E "Messages|Arn"
 
# Purge DLQ (prototype only — never in production)
DLQ_URL=$(aws ssm get-parameter \
    --name /smartretail/dev/sqs/re-alert-queue-url \
    --query Parameter.Value --output text | sed 's/\.fifo$/-dlq.fifo/')
aws sqs purge-queue --queue-url "$DLQ_URL"
```
 
---
 
## Running Unit Tests
 
Each service has unit tests covering:
- Domain use case logic (no Spring context, no AWS)
- Port interface contracts (mock adapters)
- ArchUnit architecture enforcement
 
```bash
# Run all unit tests
mvn test
 
# Run a specific service
mvn test -pl backend/services/re
 
# Run a specific test class
mvn test -pl backend/services/re \
    -Dtest=ApprovalWorkflowUseCaseTest
 
# Run with coverage report
mvn test jacoco:report -pl backend/services/re
open backend/services/re/target/site/jacoco/index.html
```
 
Test naming convention:
- `{UseCase}Test` — pure unit tests, no Spring
- `{Adapter}IT` — integration tests with TestContainers Postgres
- `{Controller}Test` — Spring MockMvc tests with mocked ports
 
### TestContainers for integration tests
 
Services use TestContainers for repository integration tests:
 
```java
// Base class for all repository tests
@SpringBootTest
@Testcontainers
abstract class RepositoryTestBase {
 
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("smartretail")
        .withUsername("test")
        .withPassword("test");
 
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```
 
---
 
## Git Workflow
 
```
main          ← production-ready prototype (deploys to AWS dev automatically)
feature/xxx   ← your work branch
```
 
```bash
# Start a new feature
git checkout -b feature/flow2-re-service
 
# Commit often with descriptive messages
git commit -m "feat(re): implement PO generation from inventory alert"
git commit -m "feat(re): add auto-approve threshold decision"
git commit -m "test(re): add GeneratePurchaseOrderUseCaseTest"
 
# Push and open PR
git push -u origin feature/flow2-re-service
# Open PR → assign reviewer → wait for CI → merge
```
 
PR checklist before merging:
- [ ] Unit tests pass (`mvn test`)
- [ ] ArchUnit tests pass (no architecture violations)
- [ ] Relevant flow smoke test passes (`make test-flowN`)
- [ ] No hardcoded AWS endpoints or credentials
- [ ] No cross-schema SQL JOINs in ARS
- [ ] Optimistic locking in any UPDATE to purchase_orders
- [ ] Structured JSON logging in new log statements
- [ ] No AWS imports in domain classes
 
---
 
## Environment Variables Reference
 
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | Yes | — | `local` or `aws` |
| `SMARTRETAIL_ENV` | Yes | — | `local`, `dev`, `prod` |
| `DB_SCHEMA` | Yes | — | Schema name for this service |
| `DB_USERNAME` | Yes | — | RDS user for this service |
| `RDS_PROXY_ENDPOINT` | aws only | — | From Parameter Store |
| `AWS_PROFILE` | aws only | default | AWS CLI profile |
| `AWS_REGION` | No | us-east-1 | AWS region |
 
---
 
## Asking Claude Code for Help
 
When using Claude Code with these documents:  
 
**Always tell it which flow you are building:**  
> "I am implementing Flow 2 (inventory alert → RE → PO generation).
> Read FLOWS.md section Flow 2, SCHEMAS.md replenishment schema,
> API_CONTRACTS.md RE section, and SERVICE_SPECS.md RE section.
> Generate the InventoryAlertFifoListener and GeneratePurchaseOrderUseCase."  

**Always reference the architecture rules:**  
> "Implement the ApprovalWorkflowUseCase following the hexagonal pattern
> in SERVICE_SPECS.md. The domain class must have zero AWS imports.
> Include optimistic locking as specified in SCHEMAS.md purchase_orders."

**Tell it your mode:**
> "I am running in LOCAL mode with LocalStack. Use application-local.yml
> configuration. Do not generate IAM auth config — use static credentials
> for LocalStack as shown in LOCAL_DEV.md LocalStackConfig."
 
 