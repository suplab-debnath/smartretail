# Flow 8 Enhancement: Add Charts + History to Stockout Frequency & Cycle Time KPIs

## Context

Flow 8's Executive Dashboard currently has:
- 3 KPI cards (Forecast Accuracy, Stockout Frequency, Replenishment Cycle Time)
- 1 MAPE trend line chart
- 1 Forecast Accuracy history table

The enhancement adds visual charts and history tables for the other two KPIs, matching the depth of the Forecast Accuracy section.

**Two data gaps to fix first:**
1. Seed data has only 1 CRITICAL stock alert (all in the last 2 hours) — a 30-day chart would be nearly empty. A new Flyway migration adds ~12 CRITICAL alerts spread across the prior 30 days.
2. The `ReplenishmentReadRepository` queries `WHERE workflow_status = 'DISPATCHED'` — but all 50 seeded POs have `workflow_status = 'COMPLETED'`. Fix: include COMPLETED orders.

---

## Step 1 — New Flyway migration: `V8__seed_flow8_charts.sql`

File: `migrations/flyway/src/main/resources/db/migration/V8__seed_flow8_charts.sql`

Insert 12 CRITICAL stock alerts distributed across the last 30 days so the Stockout Frequency chart has a meaningful non-flat shape:

```sql
-- Critical alerts spread across last 30 days (2–4 per week, varying severity window)
INSERT INTO inventory.stock_alerts (alert_id, position_id, alert_type, severity,
    threshold_value, actual_value, status, raised_at)
SELECT
    gen_random_uuid(),
    ip.position_id,
    'LOW_STOCK',
    'CRITICAL',
    ip.reorder_point,
    ip.reorder_point - (2 + (row_number() OVER () % 5))::int,
    CASE WHEN row_number() OVER () % 3 = 0 THEN 'RESOLVED' ELSE 'ACTIVE' END,
    NOW() - ((3 + (row_number() OVER () * 2)) || ' days')::INTERVAL
FROM inventory.inventory_positions ip
WHERE ip.sku_id IN ('SKU-BEV-001','SKU-SNK-001','SKU-DRY-001','SKU-CHL-001')
  AND ip.dc_id IN ('DC-LONDON','DC-MANCHESTER','DC-BIRMINGHAM')
ORDER BY ip.sku_id, ip.dc_id
LIMIT 12;
```

This produces alerts at approximately days -3, -5, -7, -9, -11, -13, -15, -17, -19, -21, -23, -25 — a realistic declining frequency pattern.

After adding the migration, run: `make local-migrate`

---

## Step 2 — OpenAPI: update `openapi/ars-api.yaml`

Add `history` arrays to `StockoutFrequencyKpi` and `ReplenishmentCycleTimeKpi` schemas:

```yaml
StockoutAlertDataPoint:
  type: object
  required: [alertDate, criticalCount]
  additionalProperties: false
  properties:
    alertDate:
      type: string
      format: date
      description: Calendar date (UTC) for this data point
    criticalCount:
      type: integer
      format: int32
      description: Number of CRITICAL alerts raised on this date

CycleTimeDataPoint:
  type: object
  required: [weekStart, averageDays, poCount]
  additionalProperties: false
  properties:
    weekStart:
      type: string
      format: date
      description: Monday of the week (UTC)
    averageDays:
      type: number
      format: double
      description: Average cycle time in calendar days for this week
    poCount:
      type: integer
      format: int32
      description: Number of completed POs in this week

StockoutFrequencyKpi:
  # existing fields unchanged, add:
  properties:
    history:
      type: array
      description: Daily CRITICAL alert counts for the last 30 days, newest first
      items:
        $ref: '#/components/schemas/StockoutAlertDataPoint'

ReplenishmentCycleTimeKpi:
  # existing fields unchanged, add:
  properties:
    history:
      type: array
      description: Weekly average cycle time for the last 90 days, newest first
      items:
        $ref: '#/components/schemas/CycleTimeDataPoint'
```

Then regenerate: `mvn generate-sources -pl services/ars`

---

## Step 3 — Domain model: `ExecutiveDashboard.java`

Add two new record types (zero AWS imports):

```java
public record StockoutDataPoint(LocalDate alertDate, int criticalCount) {}
public record CycleTimeDataPoint(LocalDate weekStart, BigDecimal averageDays, int poCount) {}

// Update existing records:
public record StockoutFrequency(int last30Days, DirectionTrend trend, List<StockoutDataPoint> history) {}
public record ReplenishmentCycleTime(BigDecimal averageDays, Trend trend, List<CycleTimeDataPoint> history) {}
```

File: `services/ars/src/main/java/com/smartretail/ars/domain/model/ExecutiveDashboard.java`

---

## Step 4 — Outbound ports: add history methods

