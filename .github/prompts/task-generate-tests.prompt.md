---
mode: 'agent'
description: 'Task: Generate comprehensive JUnit 5 / Vitest tests for a class, use case, or component'
tools: ['codebase', 'findTestFiles', 'new', 'runTests', 'testFailure', 'usages', 'workspaceDetails']
---

Generate comprehensive tests for the specified class or component.

## Target
${input:target}
(e.g. `backend/services/re/src/main/java/.../ApprovePurchaseOrderUseCase.java`
or `mfe/sc-planner/src/components/approval/ApprovalModal.tsx`)

## For Java classes -- generate:

### Unit test (*Test.java)
- `@ExtendWith(MockitoExtension.class)` -- no Spring context
- Method names: `should{Outcome}When{Condition}`
- Cover: all success paths, all exception paths, all guard clauses
- For use cases: mock all outbound ports with `@Mock`
- For state machines: test every valid transition AND every invalid transition (expect exception)

### Repository integration test (*IT.java) if the target is a repository
- Extend `RepositoryTestBase` (Testcontainers PostgreSQL)
- Test: insert, find by id (found and not found), update with correct version (rowsAffected=1), update with stale version (rowsAffected=0), conflict handling

### ArchUnit test (if not already present)
Check whether `src/test/java/.../ArchitectureTest.java` exists. If not, create it with the three standard rules.

## For React components / hooks -- generate:

### Vitest + RTL test (*.test.tsx)
- `vi.mock('@smartretail/api-client')` or mock the hook used by the component
- Test loading state: skeleton with `aria-label="Loading..."` is visible
- Test error state: `<ErrorBanner>` with `role="alert"` is visible; stale data still shown
- Test success state: data renders correctly with expected text/values
- Test interactions: user clicks, form submissions if applicable
- Use `screen.getByRole()` / `screen.getByLabelText()` -- no `getByTestId()`

After generating tests, run them and fix any compilation or runtime errors before finishing.
