# Flow 3 & Flow 4 Implementation Plan

## Context

Flows 1 and 2 are complete: SIS ingests POS events, IMS updates inventory and raises stock alerts, RE generates purchase orders (auto-approving below threshold, PENDING_APPROVAL above it) and publishes PurchaseOrderEvents.

**Flow 3** (SC Planner MFE → RE approve/reject → RDS → EventBridge) is ~90% done on the backend. The RE service has both use cases fully implemented with optimistic locking, idempotency, and EventBridge publishing. The SC Planner MFE is scaffolded with all 8 tab components and hooks. The remaining gap is MFE wiring: Vite proxy and local config injection.

**Flow 4** (ARS → Store Manager Dashboard MFE) has two major gaps: ARS `GET /v1/dashboard/store-manager` returns 501, and the Store Manager MFE directory is completely empty.

---

## Flow 3 — Remaining Work

### 1. SC Planner MFE: Vite Proxy
**File**: `mfe/sc-planner/vite.config.ts`

Add proxy rules so relative API calls route to the correct local services:
```ts
proxy: {
  '/v1/replenishment': 'http://localhost:8082',
  '/v1/dashboard':     'http://localhost:8083',
  '/v1/inventory':     'http://localhost:8081',
  '/v1/forecast':      'http://localhost:8084',
  '/v1/supplier':      'http://localhost:8085',
}
```
MFE dev server already runs on port 5174 (per CLAUDE.md).

### 2. SC Planner MFE: Local Config
**File**: `mfe/sc-planner/public/config.js`

```js
window.SMARTRETAIL_CONFIG = {
  apiGatewayEndpoint: '',   // empty = relative URLs, Vite proxy handles routing
  cognitoPoolId:      'local-bypass',
  cognitoClientId:    'local-bypass',
  cognitoDomain:      '',
  env:                'local',
};
```

### 3. Verify Hook Wire-up
Hooks in `mfe/sc-planner/src/hooks/` must:
- Use relative URLs (no hardcoded `localhost:8082`)
- Send `X-Dev-Role: SC_PLANNER` header on all requests
- Send `X-Idempotency-Key: crypto.randomUUID()` on approve/reject mutations
- Include `{ version }` in the `ApproveRequest` / `RejectRequest` body

Check `ApprovalWorkflowsTab.tsx` — the approve button must read `po.version` from the fetched PO and pass it in the POST body. The PO list response from `usePendingApprovals` must include `version` per the `re-api.yaml` schema.

### 4. Flow 3 Observable Evidence Checklist
| # | Evidence | Where to verify |
|---|----------|----------------|
| 1 | MFE lists PENDING_APPROVAL POs | Browser tab "Approvals" |
| 2 | Approve request hits RE with idempotency key + version | RE logs |
| 3 | 403 returned for wrong role | Use `X-Dev-Role: STORE_MANAGER`, expect 403 |
| 4 | 409 returned on double-approve | Approve same PO twice |
| 5 | RDS: workflow_status → APPROVED, version incremented | psql query |
| 6 | EventBridge event published | LocalStack logs |
| 7 | MFE removes PO from pending list | Optimistic update in UI |

---

## Flow 4 — ARS Backend

### 1. OpenAPI: Add StoreManagerDashboardResponse
**File**: `openapi/ars-api.yaml`

Add under `components/schemas`:
```yaml
AlertKpi:
  type: object
  properties:
    criticalCount: { type: integer }
    highCount:     { type: integer }
    mediumCount:   { type: integer }
    totalActive:   { type: integer }

StockAlertSummary:
  type: object
  properties:
    alertId:      { type: string, format: uuid }
    skuId:        { type: string }
    dcId:         { type: string }
    alertType:    { type: string }
    severity:     { type: string }
    onHand:       { type: integer }
    reorderPoint: { type: integer }
    raisedAt:     { type: string, format: date-time }

StoreManagerDashboardResponse:
  type: object
  properties:
    dcId:                    { type: string }
    alertKpi:                { $ref: '#/components/schemas/AlertKpi' }
    totalOnHandUnits:        { type: integer, format: int64 }
    pendingReplenishmentCount: { type: integer }
    forecastCoveragePct:     { type: number, format: double }
    alerts:
      type: array
      items: { $ref: '#/components/schemas/StockAlertSummary' }
    alertsPage:        { type: integer }
    alertsTotalPages:  { type: integer }
    dataFreshness:     { type: string, format: date-time }
```

Update the `/v1/dashboard/store-manager` path: add `dcId` (required, query param), add `page`/`size` query params, change response from 501 to `200 StoreManagerDashboardResponse`.

