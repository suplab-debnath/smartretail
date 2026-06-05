---
name: demo-backend-500-rca
description: "RCA, investigation notes, and pending fixes for SC Planner demo backend 500/403 errors after CloudFront/Cognito login was working"
metadata: 
  node_type: memory
  type: project
  originSessionId: 37ee2733-b409-463e-a49a-2701c9319b26
---

# Demo Backend 500/403 RCA — 2026-06-05

**Status**: Investigation complete, fixes identified, partially applied. Resuming after break.

**Why:** SC Planner MFE at `https://d1y8d25pr5rmx.cloudfront.net/sc-planner/` loads and Cognito login works, but API calls return 500 or 403. No data is displayed.

---

## Fix Already Applied ✅

**API Gateway path strip bug** — `api-stack.ts` was forwarding only the path suffix to backends.

- `GET /v1/dashboard/sc-planner` → backend received `/sc-planner` (Spring: NoResourceFoundException → 500)
- Root cause: `nlbProxyIntegration` URI was `http://{nlb}:{port}/{proxy}` — `{proxy}` only captures the suffix after the resource path, stripping `/v1/dashboard/`
- Fix: Changed integration URI to `http://{nlb}:{port}{pathPrefix}/{proxy}` where `pathPrefix` is `/v1/dashboard`, `/v1/inventory`, etc.
- File: `environments/demo/infra/lib/api-stack.ts`
- **Deployed**: `Min-ApiStack` redeployed 2026-06-05, confirmed UPDATE_COMPLETE

---

## Remaining Issues — Three Root Causes

### RCA 1: 403 on `/v1/dashboard/sc-planner` and `/v1/dashboard/supplier-performance`

**Symptom:** SC_PLANNER-role endpoints return 403.

**Root cause:** In `demo` Spring profile, `SecurityConfig.demoSecurity` has `anyRequest().permitAll()` but does NOT configure `.oauth2ResourceServer(...)`. Therefore, the Cognito JWT Bearer token sent by the MFE is **never parsed** by Spring Security. `DashboardController.extractRoles()` falls through to the header fallback:

```java
String header = httpRequest.getHeader("X-Dev-Role");
return Set.of(header != null ? header : "EXECUTIVE");
```

`X-Dev-Role` is removed in AWS mode by `fetchJson` (CORS compliance), so the fallback is always `"EXECUTIVE"`. `PLANNER_ROLES = {"SC_PLANNER", "ADMIN"}` → `hasAnyRole` fails → 403.

**User `scp1@test.com` is in Cognito group `SC_PLANNER`** — the JWT has the right claim, Spring just doesn't decode it.

**Fix required:**

1. In `backend/services/ars/src/main/resources/application-demo.yml` — **remove** the `OAuth2ResourceServerAutoConfiguration` exclusion:
   ```yaml
   # DELETE this entire block:
   spring:
     autoconfigure:
       exclude:
         - org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration
   ```

2. In `backend/services/ars/src/main/java/com/smartretail/ars/config/SecurityConfig.java` — add JWT parsing to `demoSecurity`:
   ```java
   @Bean
   @Profile("demo")
   public SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
       http.csrf(AbstractHttpConfigurer::disable)
           .cors(cors -> cors.configurationSource(corsConfigurationSource()))
           .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
           .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {})); // ADD THIS LINE
       return http.build();
   }
   ```

   The `issuer-uri` is already configured in `application-aws.yml` (loaded via `profiles.group.demo: [aws]`):
   ```yaml
   spring.security.oauth2.resourceserver.jwt.issuer-uri: ${COGNITO_ISSUER_URI}
   ```

   `COGNITO_ISSUER_URI` is injected into ECS tasks by `compute-stack.ts` (`commonEnv`). Spring will auto-create a `NimbusJwtDecoder` that fetches the Cognito JWKS at startup. Valid — Cognito endpoint is always reachable.

3. Requires **ARS rebuild + ECR push + ECS redeploy**.

---

### RCA 2: 500 on `/v1/inventory/positions`

**Symptom:** `GET /v1/inventory/positions?dcId=DC-LONDON` → 500. `GET /v1/inventory/alerts?status=ACTIVE` → 200 (same service, same DB).

**Root cause:** NullPointerException from null `page`/`size` auto-unboxing.

`InventoryController.listInventoryPositions` receives `Integer page, Integer size` (nullable, from query params). When the MFE calls without explicit page/size params, both are `null`. The method calls `inventoryRepo.findPositions(dcId, skuId, page, size)` where `findPositions` takes primitive `int` — Java auto-unboxes `null Integer` → NPE → caught by GlobalExceptionHandler → 500.

File: `backend/services/ims/src/main/java/com/smartretail/ims/adapter/inbound/rest/InventoryController.java`

**Fix required** (one-liner in controller):
```java
@Override
public ResponseEntity<InventoryPositionPage> listInventoryPositions(
        String dcId, String skuId, Integer page, Integer size) {
    int p = page != null ? page : 0;   // ADD
    int s = size != null ? size : 20;  // ADD
    var positions = inventoryRepo.findPositions(dcId, skuId, p, s);  // use p, s
    long total = inventoryRepo.countPositions(dcId, skuId);
    ...
}
```

