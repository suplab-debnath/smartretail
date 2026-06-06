# RCA Tracker
Single source of truth for all known issues. Update status as fixes land.
Original detail: see `project_demo-backend-500-rca.md` (archived).

---

## Open Issues

### RCA-004 — V9 Migration Not Applied to Demo RDS
**Status**: Open — needs AWS credentials + RDS SG ingress to run
**Env affected**: demo
**Root cause**: V9 migration (adds `sku_id`, `dc_id`, `quantity` to `supplier.supplier_pos`)
not applied to demo RDS. `supplier-orders` endpoint returns 500 on column-not-found.
**Fix**: Open RDS SG ingress from local IP → run Flyway → close SG rule.

```bash
# 1. Get RDS SG ID from AWS console or:
SG_ID=$(AWS_PROFILE=smartretail-dev aws ssm get-parameter \
  --name /smartretail/demo/sg-rds-id --query Parameter.Value --output text)
MY_IP=$(curl -s https://checkip.amazonaws.com)
AWS_PROFILE=smartretail-dev aws ec2 authorize-security-group-ingress \
  --group-id "$SG_ID" --protocol tcp --port 5432 --cidr "${MY_IP}/32"

# 2. Run migrations
JAVA_HOME=$(/usr/libexec/java_home) AWS_PROFILE=smartretail-dev \
  bash environments/demo/scripts/run-flyway-aws-demo.sh demo

# 3. Revoke the temporary rule
AWS_PROFILE=smartretail-dev aws ec2 revoke-security-group-ingress \
  --group-id "$SG_ID" --protocol tcp --port 5432 --cidr "${MY_IP}/32"
```

**Requires**: No rebuild — DB migration only

---

## 🔧 Code Fixed — Pending ECR Push + ECS Redeploy

### RCA-002 — ARS JWT Parsing (403 on SC Planner endpoints)
**Status**: Code fixed ✅ — needs ECR push + ECS redeploy
**Files changed**:
- `backend/services/ars/src/main/resources/application-demo.yml` — removed OAuth2 auto-config exclusion
- `backend/services/ars/src/main/java/com/smartretail/ars/config/SecurityConfig.java` — added `.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))` to `demoSecurity`
**Deploy command**:
```bash
JAVA_HOME=$(/usr/libexec/java_home) \
  bash environments/demo/scripts/deploy-services-demo.sh \
  --env demo --profile smartretail-dev --services ars --skip-build --wait
```

### RCA-003 — IMS NPE on Pagination (500 on `/v1/inventory/positions`)
**Status**: Code fixed ✅ — needs ECR push + ECS redeploy
**Files changed**:
- `backend/services/ims/src/main/java/com/smartretail/ims/adapter/inbound/rest/InventoryController.java` — null-safe defaults for `page`/`size` in both `listInventoryPositions` and `listStockAlerts`
**Deploy command**:
```bash
JAVA_HOME=$(/usr/libexec/java_home) \
  bash environments/demo/scripts/deploy-services-demo.sh \
  --env demo --profile smartretail-dev --services ims --skip-build --wait
```

---

## ✅ Resolved Issues

### RCA-001 — API Gateway Path Strip Bug
**Status**: ✅ Resolved
**Env affected**: demo
**Root cause**: API Gateway stage path not stripped before forwarding to ECS targets.
**Fix applied:** environments/demo/infra/lib/api-stack.ts corrected; Min-ApiStack redeployed.
