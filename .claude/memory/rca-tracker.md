# RCA Tracker
Single source of truth for all known issues. Update status as fixes land.
Original detail: see `project_demo-backend-500-rca.md` (archived).

---

## Open Issues

### RCA-002 — ARS JWT Parsing (403 on SC Planner endpoints)
**Status**: Open
**Env affected**: demo
**Root cause**: `SecurityConfig.demoSecurity` does not parse Cognito JWT; falls back to
`X-Dev-Role` header which gets stripped by CORS — always resolves to "EXECUTIVE" role.

**Fix**:
1. Enable `.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))` in demo profile
2. Remove OAuth2 exclusion from `application-demo.yml`
**Requires**: ARS rebuild → ECR push → ECS redeploy (demo)
**Owner**: —


### RCA-003 — IMS NPE on Pagination (500 on `/v1/inventory/positions`)
**Status**: Open
**Env affected**: demo
**Root cause**: `InventoryController.listInventoryPositions` receives nullable
`Integer page, Integer size`; NPE on auto-unbox when caller omits query params.
**Fix**:

```java
int p = page != null ? page : 0;
int s = size != null ? size : 20;
```

**Requires**: IMS rebuild → ECR push → ECS redeploy (demo)
**Owner**: —


### RCA-004 — V9 Migration Not Applied to Demo RDS
**Status**: Open
**Env affected**: demo
**Root cause**: V9 migration (adds sku_id , dc_id , quantity to supplier.supplier_pos )
not applied to demo RDS. SUP endpoints return 500 on column-not-found.
**Fix**: Open RDS SG ingress from local IP → run Flyway → close SG rule.
**Requires**: No rebuild — DB migration only
**Owner**: —

---

## ✅ Resolved Issues

### RCA-001 — API Gateway Path Strip Bug
**Status**: ✅ Resolved
**Env affected**: demo
**Root cause**: API Gateway stage path not stripped before forwarding to ECS targets.
**Fix applied:** environments/demo/infra/lib/api-stack.ts corrected; Min-ApiStack redeployed.
