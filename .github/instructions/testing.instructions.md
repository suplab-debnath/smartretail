---
applyTo: "**/*Test.java,**/*IT.java,**/*.spec.ts,**/*.test.ts,**/*.test.tsx"
---

# Testing Instructions -- SmartRetail

## Java unit tests (@Test suffix)
- `@ExtendWith(MockitoExtension.class)` only -- no Spring context
- One test class per use case or per adapter class
- Method names: `should{Outcome}When{Condition}`
- Mock all outbound ports with `@Mock`, inject with `@InjectMocks`
- Test both success paths AND all exception/error paths
- Never assert on log output -- test behaviour only

```java
@ExtendWith(MockitoExtension.class)
class ApprovePurchaseOrderUseCaseTest {
    @Mock PurchaseOrderRepository repository;
    @Mock EventBridgePurchaseOrderPublisher publisher;
    @InjectMocks ApprovePurchaseOrderUseCase useCase;

    @Test
    void shouldApproveWhenStatusIsPendingApproval() { ... }

    @Test
    void shouldThrowInvalidStatusTransitionWhenStatusIsDraft() { ... }

    @Test
    void shouldThrowOptimisticLockExceptionWhenVersionMismatch() { ... }
}
```

## Java integration tests (@IT suffix)
- `@SpringBootTest + @Testcontainers + @ActiveProfiles("test")`
- Extend `RepositoryTestBase` (shared PostgreSQLContainer with `withReuse(true)`)
- Flyway runs automatically in test profile
- Test all SQL paths: insert, select, update with version check, conflict handling

## ArchUnit tests (required in every service)
File: `{ServiceName}ArchitectureTest.java`
Required rules:
1. `domainHasNoAwsImports` -- no `software.amazon.*` in `..domain..` or `..port..`
2. `useCasesOnlyDependOnPorts` -- `..domain.usecase..` may not depend on `..adapter..`
3. `controllersDoNotCallRepositories` -- `..adapter.inbound.rest..` may not depend on `..adapter.outbound..`

## Coverage targets
| Service | Critical paths |
|---|---|
| SIS | Dedup (409 on duplicate), new event (202), Firehose batch envelope |
| IMS | Decrement, severity thresholds (CRITICAL/HIGH/MEDIUM), optimistic lock |
| RE | Auto-approve vs manual path, status transition guards, optimistic lock miss |
| ARS | Parallel queries, data freshness, STORE_MANAGER scope (own DC only) |

## TypeScript / React tests (Vitest + RTL)
- `vi.mock('@smartretail/api-client')` to mock all API calls
- Test: loading state (skeleton visible), error state (ErrorBanner visible), success state (data visible)
- No snapshot tests -- test rendered text and user interactions
- `userEvent.click()` for interaction tests, not `fireEvent`
- `screen.getByRole()` preferred over `getByTestId()`
