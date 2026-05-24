# SmartRetail — Event & Data Flow Diagrams

Legend:
```
────►   Production path  (cdk-prod / cdk-dev — Kinesis, RDS Proxy, CloudFront)
- - ->  Demo path        (cdk-demo — SQS-only, direct RDS, S3 website)
═════►  Shared path      (identical in both)
```

---

## Flow 1 — POS Event Ingestion

A sale at a store terminal enters the system through SIS and drives inventory updates.

```
POS Terminal
    │
    │  HTTP POST /v1/ingest/sales
    │
    ▼
[ API Gateway ]
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  INGESTION                                                      │
│                                                                 │
│   ────────────────────────────────────► Kinesis Data Stream     │  prod only
│                                              │                  │
│                                              ▼                  │
│   SIS (ECS)  ◄─────────────────── Lambda (kinesis-consumer)    │  prod only
│                                                                 │
│   - - - - - - - - - - - - - - - - - - - - - - - - - - - - ->   │
│   SIS puts directly onto SQS (no Kinesis, no Lambda)           │  demo only
│   [SIS not deployed in demo — data pre-seeded via V7 SQL]      │  demo only
└─────────────────────────────────────────────────────────────────┘
    │
    │  (shared path from here)
    ▼
   SIS
    ├═════► RDS — sales.sales_events (write)
    │
    └═════► SQS: smartretail-ims-sales-{env}
                │
                ▼
              IMS (ECS)
                ├═════► RDS — inventory.inventory_positions (update on_hand)
                ├═════► RDS — inventory.stock_alerts (raise if ATP < reorder_point)
                └═════► EventBridge: InventoryAlertEvent
                              │
                              ▼ (see Flow 2 fan-out)
```

**Idempotency (prod only):** Lambda writes SHA-256 of `transactionId` to DynamoDB before forwarding to SIS. Duplicate POS events are dropped.

---

## Flow 2 — Inventory Alert → Replenishment Engine

EventBridge routes the IMS alert to two consumers in parallel.

```
EventBridge: InventoryAlertEvent
    │
    ├═════════════════════════════════════════════════════► SQS: re-alert (FIFO)
    │                                                              │
    │                                                              ▼
    │                                                         RE (ECS)
    │                                                              │
    │                                          totalValue ≤ threshold?
    │                                         ┌──────────────────┘
    │                                         │
    │                               ┌── YES ──┴── NO ──┐
    │                               ▼                  ▼
    │                           APPROVED          PENDING_APPROVAL
    │                               │                  │
    │                               └─────────┬────────┘
    │                                         │
    │                                         ▼
    │                              RDS — replenishment.purchase_orders
    │                                         │
    │                              ═══════════► EventBridge: PurchaseOrderEvent
    │
    └═════════════════════════════════════════════════════► SQS: ars-updates
                                                                   │
                                                                   ▼
                                                             ARS (ECS)
                                                            (dashboard aggregation)
```

---

## Flow 3 — SC Planner Manual Approve / Reject

Only POs in `PENDING_APPROVAL` status reach this flow.

```
┌─────────────────────────────────────────────────────────────────┐
│  SC PLANNER MFE                                                 │
│                                                                 │
│   ──────────────────────────────────────────────────────────►  │  prod
│   S3 Static Website (CloudFront HTTPS)                         │  prod
│                                                                 │
│   - - - - - - - - - - - - - - - - - - - - - - - - - - - - ->  │  demo
│   S3 Static Website (HTTP, no CloudFront)                      │  demo
└─────────────────────────────────────────────────────────────────┘
    │
    │  GET /v1/replenishment/orders?status=PENDING_APPROVAL
    │  POST /v1/replenishment/orders/{poId}/approve
    │  POST /v1/replenishment/orders/{poId}/reject
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  AUTH                                                           │
│                                                                 │
│   ──────────────────────────────────────────────────────────►  │  prod
│   Cognito JWT → API Gateway authoriser → service JWT check     │  prod
│                                                                 │
│   - - - - - - - - - - - - - - - - - - - - - - - - - - - - ->  │  demo
│   X-Dev-Role: SC_PLANNER header (mock bypass)                  │  demo
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
  ALB  ──►  RE (ECS)
              │
              ├═════► Validate: status must be PENDING_APPROVAL (else 409)
              ├═════► Validate: SC_PLANNER or ADMIN role (else 403)
              ├═════► RDS UPDATE with optimistic lock (version check)
              │         PENDING_APPROVAL ──► APPROVED  (approve path)
              │         PENDING_APPROVAL ──► REJECTED  (reject path)
              │
              └═════► EventBridge: PurchaseOrderEvent (status=APPROVED|REJECTED)
                            │
                            └═════► SQS: ars-updates ──► ARS (dashboard refresh)
```

---

## Flow 4 — ARS → Store Manager Dashboard

Read-only aggregation — no writes. ARS queries each schema independently (no cross-schema SQL JOINs).

```
┌─────────────────────────────────────────────────────────────────┐
│  STORE MANAGER MFE                                              │
│                                                                 │
│   ──────────────────────────────────────────────────────────►  │  prod (CloudFront)
│   - - - - - - - - - - - - - - - - - - - - - - - - - - - - ->  │  demo (S3 HTTP)
└─────────────────────────────────────────────────────────────────┘
    │
    │  GET /v1/dashboard/store-manager?dcId=DC-LONDON
    ▼
  ARS (ECS)
    │
    │  parallel queries (no JOINs — merged in Java)
    ├═════► RDS — inventory.inventory_positions   (on_hand, in_transit, reorder status)
    ├═════► RDS — inventory.stock_alerts          (active alert counts)
    ├═════► RDS — replenishment.purchase_orders   (pending PO count)
    └═════► RDS — forecasting.demand_forecasts    (P50 for dcId)
              │
              └═════► HTTP response: StoreManagerDashboardResponse
                            │
                            ▼
                      MFE renders KPI cards
```

