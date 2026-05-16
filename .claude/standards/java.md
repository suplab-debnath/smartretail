# Java Coding Standards
 
Apply these to every Java file generated in this project.
 
---
 
## Java Version and Features
 
Use Java 21. Always use modern Java features where they reduce boilerplate.
 
### Records for value objects and domain models
```java
// CORRECT — immutable value object as record
public record SalesTransaction(
UUID transactionId,
String storeId,
String skuId,
String dcId,
int quantity,
BigDecimal unitPrice,
Channel channel,
Instant eventTimestamp
) {
// Compact constructor for validation
public SalesTransaction {
Objects.requireNonNull(transactionId, "transactionId must not be null");
Objects.requireNonNull(skuId, "skuId must not be null");
if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
if (unitPrice.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
}
 
public enum Channel { POS, ECOMMERCE }
}
 
// WRONG — mutable POJO with getters/setters
public class SalesTransaction {
private UUID transactionId;
public void setTransactionId(UUID id) { this.transactionId = id; }
}
```
 
### Sealed interfaces for discriminated unions
```java
// Use sealed interfaces for result types
public sealed interface IngestionResult
permits IngestionResult.Accepted, IngestionResult.Duplicate {
 
record Accepted(UUID transactionId) implements IngestionResult {}
record Duplicate(UUID transactionId) implements IngestionResult {}
}
 
// Use in switch expressions
return switch (result) {
case IngestionResult.Accepted a -> ResponseEntity.accepted()
.body(Map.of("transactionId", a.transactionId(), "status", "ACCEPTED"));
case IngestionResult.Duplicate d -> ResponseEntity.status(409)
.body(errorResponse("DUPLICATE_EVENT", d.transactionId().toString()));
};
```
 
### Text blocks for SQL
```java
// CORRECT
private static final String INSERT_SALES_EVENT = """
INSERT INTO sales.sales_events
(transaction_id, event_date, store_id, sku_id, dc_id,
quantity, unit_price, channel, event_timestamp)
VALUES
(:transactionId, :eventDate, :storeId, :skuId, :dcId,
:quantity, :unitPrice, :channel, :eventTimestamp)
""";
 
// WRONG — concatenated strings
String sql = "INSERT INTO sales.sales_events (transaction_id, " +
"event_date, " + "store_id) VALUES (?, ?, ?)";
```
 
### Pattern matching
```java
// CORRECT
if (result instanceof IngestionResult.Duplicate dup) {
log.debug("Duplicate event skipped: {}", dup.transactionId());
}
 
// WRONG — cast manually
if (result instanceof IngestionResult.Duplicate) {
IngestionResult.Duplicate dup = (IngestionResult.Duplicate) result;
}
```
 
---
 
## Dependency Injection
 
Constructor injection only. No `@Autowired` on fields. No `@Autowired` on
setters. No field injection of any kind.
 
```java
// CORRECT
@Service
public class SalesIngestionUseCase implements SalesEventPort {
 
private final EventStorePort eventStore;
private final EventPublisherPort eventPublisher;
private final RawArchivePort rawArchive;
private final IdempotencyPort idempotency;
 
public SalesIngestionUseCase(
EventStorePort eventStore,
EventPublisherPort eventPublisher,
RawArchivePort rawArchive,
IdempotencyPort idempotency) {
this.eventStore = eventStore;
this.eventPublisher = eventPublisher;
this.rawArchive = rawArchive;
this.idempotency = idempotency;
}
}
 
// WRONG
@Service
public class SalesIngestionUseCase {
@Autowired
private EventStorePort eventStore; ← forbidden
}
```
 
---
 
## Null Safety
 
- Return `Optional<T>` from all repository find-by-id and find-by-query methods
- Never return `null` from a public method
- Use `Objects.requireNonNull` in record compact constructors
- Use `@NonNull` / `@Nullable` from `org.springframework.lang` on public API
```java
// CORRECT
Optional<PurchaseOrder> findById(UUID poId);
Optional<ReplenishmentRule> findRule(String skuId, String dcId);
 
// WRONG
PurchaseOrder findById(UUID poId); // may return null
```
 
---
 
## Exception Handling
 
### Domain exceptions
Define in `domain/model/exception/`:
```java
// Unchecked — no checked exceptions in domain
public class InvalidStatusTransitionException extends RuntimeException {
private final WorkflowStatus currentStatus;
 
public InvalidStatusTransitionException(String message, WorkflowStatus currentStatus) {
super(message);
this.currentStatus = currentStatus;
}
 
public WorkflowStatus getCurrentStatus() { return currentStatus; }
}
 
public class OptimisticLockException extends RuntimeException {
public OptimisticLockException(String message) { super(message); }
}
 
public class EntityNotFoundException extends RuntimeException {
public EntityNotFoundException(String entity, Object id) {
super(entity + " not found: " + id);
}
}
```
 
### Controller exception handler
Each service has a `@RestControllerAdvice` that maps domain exceptions to HTTP:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
 
@ExceptionHandler(InvalidStatusTransitionException.class)
public ResponseEntity<ErrorResponse> handleInvalidTransition(
InvalidStatusTransitionException ex, HttpServletRequest req) {
return ResponseEntity.status(409).body(ErrorResponse.builder()
.errorCode("INVALID_STATUS_TRANSITION")
.message(ex.getMessage())
.traceId(MDC.get("traceId"))
.timestamp(Instant.now())
.build());
}
 
@ExceptionHandler(OptimisticLockException.class)
public ResponseEntity<ErrorResponse> handleOptimisticLock(
OptimisticLockException ex, HttpServletRequest req) {
return ResponseEntity.status(409).body(ErrorResponse.builder()
.errorCode("CONCURRENT_MODIFICATION")
.message("Resource was modified concurrently. Retry with fresh data.")
.traceId(MDC.get("traceId"))
.timestamp(Instant.now())
.build());
}
 