Run `mvn generate-sources` in `services/ars/` after YAML change.

### 2. Domain Model
**File**: `services/ars/src/main/java/com/smartretail/ars/domain/model/StoreManagerDashboard.java`

```java
public record StoreManagerDashboard(
    String dcId,
    AlertKpi alertKpi,
    long totalOnHandUnits,
    int pendingReplenishmentCount,
    BigDecimal forecastCoveragePct,
    List<StockAlertSummary> alerts,
    int alertsPage,
    int alertsTotalPages,
    Instant dataFreshness
) {
    public record AlertKpi(int criticalCount, int highCount, int mediumCount, int totalActive) {}
    public record StockAlertSummary(UUID alertId, String skuId, String dcId, String alertType,
                                    String severity, int onHand, int reorderPoint, Instant raisedAt) {}
}
```

### 3. Inbound Port
**File**: `services/ars/src/main/java/com/smartretail/ars/port/inbound/StoreManagerDashboardPort.java`

```java
public interface StoreManagerDashboardPort {
    StoreManagerDashboard assemble(String dcId, int page, int size);
}
```

### 4. New Outbound Port Methods (add to existing interfaces)

**`InventoryReadPort`** — add:
```java
AlertKpi   countActiveAlertsByDc(String dcId);
List<StockAlertSummary> findActiveAlertsByDc(String dcId, int page, int size);
int        countActiveAlertsByDcTotal(String dcId);
long       sumOnHandByDc(String dcId);
```

**`ReplenishmentReadPort`** — add:
```java
int countPendingApprovalsByDc(String dcId);
```

**`ForecastReadPort`** — add:
```java
BigDecimal forecastCoverageByDc(String dcId);  // % SKUs with any forecast in next 7 days
```

### 5. Use Case
**File**: `services/ars/src/main/java/com/smartretail/ars/domain/usecase/StoreManagerDashboardUseCase.java`

Pattern: matches existing `ScPlannerDashboardUseCase` — 4 parallel `CompletableFuture` reads joined with `CompletableFuture.allOf()`, no cross-schema joins.

```java
@Service
public class StoreManagerDashboardUseCase implements StoreManagerDashboardPort {

    // constructor injection: inventoryReadPort, replenishmentReadPort, forecastReadPort

    @Override
    public StoreManagerDashboard assemble(String dcId, int page, int size) {
        var alertKpiFuture      = CompletableFuture.supplyAsync(() -> inventoryReadPort.countActiveAlertsByDc(dcId));
        var onHandFuture        = CompletableFuture.supplyAsync(() -> inventoryReadPort.sumOnHandByDc(dcId));
        var pendingPoFuture     = CompletableFuture.supplyAsync(() -> replenishmentReadPort.countPendingApprovalsByDc(dcId));
        var forecastCovFuture   = CompletableFuture.supplyAsync(() -> forecastReadPort.forecastCoverageByDc(dcId));
        var alertsFuture        = CompletableFuture.supplyAsync(() -> inventoryReadPort.findActiveAlertsByDc(dcId, page, size));
        var alertsTotalFuture   = CompletableFuture.supplyAsync(() -> inventoryReadPort.countActiveAlertsByDcTotal(dcId));

        CompletableFuture.allOf(alertKpiFuture, onHandFuture, pendingPoFuture,
                                forecastCovFuture, alertsFuture, alertsTotalFuture).join();

        int total = alertsTotalFuture.join();
        int totalPages = (int) Math.ceil((double) total / size);

        return new StoreManagerDashboard(
            dcId,
            alertKpiFuture.join(),
            onHandFuture.join(),
            pendingPoFuture.join(),
            forecastCovFuture.join(),
            alertsFuture.join(),
            page,
            totalPages,
            Instant.now()
        );
    }
}
```

### 6. Repository SQL (no cross-schema joins)

**`InventoryReadRepository`** — new methods:
```sql
-- countActiveAlertsByDc: severity breakdown
SELECT severity, COUNT(*) FROM inventory.stock_alerts
WHERE dc_id = :dcId AND status = 'ACTIVE'
GROUP BY severity

-- sumOnHandByDc
SELECT COALESCE(SUM(on_hand), 0) FROM inventory.inventory_positions
WHERE dc_id = :dcId

-- findActiveAlertsByDc (sorted CRITICAL→HIGH→MEDIUM)
SELECT a.alert_id, a.sku_id, a.dc_id, a.alert_type, a.severity,
       p.on_hand, p.reorder_point, a.raised_at
FROM inventory.stock_alerts a
JOIN inventory.inventory_positions p ON a.position_id = p.position_id
WHERE a.dc_id = :dcId AND a.status = 'ACTIVE'
ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END
LIMIT :size OFFSET :offset

-- countActiveAlertsByDcTotal
SELECT COUNT(*) FROM inventory.stock_alerts
WHERE dc_id = :dcId AND status = 'ACTIVE'
```
Note: the JOIN above is within the `inventory` schema only — no cross-schema join.

