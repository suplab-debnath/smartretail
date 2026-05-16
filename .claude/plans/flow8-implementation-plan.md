# Flow 8: Executive Dashboard — MAPE Trend + Forecast Accuracy

## Context

Flows 1 and 2 are complete (POS ingestion → inventory alerts → replenishment auto-approve). Flow 8 is a read-only analytics flow that proves the Executive Insights Dashboard renders MAPE trend data from pre-seeded `forecasting.forecast_runs`. It depends on V7__seed_data.sql being applied (30 days of COMPLETED forecast runs, MAPE trending from 0.1187 down to 0.0823).

Neither the ARS Java service nor the Executive MFE have any source code yet — both are empty stubs. This plan implements them end-to-end for the executive endpoint only, following the contract-first mandate.

---

## Scope (Flow 8 only)

**In:** `GET /v1/dashboard/executive` endpoint + Executive Insights Dashboard MFE  
**Out of scope this flow:** store-manager, sc-planner, supplier-performance endpoints (scaffolded but not wired)

---

## Step 1 — OpenAPI spec: `openapi/ars-api.yaml`

Write the OpenAPI 3.1 YAML. For Flow 8 we only need to fully define `GET /v1/dashboard/executive`. The other three paths (`store-manager`, `sc-planner`, `supplier-performance`) are stubbed as 501 Not Implemented so the generator produces all interface methods but implementation can come later.

Key schema shapes (from `docs/API_CONTRACTS.md`):

```yaml
GET /v1/dashboard/executive:
  security: [bearerAuth]
  responses:
    200:
      content: ExecutiveDashboardResponse
        kpis:
          forecastAccuracy:
            latestMape: number
            trend: IMPROVING | STABLE | DEGRADING
            history: [{runDate: date, mape: number}]   # last 30 entries
          stockoutFrequency:
            last30Days: integer
            trend: INCREASING | STABLE | DECREASING
          replenishmentCycleTime:
            averageDays: number
            trend: IMPROVING | STABLE | DEGRADING
        dataFreshness: datetime
    401: {}
    403: {}
```

Standard: follow `.claude/standards/openapi.md`. No hand-written DTOs — all from generator.

---

## Step 2 — ARS Maven: wire code generation

Edit `services/ars/pom.xml`:
- Remove `<skip>true</skip>` from spring-boot-maven-plugin repackage
- Add `openapi-generator-maven-plugin` block pointing at `openapi/ars-api.yaml`
- Enable Spring Boot server stub generation (`spring` generator, `useSpringBoot3=true`, `interfaceOnly=true`)
- Generated output: `target/generated-sources/openapi/`

Run `mvn generate-sources -pl services/ars` to verify stubs compile.

---

## Step 3 — ARS Java source (hexagonal, Flow 8 slice)

**Critical file:** `docs/SERVICE_SPECS.md` (ARS section, ~lines 587–700) defines the exact package layout. Implement only what Flow 8 needs.

```
services/ars/src/main/java/com/smartretail/ars/
├── ArsApplication.java
├── domain/
│   └── model/
│       └── ExecutiveDashboard.java          ← pure Java record, zero AWS imports
├── port/
│   ├── inbound/
│   │   └── ExecutiveDashboardPort.java
│   └── outbound/
│       ├── ForecastReadPort.java
│       └── InventoryReadPort.java
├── usecase/
│   └── ExecutiveDashboardUseCase.java       ← implements ExecutiveDashboardPort
│       # CompletableFuture.allOf(forecastFuture, stockoutFuture, cycleTimeFuture)
│       # Merge in Java — NO cross-schema SQL
└── adapter/
    ├── inbound/rest/
    │   └── DashboardController.java         ← implements generated ApiApi interface
    │       # JWT role check: EXECUTIVE | SC_PLANNER | ADMIN
    │       # Delegates to ExecutiveDashboardPort
    └── outbound/persistence/
        ├── ForecastReadRepository.java      ← reads forecasting.forecast_runs
        │   # SELECT run_id, DATE(started_at) AS run_date, mape
        │   # FROM forecasting.forecast_runs
        │   # WHERE status = 'COMPLETED'
        │   # ORDER BY started_at DESC LIMIT 30
        └── InventoryReadRepository.java     ← reads inventory.stock_alerts
            # SELECT COUNT(*) FROM inventory.stock_alerts
            # WHERE severity = 'CRITICAL' AND created_at >= NOW() - INTERVAL '30 days'
```

**Replenishment cycle time query** (reads `replenishment.purchase_orders`):
```sql
SELECT AVG(EXTRACT(EPOCH FROM (dispatched_at - created_at))/86400)
FROM replenishment.purchase_orders
WHERE workflow_status = 'DISPATCHED'
AND created_at >= NOW() - INTERVAL '90 days'
```

**Trend calculation** (pure Java, in domain use case):
- MAPE trend: compare last 7-day avg vs prior 7-day avg → IMPROVING / STABLE / DEGRADING
- Stockout trend: last 30 days count vs prior 30 days count
- Cycle time trend: last 30 days avg vs prior 30 days avg

