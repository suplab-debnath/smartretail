# Demo Control Center

A single-browser experience for presenting all six prototype flows to a technical audience. The presenter never touches a terminal — every trigger, verification, and MFE reveal is orchestrated from one window.

```
┌──────────────┬─────────────────────────────────────────┬──────────────────┐
│  FLOW RAIL   │           CENTER CANVAS                 │   EVENT LOG      │
│              │  ┌─────────────────────────────────┐   │                  │
│ 01 ● Flow 1  │  │  Chapter hero + narrative prose  │   │  ✓ SIS saved     │
│ 02 ○ Flow 2  │  │  Animated SVG architecture diagram│  │  ✓ IMS alert     │
│ 03 ○ Flow 3  │  │  Before / After DB state panels  │   │  ✓ RE auto-PO    │
│ 04 ○ Flow 4  │  │  Evidence checklist (live ticks) │   │  · Waiting…      │
│ 05 ○ Flow 8  │  │  [ Fire POS Transaction ▶ ]      │   │                  │
│ 06 ○ Flow 9  │  └─────────────────────────────────┘   │                  │
└──────────────┴─────────────────────────────────────────┴──────────────────┘
```

---

## Architecture

| Process | Port | Role |
|---------|------|------|
| `tools/demo/server` (Node/Express) | 3099 | Spawns `publish-pos-event.py` / `smoke-test.sh`, streams stdout to browser via SSE, queries Postgres for before/after DB state panels |
| `tools/demo/ui` (Vite + React) | 5176 | Mission Control UI — flow rail, animated SVG diagram, narrative heroes, evidence checklists, MFE iframes |

The architecture diagram shows every service node. Nodes pulse and animated dots travel along edges in real time as SSE log lines arrive. The live evidence checklist auto-checks each item when a matching string appears in the log stream.

---

## Demo Narrative

| Chapter | Flow   | Title                          | Key moment                                                                          |
|---------|--------|--------------------------------|-------------------------------------------------------------------------------------|
| 1       | Flow 1 | A Customer Buys Something      | Fire POS event — watch Firehose → SIS → IMS animate; stock alert row appears        |
| 2       | Flow 2 | The System Responds            | RE evaluates the alert; split reveal shows auto-approved PO vs PENDING_APPROVAL PO  |
| 3       | Flow 3 | The Planner Decides            | SC Planner MFE slides in — presenter approves the live PO; EventBridge fires        |
| 4       | Flow 4 | The Store Manager Reacts       | Store Manager MFE slides in — DC-LONDON KPIs show the active alert from Chapter 1   |
| 5       | Flow 8 | Leadership Reviews Performance | Executive Dashboard — MAPE trend improving 0.1187 → 0.0823 across 30 seed days     |
| 6       | Flow 9 | The Planner Optimizes          | All 8 SC Planner Console tabs; manual replenishment trigger creates DRAFT PO live   |

---

## Running Locally

**Prerequisites:** all backend services and operational MFEs must be running first.

```bash
# Full startup sequence
make local-up && make local-migrate && make local-seed
make local-sis & make local-ims & make local-re & make local-ars &
make local-dfs & make local-sup & make local-pps &
make local-mfe-sm & make local-mfe-scp & make local-mfe-exec &

# Then start the demo
make local-demo   # starts both demo server (:3099) and demo MFE (:5176)
```

Open **http://localhost:5176** — all health dots in the top bar should be green before presenting.

**Demo flow:**
1. Click a chapter in the left rail.
2. Step through the progress bar.
3. Click trigger buttons (e.g. **Fire POS Transaction**) — diagram animates, event log fills in real time.
4. When a step requires a live MFE, an iframe slides in within the canvas — interact with it directly.
5. Run **Verify FlowN** at the end of each chapter to execute the smoke test and auto-check the evidence checklist.

---

## Running on AWS

The demo server switches to AWS mode via `SMARTRETAIL_ENV=aws`. It routes trigger calls to real API Gateway endpoints and reads MFE URLs from environment variables.

```bash
# 1. Deploy the platform first
make aws-full-deploy ENV=dev PROFILE=smartretail-dev

# 2. Resolve endpoints from SSM
export SIS_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/api-gateway/endpoint --query Parameter.Value --output text)
export ARS_URL=$SIS_URL

export MFE_SM_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/store-manager-url --query Parameter.Value --output text)
export MFE_SCP_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/sc-planner-url --query Parameter.Value --output text)
export MFE_EXEC_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/executive-url --query Parameter.Value --output text)

# 3. Start demo
make aws-demo
```

**AWS mode differences vs local:**
- Trigger buttons call real API Gateway endpoints
- `smoke-test.sh` runs against `SMARTRETAIL_ENV=dev`
- Before/After DB panels use ARS REST responses (no direct Postgres access)
- MFE iframes load from CloudFront URLs

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| All health dots red | Confirm all 6 services are up: `curl localhost:8080/actuator/health` |
| "Flow X is already running" | A previous smoke test is still running; wait or refresh |
| Chapter 3 — no PENDING_APPROVAL PO | Click **Create Test PENDING_APPROVAL PO** in step 1 of Chapter 3 |
| MFE iframe shows login page | Auth mock should auto-login in LOCAL mode; check `SPRING_PROFILES_ACTIVE=local` is set |
| Event log empty after trigger | SSE connection dropped; reload — `EventSource` auto-reconnects |