@ExceptionHandler(EntityNotFoundException.class)
public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex) {
return ResponseEntity.status(404).body(ErrorResponse.builder()
.errorCode("NOT_FOUND")
.message(ex.getMessage())
.traceId(MDC.get("traceId"))
.timestamp(Instant.now())
.build());
}
 
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
log.error("Unexpected error", ex); // stack trace to logs only
return ResponseEntity.status(500).body(ErrorResponse.builder()
.errorCode("INTERNAL_ERROR")
.message("An unexpected error occurred") // never expose ex.getMessage()
.traceId(MDC.get("traceId"))
.timestamp(Instant.now())
.build());
}
}
```
 
---
 
## Logging
 
Structured JSON logs. SLF4J + Logback.
 
### Logback configuration
`src/main/resources/logback-spring.xml` — JSON output via Logstash encoder:
```xml
<configuration>
<springProperty name="SERVICE_NAME" source="spring.application.name"/>
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
<customFields>{"service":"${SERVICE_NAME}","env":"${SMARTRETAIL_ENV:-local}"}</customFields>
</encoder>
</appender>
<root level="INFO">
<appender-ref ref="JSON"/>
</root>
<logger name="com.smartretail" level="DEBUG" additivity="false">
<appender-ref ref="JSON"/>
</logger>
</configuration>
```
 
### MDC context — set at entry points
Every SQS listener and REST controller sets MDC before any logging:
```java
// In SQS listener
@SqsListener("${smartretail.sqs.ims-sales-queue-url}")
public void onSalesEvent(@Payload SalesTransactionEventDto event,
@Header("X-Correlation-Id") String correlationId) {
try {
MDC.put("traceId", correlationId);
MDC.put("service", "IMS");
MDC.put("eventType", "SalesTransactionEvent");
MDC.put("skuId", event.getSkuId());
MDC.put("dcId", event.getDcId());
inventoryUpdatePort.processSalesEvent(toDomain(event));
} finally {
MDC.clear();
}
}
 
// In REST controller — use filter, not manual MDC
// Add TraceIdFilter that reads X-Amzn-Trace-Id header and puts in MDC
```
 
### PII masking rule
Any value being logged that comes from a field named `*email*`, `*phone*`, or `*contact*`
must be masked before logging. Use a utility method:
```java
public static String maskPii(String value) {
if (value == null || value.length() <= 4) return "***";
return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
}
```
 
### Log level guidance
- `DEBUG` — individual record processing, SQL parameter values (local only)
- `INFO` — service lifecycle, PO state transitions, domain events published
- `WARN` — business rule not met (no replenishment rule found, duplicate event)
- `ERROR` — infrastructure failure, unexpected exception
---
 
## Spring Boot Configuration
 
### application.yml baseline (all services)
```yaml
spring:
application:
name: ${SERVICE_NAME}
datasource:
url: jdbc:postgresql://${RDS_PROXY_ENDPOINT:localhost}:5432/smartretail?currentSchema=${DB_SCHEMA}
username: ${DB_USERNAME}
hikari:
maximum-pool-size: 10
minimum-idle: 2
connection-timeout: 30000
idle-timeout: 600000
max-lifetime: 1800000
pool-name: SmartRetail-${SERVICE_NAME}-Pool
flyway:
enabled: false # migrations run separately, not at service startup
 
management:
endpoints:
web:
exposure:
include: health,info,metrics,prometheus
base-path: /actuator
endpoint:
health:
show-details: never
probes:
enabled: true # liveness + readiness for ECS health checks
metrics:
tags:
service: ${SERVICE_NAME}
env: ${SMARTRETAIL_ENV:local}
export:
cloudwatch:
enabled: ${CLOUDWATCH_METRICS_ENABLED:false}
namespace: SmartRetail/${SERVICE_NAME}
 
server:
port: 8080
shutdown: graceful
tomcat:
accesslog:
enabled: false # structured JSON logging via Logback handles this
 
logging:
config: classpath:logback-spring.xml
```
 
---
 
## Code Organisation Rules
 
- Max method length: 30 lines. Extract to private methods if longer.
- Max class length: 200 lines for use cases and controllers. Repositories may be longer.
- No static utility classes with business logic — put logic in domain objects.
- Package-private constructors on domain records — use factory methods for complex creation.
- All public methods on port interfaces must have Javadoc.
- All `@RestController` classes must have `@Tag` annotation for OpenAPI.
---
 
## Naming Conventions
 
| Element | Convention | Example |
|---------|-----------|---------|
| Domain records | PascalCase noun | `SalesTransaction`, `PurchaseOrder` |
| Use cases | PascalCase + UseCase suffix | `GeneratePurchaseOrderUseCase` |
| Inbound ports | PascalCase + Port suffix | `SalesEventPort`, `ApprovalPort` |
| Outbound ports | PascalCase + Port suffix | `EventStorePort`, `EventPublisherPort` |
| Adapters | PascalCase + technology + Adapter/Repository/Publisher | `EventBridgePurchaseOrderPublisher` |
| Controllers | PascalCase + Controller | `ReplenishmentController` |
| Exceptions | PascalCase + Exception | `InvalidStatusTransitionException` |
| Test classes | ClassUnderTest + Test | `ApprovalWorkflowUseCaseTest` |
| IT classes | ClassUnderTest + IT | `ReplenishmentRepositoryIT` |
| Constants | UPPER_SNAKE_CASE | `MAX_RETRY_ATTEMPTS` |
| SQL constants | UPPER_SNAKE_CASE + _SQL suffix | `INSERT_SALES_EVENT_SQL` |
 