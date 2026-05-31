---
mode: 'agent'
description: 'Workflow: Perform a structured PR review -- architecture compliance, security, tests, and style'
tools: ['codebase', 'findTestFiles', 'search', 'usages', 'workspaceDetails']
---

Perform a structured code review for this PR / set of changed files.

## PR / changes to review
${input:changes}
(e.g. `backend/services/re/` or a specific file path or PR description)

## Review structure

### 1. Architecture compliance (check all 8 rules)
For every Java file changed:
- [ ] No `software.amazon.*` imports in `domain/` or `port/` packages
- [ ] No cross-schema SQL JOINs
- [ ] No writes to a schema this service does not own
- [ ] RDS Proxy endpoint only in JDBC config
- [ ] JWT validation present in aws profile security config
- [ ] Approve endpoint checks `status == PENDING_APPROVAL`
- [ ] `CANCELLED` not used for planner rejection (must be `REJECTED`)
- [ ] Every UPDATE on `purchase_orders` has `WHERE version = :v` and increments version

### 2. Code quality
- [ ] Constructor injection used (no `@Autowired` on fields)
- [ ] No hand-written DTOs that duplicate generated classes
- [ ] `Optional<T>` returned from repository find methods
- [ ] Domain records used for value objects (not mutable POJOs)
- [ ] Methods < 30 lines, classes < 200 lines (repos may be longer)
- [ ] No `null` returned from public methods
- [ ] SQL uses named parameters (`:param`) -- no string concatenation

### 3. Security
- [ ] No hardcoded credentials, API keys, or passwords
- [ ] No `ex.getMessage()` in HTTP response body
- [ ] PII fields (`*email*`, `*phone*`, `*contact*`) masked before logging
- [ ] `additionalProperties: false` on all request body schemas (OpenAPI)

### 4. Test coverage
- [ ] Unit tests present for new use-case logic (success + failure paths)
- [ ] Repository IT tests for new SQL paths
- [ ] Controller tests for new HTTP endpoints
- [ ] ArchUnit test file exists in the service
- [ ] MFE tests for new components/hooks (loading, error, success states)

### 5. Frontend (if applicable)
- [ ] Functional components only
- [ ] Typed props (no `any`)
- [ ] Semantic Tailwind colour palette
- [ ] Data freshness badge on data-displaying components
- [ ] Stale data retained on error (no `setData(null)` in catch)
- [ ] Accessibility: `role="alert"`, `aria-label="Loading..."`, keyboard navigation

## Output format
For each finding:
- **[BLOCK]** -- must fix before merge (architecture violation, security issue, missing test for changed code)
- **[SUGGEST]** -- quality improvement (not blocking)
- **[NIT]** -- minor style (not blocking)

End with: overall recommendation (approve / approve with suggestions / request changes) and a one-line summary.