**`ReplenishmentReadRepository`** — new method:
```sql
SELECT COUNT(*) FROM replenishment.purchase_orders
WHERE dc_id = :dcId AND workflow_status = 'PENDING_APPROVAL'
```

**`ForecastReadRepository`** — new method:
```sql
-- % of distinct SKUs in inventory that have ≥1 forecast in next 7 days for this DC
SELECT
  COUNT(DISTINCT f.sku_id)::numeric /
  NULLIF((SELECT COUNT(DISTINCT sku_id) FROM inventory.inventory_positions WHERE dc_id = :dcId), 0) * 100
FROM forecasting.demand_forecasts f
WHERE f.dc_id = :dcId
  AND f.forecast_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 7
```
⚠️ This query crosses `forecasting` and `inventory` schemas via subquery — violates rule C1.
**Fix**: Split into two separate queries in the use case:
- `ForecastReadPort.countSkusWithForecastByDc(dcId)` → queries `forecasting.*` only
- `InventoryReadPort.countDistinctSkusByDc(dcId)` → queries `inventory.*` only
- Compute ratio in `StoreManagerDashboardUseCase` in Java.

### 7. Controller
**File**: `services/ars/src/main/java/com/smartretail/ars/adapter/inbound/rest/DashboardController.java`

Replace the 501 stub:
```java
@GetMapping("/store-manager")
public ResponseEntity<StoreManagerDashboardResponse> getStoreManagerDashboard(
        @RequestParam String dcId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        HttpServletRequest request) {
    validateRole(request, Set.of("STORE_MANAGER", "ADMIN"));
    StoreManagerDashboard domain = storeManagerDashboardPort.assemble(dcId, page, size);
    return ResponseEntity.ok(StoreManagerResponseMapper.toResponse(domain));
}
```

Inject `StoreManagerDashboardPort` via constructor alongside existing ports.

### 8. Response Mapper
**File**: `services/ars/src/main/java/com/smartretail/ars/adapter/inbound/rest/StoreManagerResponseMapper.java`

Maps `StoreManagerDashboard` → generated `StoreManagerDashboardResponse` API model.

---

## Flow 4 — Store Manager MFE

All files in `mfe/store-manager/`. Follow the SC Planner MFE pattern exactly.

### File List
```
mfe/store-manager/
├── package.json
├── vite.config.ts          ← port 5173, proxy /v1/dashboard → localhost:8083
├── tsconfig.json
├── tailwind.config.js
├── postcss.config.js
├── index.html
├── public/
│   └── config.js           ← window.SMARTRETAIL_CONFIG local values
└── src/
    ├── main.tsx
    ├── App.tsx              ← BrowserRouter, AuthProvider, /dashboard route
    ├── types.ts             ← StoreManagerDashboardResponse, AlertKpi, StockAlertSummary
    ├── index.css
    ├── hooks/
    │   └── useStoreManagerDashboard.ts  ← GET /v1/dashboard/store-manager?dcId=X, 60s poll
    └── components/
        ├── StoreDashboard.tsx           ← main shell, wires DcSelector + KpiRow + AlertList + ForecastSummary
        ├── DcSelector.tsx               ← dropdown: DC-LONDON, DC-MANCHESTER, DC-BIRMINGHAM
        ├── KpiRow.tsx                   ← 4 KPI cards
        ├── KpiCard.tsx                  ← single card (label, value, optional sub-breakdown)
        ├── AlertList.tsx                ← table of alerts, paginated 10/page
        ├── SeverityBadge.tsx            ← reuse pattern from SC Planner
        ├── ForecastSummary.tsx          ← forecastCoveragePct display
        └── DataFreshnessIndicator.tsx   ← "Last updated X seconds ago"
```

### Key Component Details

**`DcSelector`**: controlled dropdown, initial value `DC-LONDON`. On change, resets page to 0 and re-fetches.

**`KpiRow`**: 4 `KpiCard` instances:
1. Low Stock Alerts — primary value = `alertKpi.totalActive`, sub-breakdown = CRITICAL/HIGH/MEDIUM counts
2. On-Hand Units — `totalOnHandUnits`
3. Pending Replenishment Orders — `pendingReplenishmentCount`
4. Forecast Coverage — `forecastCoveragePct.toFixed(1)%`