---

## Flow 8 — Executive Dashboard

Seed-data driven. All data pre-populated via V7__seed_data.sql.

```
┌─────────────────────────────────────────────────────────────────┐
│  EXECUTIVE MFE                                                  │
│                                                                 │
│   ──────────────────────────────────────────────────────────►  │  prod (CloudFront)
│   [not deployed in cdk-demo]                                   │  demo
└─────────────────────────────────────────────────────────────────┘
    │
    │  GET /v1/dashboard/executive
    ▼
  ARS (ECS)
    │
    │  parallel queries (no JOINs — merged in Java)
    ├═════► RDS — replenishment.purchase_orders          (fulfilment rate, lead time)
    ├═════► RDS — inventory.stock_alerts                 (stockout incidents, top SKUs)
    ├═════► RDS — forecasting.forecast_runs              (MAPE trend 30-day)
    ├═════► RDS — supplier.supplier_pos                  (OTD %, delivery histogram)
    └═════► RDS — supplier.shipment_updates              (carrying cost proxy)
              │
              └═════► HTTP response: ExecutiveDashboardResponse (9 KPI surfaces)
```

---

## Flow 9 — SC Planner Console

Seed-data driven for read surfaces. Surface 9.7 (manual replenishment trigger) is a live write path.

```
┌─────────────────────────────────────────────────────────────────┐
│  SC PLANNER MFE                                                 │
│                                                                 │
│   ──────────────────────────────────────────────────────────►  │  prod (CloudFront)
│   - - - - - - - - - - - - - - - - - - - - - - - - - - - - ->  │  demo (S3 HTTP)
└─────────────────────────────────────────────────────────────────┘
    │
    │  READ surfaces (tabs 1–6, 8) ──► ARS (ECS) ──► RDS (parallel queries)
    │
    │  WRITE surface (tab 7 — manual replenishment trigger)
    │  POST /v1/replenishment/orders
    ▼
  RE (ECS)
    └═════► RDS — replenishment.purchase_orders (INSERT, status=DRAFT)
                  (planner then submits → PENDING_APPROVAL → Flow 3)

  READ endpoints called by MFE:
    ├═════► GET /v1/dashboard/sc-planner           ──► ARS (summary KPIs)
    ├═════► GET /v1/inventory/positions             ──► IMS (inventory overview)
    ├═════► GET /v1/inventory/alerts                ──► IMS (exception queue)
    ├═════► GET /v1/forecast/demand                 ──► DFS (P10/P50/P90 bands)
    ├═════► GET /v1/replenishment/orders            ──► RE  (approval queue)
    ├═════► GET /v1/supplier/orders                 ──► SUP (order tracking)
    └═════► GET /v1/supplier/suppliers              ──► SUP (name resolution)
```

---

## Full System Topology

```
                         ┌──────────────────────────────────────────────┐
                         │  INGESTION (prod)                            │
  POS Terminal ─────────►│  Kinesis ──► Lambda ──► SIS ──► imsSalesQueue│
                         │                                              │
                         │  INGESTION (demo — pre-seeded, SIS absent)  │
  POS Terminal  - - - -> │  [V7 seed SQL populates RDS directly]        │
                         └───────────────────────┬──────────────────────┘
                                                 │
                                   ┌─────────────▼──────────────┐
                                   │  IMS (ECS)                  │
                                   │  inventory positions + alerts│
                                   └──────┬──────────────────────┘
                                          │ EventBridge
                              ┌───────────┴───────────┐
                              │                       │
                    ┌─────────▼────────┐   ┌──────────▼────────┐
                    │  RE (ECS)        │   │  ARS (ECS)         │
                    │  auto/manual PO  │   │  dashboards        │
                    │  creation        │   │  scorecards        │
                    └─────────┬────────┘   └──────────┬─────────┘
                              │ EventBridge            │
                              └───────────┬────────────┘
                                          │
                              ┌───────────▼───────────┐
                              │  DFS (ECS)             │
                              │  demand forecasts      │
                              │  P10 / P50 / P90       │
                              └───────────┬────────────┘
                                          │
                              ┌───────────▼───────────┐
                              │  SUP (ECS)             │
                              │  supplier records      │
                              │  order tracking        │
                              └───────────────────────┘

  MFEs
  ┌───────────────────┐   ┌─────────────────────┐   ┌──────────────────┐
  │  SC Planner       │   │  Store Manager      │   │  Executive       │
  │  :5174 / S3       │   │  :5173 / S3 (prod)  │   │  :5175 / S3(prod)│
  │  RE, ARS, DFS,    │   │  ARS, IMS           │   │  ARS, DFS        │
  │  SUP, IMS         │   │                     │   │                  │
  └───────────────────┘   └─────────────────────┘   └──────────────────┘
  deployed in demo ✅      prod / dev only ❌ demo    prod / dev only ❌ demo
```

---

## RDS Schema Ownership

Each service owns exactly one schema. No service writes to another service's schema.
No cross-schema SQL JOINs anywhere. ARS merges data in Java.

```
  SIS  ──► sales.*
  IMS  ──► inventory.*
  RE   ──► replenishment.*
  ARS  ──► reads all schemas (SELECT only, no writes outside analytics.*)
  DFS  ──► forecasting.*
  SUP  ──► supplier.*
  PPS  ──► promotions.*
```
