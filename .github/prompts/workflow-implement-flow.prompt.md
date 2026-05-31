---
mode: 'agent'
description: 'Workflow: Implement an end-to-end SmartRetail flow -- schema, migrations, services, MFE, smoke tests'
tools: ['codebase', 'findTestFiles', 'new', 'runCommand', 'runTests', 'usages', 'workspaceDetails']
---

Implement an end-to-end SmartRetail flow from schema to smoke-test green.

## Flow to implement
**Flow number and name:** ${input:flow}
(e.g. `Flow 2 -- Inventory Alert -> RE Auto-Approve -> RDS State Transition`)

## Phase 1: Read the specification (do this first)
1. Read `docs/FLOWS.md` section for this flow -- note the observable evidence checklist
2. Read `docs/SCHEMAS.md` for all schemas involved
3. Read `docs/API_CONTRACTS.md` for all services involved
4. Read `docs/EVENT_ASYNC_SPEC.md` for any async events in this flow
5. State what components need to be created or modified before writing any code

## Phase 2: Schema (if new tables needed)
- Create `V{N+1}__*.sql` in `backend/migrations/src/main/resources/db/migration/`
- Schema-qualify all tables, add standard columns (`id`, `created_at`, `updated_at`, `version` if mutable)
- Run `make local-migrate` to apply

## Phase 3: Backend services (for each service involved)
For each service, follow the contract-first workflow:
1. Update `{service}-api.yaml` if new or changed endpoints
2. Run `mvn generate-sources -pl backend/services/{service}`
3. Implement inbound port interface in use case
4. Implement outbound port in persistence/event adapters
5. Wire controller to call inbound port
6. Write unit tests (use case) + IT tests (repository) + ArchUnit test
7. Run `mvn verify -pl backend/services/{service}` -- must be green

## Phase 4: Async wiring (if events involved)
- Verify EventBridge rule targets the correct SQS queue
- Verify SQS listener in consumer service uses the correct queue URL
- In local mode: verify `localstack-init.sh` creates the queue
- Test: publish event manually and verify consumer processes it

## Phase 5: MFE (if UI involved)
- Read `docs/MFE_SPECS.md` for the relevant MFE tab/section
- Create or update the hook for data fetching
- Create or update the component
- Run `npm run type-check && npm run lint` in the MFE directory

## Phase 6: Smoke test
- Start local stack: `make local-up && make local-migrate && make local-seed`
- Start required services (e.g. `make local-sis & make local-ims & make local-re`)
- Run `make test-flow{N}` and verify all observable evidence assertions pass
- Document any failing assertions with root cause

## Definition of done
All items in the observable evidence checklist in `docs/FLOWS.md` for this flow are green.
`mvn verify` is green for all affected services.
`make test-flow{N}` reports 0 failed assertions.