**MAPE threshold:** 0.15 — referenced only in MFE rendering, not in backend.

---

## Step 4 — ARS `application.yml`

```yaml
# local profile
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smartretail
    username: smartretail
    password: smartretail
server:
  port: 8083

# aws profile  
spring:
  datasource:
    url: jdbc:postgresql://${RDS_PROXY_ENDPOINT}:5432/smartretail
```

Mock JWT bypass for local profile (matching pattern from SIS/IMS/RE).

---

## Step 5 — Makefile target

Add to root `Makefile`:
```make
local-ars:
	SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/ars
```

---

## Step 6 — Executive MFE: `mfe/executive/`

Scaffold with Vite + React 18 + TypeScript 5. Follow `.claude/standards/frontend.md`.

```
mfe/executive/
├── package.json          ← recharts, @aws-amplify/auth, tailwindcss, vite, react
├── vite.config.ts        ← proxy /v1 → localhost:8083 in local mode
├── tailwind.config.ts
├── index.html
└── src/
    ├── main.tsx
    ├── App.tsx            ← Router: / → /dashboard, /callback, /logout
    ├── config.ts          ← reads window.SMARTRETAIL_CONFIG
    ├── hooks/
    │   └── useExecutiveDashboard.ts   ← GET /v1/dashboard/executive, poll 5 min
    └── components/
        ├── KpiCard.tsx              ← reusable card: value + trend badge + color
        ├── MapeTrendChart.tsx       ← Recharts LineChart + reference line at 0.15
        ├── ForecastHistoryTable.tsx ← last 10 rows: Date | MAPE | Status | Duration
        └── ExecutiveDashboard.tsx   ← composes all three sections
```

**MapeChart specifics:**
- `<LineChart data={history}>` where `history` = `kpis.forecastAccuracy.history`
- X-axis: `dataKey="runDate"` (formatted as MM-DD)
- Y-axis: domain `[0, 0.20]`, tickFormatter `(v) => (v * 100).toFixed(1) + '%'`
- `<ReferenceLine y={0.15} stroke="red" strokeDasharray="4 4" label="Threshold" />`
- Tooltip: shows `runDate`, `(1 - mape) * 100`% accuracy, Within/Breached status

**KPI Card — Forecast Accuracy:**
- Value: `((1 - latestMape) * 100).toFixed(1) + '%'`
- Color: green if latestMape < 0.10, amber if 0.10–0.20, red if > 0.20
- Badge: trend string from API

**Auth (local mode):** Mock bypass — `useAuth` returns a hardcoded EXECUTIVE token. Matches pattern used by SIS/IMS/RE in local profile.

**Port:** `5175` (Vite dev server).

---

## Step 7 — Verify seed data applied

Ensure V7__seed_data.sql has been run (`make local-migrate && make local-seed`). The seed inserts 30 `forecast_runs` rows with MAPE 0.1187→0.0823. ARS queries these directly.

---

## Critical Files

| File | Action |
|------|--------|
| `openapi/ars-api.yaml` | CREATE |
| `services/ars/pom.xml` | EDIT — enable repackage, add openapi-generator plugin |
| `services/ars/src/main/java/com/smartretail/ars/**` | CREATE — full hexagonal slice |
| `services/ars/src/main/resources/application.yml` | CREATE |
| `mfe/executive/` | CREATE — Vite project scaffold + dashboard components |
| `Makefile` | EDIT — add `local-ars` target |

---

## Existing Patterns to Reuse

- JWT mock bypass: follow `services/sis/src/main/resources/application-local.yml` pattern (check the file for the exact property name)
- `useAuth` hook: `mfe/shared/auth/` — import `AuthProvider` and `useAuth` from there
- API client generation: `mfe/shared/api-client/` — after generating TypeScript stubs, import from there

---

## Verification (Observable Evidence per FLOWS.md §8)

Run after `make local-up && make local-migrate && make local-seed && make local-ars`:

| Check | Command / Action |
|-------|-----------------|
| 8.1 MAPE chart renders | Open http://localhost:5175 → Dashboard, confirm LineChart visible |
| 8.2 MAPE values match seed | Hover chart points — values should descend from ~11.8% to ~8.2% |
| 8.3 Accuracy status shown | "Within threshold" badge visible on KPI card (0.0823 < 0.15) |
| 8.4 Stockout KPI renders | Card shows count of CRITICAL alerts from seed data |
| 8.5 EXECUTIVE 403 on SC Planner | `curl -H "Authorization: Bearer <exec-token>" http://localhost:8083/v1/dashboard/sc-planner` → 403 |

```bash
# Quick API smoke test (no MFE needed)
curl -s http://localhost:8083/v1/dashboard/executive | jq '.kpis.forecastAccuracy.latestMape'
# expected: 0.0823

curl -s http://localhost:8083/v1/dashboard/executive | jq '.kpis.forecastAccuracy.history | length'
# expected: 30
```
