---
mode: 'agent'
description: 'Task: Add a new REST endpoint -- OpenAPI YAML first, then Java implementation following hexagonal architecture'
tools: ['codebase', 'findTestFiles', 'new', 'runCommand', 'runTests', 'usages', 'workspaceDetails']
---

Add a new REST endpoint to a SmartRetail service following contract-first development.

## Endpoint details
- **Service:** ${input:service}
  (one of: sis, ims, re, ars, dfs, sup, pps)
- **HTTP method and path:** ${input:methodAndPath}
  (e.g. `POST /v1/replenishment/orders/{orderId}/approve`)
- **Purpose:** ${input:purpose}
  (e.g. `Approve a purchase order -- transitions PENDING_APPROVAL to APPROVED`)

## Contract-first workflow (follow in order)

### Step 1: Update OpenAPI YAML
File: `backend/services/{service}/src/main/resources/{service}-api.yaml`
- Add the new path and operation with `operationId`, `tags`, `summary`, `description`
- Add request body schema (if POST/PUT) with `additionalProperties: false`, `required:[]`, typed properties with `description` and `example`
- Add `X-Idempotency-Key` header if this endpoint has side effects
- Declare ALL response codes: 200/202/204 success + 400/401/403/404/409/500 errors

### Step 2: Regenerate stubs
```
mvn generate-sources -pl backend/services/{service}
```

### Step 3: Implement the generated interface
- Add method to the existing `@RestController` that implements the generated `*ApiDelegate`
- Controller delegates to inbound port interface only -- no business logic in controller
- Map domain exceptions to HTTP via existing `@RestControllerAdvice`

### Step 4: Add use-case logic
- Add method to the inbound port interface
- Implement in the use-case class in `domain/usecase/`
- Add any new outbound port method needed
- Implement outbound port in the persistence/messaging adapter

### Step 5: Write tests
- Unit test for the new use-case method (success + all error paths)
- Controller test with MockMvc (correct HTTP status, response shape, error cases)

After implementation, run `mvn verify -pl backend/services/{service}` to confirm green.