**`InventoryReadPort.java`** — add:
```java
List<StockoutDataPoint> findDailyCriticalAlertHistory(int days);
```

**`ReplenishmentReadPort.java`** — add:
```java
List<CycleTimeDataPoint> findWeeklyCycleTimeHistory(int days);
```

Files:
- `services/ars/src/main/java/com/smartretail/ars/port/outbound/InventoryReadPort.java`
- `services/ars/src/main/java/com/smartretail/ars/port/outbound/ReplenishmentReadPort.java`

---

## Step 5 — Repositories: new queries + fix DISPATCHED→COMPLETED

**`InventoryReadRepository.java`** — add daily history query:
```sql
SELECT DATE(raised_at) AS alert_date, COUNT(*) AS critical_count
FROM inventory.stock_alerts
WHERE severity = 'CRITICAL'
  AND raised_at >= NOW() - (:days || ' days')::INTERVAL
GROUP BY DATE(raised_at)
ORDER BY alert_date DESC
```

**`ReplenishmentReadRepository.java`** — two fixes:
1. Change `averageCycleTimeDays` query from `workflow_status = 'DISPATCHED'` → `workflow_status IN ('DISPATCHED','COMPLETED')`
2. Add weekly history query:
```sql
SELECT DATE_TRUNC('week', created_at)::DATE AS week_start,
       COUNT(*)::INT AS po_count,
       AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / 86400.0) AS average_days
FROM replenishment.purchase_orders
WHERE workflow_status IN ('DISPATCHED','COMPLETED')
  AND created_at >= NOW() - (:days || ' days')::INTERVAL
GROUP BY DATE_TRUNC('week', created_at)::DATE
ORDER BY week_start DESC
```

Files:
- `services/ars/src/main/java/com/smartretail/ars/adapter/outbound/persistence/InventoryReadRepository.java`
- `services/ars/src/main/java/com/smartretail/ars/adapter/outbound/persistence/ReplenishmentReadRepository.java`

---

## Step 6 — Use case: add two parallel history futures

**`ExecutiveDashboardUseCase.java`** — extend `assemble()` with two more futures:

```java
CompletableFuture<List<StockoutDataPoint>> stockoutHistoryFuture =
    CompletableFuture.supplyAsync(() -> inventoryReadPort.findDailyCriticalAlertHistory(30));

CompletableFuture<List<CycleTimeDataPoint>> cycleHistoryFuture =
    CompletableFuture.supplyAsync(() -> replenishmentReadPort.findWeeklyCycleTimeHistory(90));

CompletableFuture.allOf(forecastFuture, stockoutFuture, cycleTimeFuture,
                        stockoutHistoryFuture, cycleHistoryFuture).join();
```

Pass the history lists into the updated `StockoutFrequency` and `ReplenishmentCycleTime` constructors.

File: `services/ars/src/main/java/com/smartretail/ars/domain/usecase/ExecutiveDashboardUseCase.java`

---

## Step 7 — Controller: update `toResponse()` mapping

**`DashboardController.java`** — map the new history lists to generated model types:

```java
// StockoutFrequencyKpi
List<StockoutAlertDataPoint> stockoutHistory = domain.stockoutFrequency().history().stream()
    .map(p -> new StockoutAlertDataPoint(p.alertDate(), p.criticalCount()))
    .toList();
StockoutFrequencyKpi stockoutKpi = new StockoutFrequencyKpi(
    domain.stockoutFrequency().last30Days(),
    DirectionTrend.valueOf(domain.stockoutFrequency().trend().name()),
    stockoutHistory
);

// ReplenishmentCycleTimeKpi
List<CycleTimeDataPoint> cycleHistory = domain.replenishmentCycleTime().history().stream()
    .map(p -> new CycleTimeDataPoint(p.weekStart(), p.averageDays().doubleValue(), p.poCount()))
    .toList();
ReplenishmentCycleTimeKpi cycleKpi = new ReplenishmentCycleTimeKpi(
    domain.replenishmentCycleTime().averageDays().doubleValue(),
    Trend.valueOf(domain.replenishmentCycleTime().trend().name()),
    cycleHistory
);
```

File: `services/ars/src/main/java/com/smartretail/ars/adapter/inbound/rest/DashboardController.java`

---

## Step 8 — MFE: two new chart components

**`src/types.ts`** — add:
```typescript
export interface StockoutAlertDataPoint { alertDate: string; criticalCount: number }
export interface CycleTimeDataPoint { weekStart: string; averageDays: number; poCount: number }

// Update existing interfaces:
export interface StockoutFrequencyKpi {
  last30Days: number; trend: DirectionTrend
  history: StockoutAlertDataPoint[]
}
export interface ReplenishmentCycleTimeKpi {
  averageDays: number; trend: Trend
  history: CycleTimeDataPoint[]
}
```