**`AlertList`**: table columns: SKU, DC, Type, Severity, On Hand, Reorder Point, Raised At. Pagination controls (prev/next). Severity sorted server-side.

**`useStoreManagerDashboard`**:
```ts
function useStoreManagerDashboard(dcId: string, page: number, size = 10) {
  const [data, setData] = useState<StoreManagerDashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const fetch = () => {
      fetchDashboard(dcId, page, size).then(d => { if (!cancelled) { setData(d); setLoading(false); } });
    };
    fetch();
    const id = setInterval(fetch, 60_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [dcId, page, size]);

  return { data, loading, error };
}
```
Sends `X-Dev-Role: STORE_MANAGER` header.

**`public/config.js`**:
```js
window.SMARTRETAIL_CONFIG = {
  apiGatewayEndpoint: '',
  cognitoPoolId:      'local-bypass',
  cognitoClientId:    'local-bypass',
  cognitoDomain:      '',
  env:                'local',
};
```

**`vite.config.ts`**:
```ts
proxy: {
  '/v1/dashboard': 'http://localhost:8083',
}
```

---

## Build Order

1. **`openapi/ars-api.yaml`** — add schemas and fix store-manager endpoint spec
2. **`mvn generate-sources` in `services/ars/`** — regenerate Java stubs
3. **ARS domain model** — `StoreManagerDashboard.java`
4. **ARS ports** — add methods to `InventoryReadPort`, `ReplenishmentReadPort`, `ForecastReadPort`; new `StoreManagerDashboardPort`
5. **ARS repositories** — add SQL methods to `InventoryReadRepository`, `ReplenishmentReadRepository`, `ForecastReadRepository`
6. **ARS use case** — `StoreManagerDashboardUseCase`
7. **ARS controller + mapper** — wire up, remove 501
8. **SC Planner MFE** — `vite.config.ts` proxy + `public/config.js` (Flow 3 wire-up)
9. **Store Manager MFE** — full app scaffold

## Verification

### Flow 3
```bash
# 1. Start RE and ARS
make local-re & make local-ars

# 2. Start SC Planner MFE
cd mfe/sc-planner && npm run dev

# 3. Open http://localhost:5174, go to Approvals tab
# → Should list PENDING_APPROVAL POs from RE

# 4. Click Approve → check RE logs for idempotency key + version
# → PO disappears from list (optimistic update)

# 5. psql: SELECT workflow_status, version FROM replenishment.purchase_orders WHERE po_id = '...';
# → workflow_status = APPROVED, version incremented

# 6. LocalStack logs: PurchaseOrderEvent published
```

### Flow 4
```bash
# 1. Start ARS (with store-manager endpoint now implemented)
make local-ars

# 2. Test endpoint directly
curl "http://localhost:8083/v1/dashboard/store-manager?dcId=DC-LONDON" \
  -H "X-Dev-Role: STORE_MANAGER"
# → 200 with alertKpi, totalOnHandUnits, forecastCoveragePct, alerts[]

# 3. Start Store Manager MFE
cd mfe/store-manager && npm run dev

# 4. Open http://localhost:5173
# → DcSelector shows DC-LONDON default
# → 4 KPI cards populated
# → Alert table shows CRITICAL first, paginated
# → DataFreshnessIndicator shows last-updated time
# → Page auto-refreshes every 60 seconds
```

---

## Critical Files

| File | Action |
|------|--------|
| `openapi/ars-api.yaml` | Add 3 new schemas, fix store-manager endpoint |
| `services/ars/src/.../model/StoreManagerDashboard.java` | Create |
| `services/ars/src/.../port/inbound/StoreManagerDashboardPort.java` | Create |
| `services/ars/src/.../port/outbound/InventoryReadPort.java` | Add 4 methods |
| `services/ars/src/.../port/outbound/ReplenishmentReadPort.java` | Add 1 method |
| `services/ars/src/.../port/outbound/ForecastReadPort.java` | Add 2 methods |
| `services/ars/src/.../usecase/StoreManagerDashboardUseCase.java` | Create |
| `services/ars/src/.../persistence/InventoryReadRepository.java` | Add SQL methods |
| `services/ars/src/.../persistence/ReplenishmentReadRepository.java` | Add SQL method |
| `services/ars/src/.../persistence/ForecastReadRepository.java` | Add SQL method |
| `services/ars/src/.../rest/DashboardController.java` | Replace 501, inject port |
| `services/ars/src/.../rest/StoreManagerResponseMapper.java` | Create |
| `mfe/sc-planner/vite.config.ts` | Add proxy rules |
| `mfe/sc-planner/public/config.js` | Create |
| `mfe/store-manager/**` | Create entire app (9+ files) |