Requires **IMS rebuild + ECR push + ECS redeploy**.

---

### RCA 3: 500 on `/v1/dashboard/supplier-orders`

**Symptom:** SQL error — column does not exist.

**Root cause:** `SupplierReadRepository.SUPPLIER_ORDERS_SQL` references `sp.sku_id`, `sp.dc_id`, `sp.quantity` on `supplier.supplier_pos`. These columns were **added by V9 migration** (`V9__seed_data_flow9.sql`), but **V9 has not been applied to the demo RDS instance**.

`supplier_pos` schema at V5 only has: `supplier_po_id, supplier_id, po_id, po_status, confirmed_at, dispatched_at, eta, created_at, updated_at`.

V9 adds:
```sql
ALTER TABLE supplier.supplier_pos
    ADD COLUMN IF NOT EXISTS sku_id   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS dc_id    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS quantity INTEGER;
```

**Fix required:** Run V9 migration on demo RDS.

The RDS is in a private subnet — SG only allows ingress from `sgEcsTasks`. Cannot connect directly from local machine.

**How to run:**
- Temporarily add your public IP to the `sgRds` security group, then run:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home)
  AWS_PROFILE=smartretail-dev bash environments/demo/scripts/run-flyway-aws-demo.sh demo
  ```
- Or run as an ECS one-off task.

**RDS SG ID:** stored in SSM at `/smartretail/demo/sg-rds-id` (but SSM lookup failed — may need to check AWS console for the actual SG ID).

Also: the `SUPPLIER_ORDERS_SQL` does `CAST(sp.po_id AS UUID)` but `po_id` is `VARCHAR(50)` in the schema. PostgreSQL can cast valid UUID strings to UUID type. As long as seed data in `po_id` contains valid UUID format strings (it does — V7/V9 seed data uses UUID literals), this cast works. Not a bug.

---

## Deployment Order When Resuming

1. **Run V9 migration** on demo RDS (no rebuild needed, just DB change)
2. **Fix `IMS InventoryController`** — add `p`/`s` null defaults  
3. **Fix `ARS SecurityConfig` + `application-demo.yml`** — enable JWT parsing in demo profile
4. **Rebuild and push IMS + ARS images** to ECR
5. **Force-redeploy ECS services** (`ims` and `ars`)
6. Verify all endpoints return data

## Commands for Rebuild + Redeploy

```bash
# Step 1 — Run migrations (need RDS SG ingress from local IP first)
JAVA_HOME=$(/usr/libexec/java_home) AWS_PROFILE=smartretail-dev \
  bash environments/demo/scripts/run-flyway-aws-demo.sh demo

# Step 2 — Rebuild IMS + ARS
cd backend/services/ims && mvn clean package -DskipTests -q
cd backend/services/ars && mvn clean package -DskipTests -q

# Step 3 — Push images (via deploy-services-demo.sh or Makefile targets)
AWS_PROFILE=smartretail-dev DEMO_ENV=demo \
  bash environments/demo/scripts/deploy-services-demo.sh ims ars

# Step 4 — Force ECS redeploy
AWS_PROFILE=smartretail-dev aws ecs update-service \
  --cluster smartretail-demo --service smartretail-ims-demo \
  --force-new-deployment --region us-east-1
AWS_PROFILE=smartretail-dev aws ecs update-service \
  --cluster smartretail-demo --service smartretail-ars-demo \
  --force-new-deployment --region us-east-1
```

---

## Other Endpoints — Status

| Endpoint | Status | Notes |
|---|---|---|
| `/v1/inventory/alerts?status=ACTIVE` | **200 ✅** | Working — IMS, no role check, no null params |
| `/v1/dashboard/sc-planner` | **403** → fix RCA1 | ARS, role check blocks SC_PLANNER |
| `/v1/dashboard/supplier-performance` | **403** → fix RCA1 | ARS, role check blocks SC_PLANNER |
| `/v1/dashboard/supplier-orders` | **500** → fix RCA3 | ARS, missing DB columns |
| `/v1/inventory/positions` | **500** → fix RCA2 | IMS, null page/size NPE |

**Why does `supplier-performance` show as 403 but `supplier-orders` shows as 500?**

Both have `hasAnyRole(PLANNER_ROLES)` → should both 403. The `supplier-orders` 500 is likely an API Gateway level timeout on the first request (NLB connection settling after CDK update). Once fixed, `supplier-orders` should also show 403 until RCA1 and RCA3 are fixed.

---

## Related Files

- `environments/demo/infra/lib/api-stack.ts` — **already fixed** (path prefix)
- `backend/services/ars/src/main/resources/application-demo.yml` — needs OAuth2 exclusion removed
- `backend/services/ars/src/main/java/com/smartretail/ars/config/SecurityConfig.java` — needs JWT parsing in demoSecurity
- `backend/services/ims/src/main/java/com/smartretail/ims/adapter/inbound/rest/InventoryController.java` — needs null defaults for page/size
- `backend/migrations/src/main/resources/db/migration/V9__seed_data_flow9.sql` — needs to be applied to demo RDS