**`src/components/StockoutChart.tsx`** — Recharts `BarChart`:
- X-axis: `alertDate` (MM-DD)
- Y-axis: integer count, domain `[0, auto]`
- Bar color: red (`#ef4444`)
- Tooltip: date + count

**`src/components/CycleTimeChart.tsx`** — Recharts `LineChart`:
- X-axis: `weekStart` (MM-DD)
- Y-axis: days, domain `[0, auto]`
- Reference line at `averageDays` overall average (gray dashed)
- Tooltip: week + avg days + PO count

**`src/components/StockoutHistoryTable.tsx`** — table: Date | CRITICAL Alerts | Status
- Status: `ACTIVE` count vs `RESOLVED` (from API data: use `criticalCount > 0` → red badge)

**`src/components/CycleTimeHistoryTable.tsx`** — table: Week | Avg Days | PO Count
- Color-code by avg: green < 4d, amber 4-7d, red > 7d

**`src/components/ExecutiveDashboard.tsx`** — insert below each KPI card section:
```tsx
{/* Below Stockout card */}
<StockoutChart history={stockoutFrequency.history} />
<StockoutHistoryTable history={stockoutFrequency.history} />

{/* Below Cycle Time card */}
<CycleTimeChart history={replenishmentCycleTime.history} />
<CycleTimeHistoryTable history={replenishmentCycleTime.history} />
```

New files:
- `mfe/executive/src/components/StockoutChart.tsx`
- `mfe/executive/src/components/CycleTimeChart.tsx`
- `mfe/executive/src/components/StockoutHistoryTable.tsx`
- `mfe/executive/src/components/CycleTimeHistoryTable.tsx`

Existing file to update:
- `mfe/executive/src/components/ExecutiveDashboard.tsx`
- `mfe/executive/src/types.ts`

---

## Critical Files

| File | Action |
|------|--------|
| `migrations/flyway/src/main/resources/db/migration/V8__seed_flow8_charts.sql` | CREATE |
| `openapi/ars-api.yaml` | EDIT — add 2 new schema types + history to existing KPI schemas |
| `services/ars/src/main/java/com/smartretail/ars/domain/model/ExecutiveDashboard.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/port/outbound/InventoryReadPort.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/port/outbound/ReplenishmentReadPort.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/adapter/outbound/persistence/InventoryReadRepository.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/adapter/outbound/persistence/ReplenishmentReadRepository.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/domain/usecase/ExecutiveDashboardUseCase.java` | EDIT |
| `services/ars/src/main/java/com/smartretail/ars/adapter/inbound/rest/DashboardController.java` | EDIT |
| `mfe/executive/src/types.ts` | EDIT |
| `mfe/executive/src/components/ExecutiveDashboard.tsx` | EDIT |
| `mfe/executive/src/components/StockoutChart.tsx` | CREATE |
| `mfe/executive/src/components/CycleTimeChart.tsx` | CREATE |
| `mfe/executive/src/components/StockoutHistoryTable.tsx` | CREATE |
| `mfe/executive/src/components/CycleTimeHistoryTable.tsx` | CREATE |

---

## Verification

```bash
# 1. Apply new migration (adds 12 CRITICAL alerts across last 30 days)
make local-migrate

# 2. Confirm seed data
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail -c \
  "SELECT DATE(raised_at), COUNT(*) FROM inventory.stock_alerts WHERE severity='CRITICAL' GROUP BY 1 ORDER BY 1 DESC;"
# expect: ~13 rows, spread across last 30 days

# 3. Confirm PO cycle time data
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail -c \
  "SELECT COUNT(*) FROM replenishment.purchase_orders WHERE workflow_status IN ('DISPATCHED','COMPLETED');"
# expect: 50

# 4. Regenerate + compile
mvn generate-sources compile -pl services/ars --no-transfer-progress

# 5. Restart ARS
make local-ars  # port 8083

# 6. Smoke test API
curl -s http://localhost:8083/v1/dashboard/executive | python3 -c "
import sys,json; d=json.load(sys.stdin)
sf=d['kpis']['stockoutFrequency']
ct=d['kpis']['replenishmentCycleTime']
print('Stockout last30Days:', sf['last30Days'])
print('Stockout history len:', len(sf['history']))
print('CycleTime averageDays:', ct['averageDays'])
print('CycleTime history len:', len(ct['history']))
"
# expect: stockout ~12-13 count, history ~13 entries; cycletime ~3-5 days, history ~13 weekly entries

# 7. Visual check in MFE
#    cd mfe/executive && npm run dev
#    Open http://localhost:5175 — should now show:
#    - Bar chart below Stockout card (red bars, declining toward today)
#    - Line chart below Cycle Time card (avg days per week)
#    - Two new history tables
```

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
