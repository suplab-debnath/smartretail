# Demo Runner MFE

Interactive walkthrough application for SmartRetail architect demos. Presents each end-to-end flow as a narrative chapter with live trigger buttons, a real-time event log, an evidence checklist, and live database snapshots.

**Dev port:** `5176`  
**Backend:** Demo Server (`demo-server/`, port 3099)  
**No auth** — local-only tool, never deployed to production

## What it does

1. Displays a **flow rail** of all prototype flows (Flows 1–4, 8, 9) with pass/fail status.
2. For each flow, shows a sequence of **steps** with narrative text, architecture diagrams, and "before/after" state panels.
3. Each step can have a **trigger button** that fires a real backend action (e.g. publish a POS event, approve a PO) by calling `POST /api/trigger/{flowId}/{stepId}` on the Demo Server.
4. The **event log** streams live output from the triggered scripts via SSE (`GET /api/events/stream`).
5. An **evidence checklist** automatically checks off items as matching log entries appear.
6. An **MFE reveal panel** links directly to the relevant screen in Store Manager, SC Planner, or Executive Dashboard.

## Components

| Component | Description |
|-----------|-------------|
| `MissionControlLayout.tsx` | Root layout — flow rail, step content, event log column |
| `FlowRail.tsx` | Left sidebar — flow list with pass/fail/in-progress indicators |
| `ChapterView.tsx` | Renders the active step: narrative, trigger, checklist, DB snapshot, MFE link |
| `NarrativeHero.tsx` | Large header with flow title, subtitle, and persona |
| `TriggerButton.tsx` | State machine button — `idle` / `running` (spinner) / `done` (✓) / `failed` (✗) |
| `ChecklistPanel.tsx` | Evidence checklist — items auto-checked when their `matchPattern` appears in the log |
| `EventLog.tsx` | Scrolling log container |
| `EventLogEntry.tsx` | Single log line — timestamp, service badge, level prefix, message |
| `ServiceHealthBar.tsx` | Header bar — per-service coloured dots (emerald = ok, red = down) |
| `MfeRevealPanel.tsx` | "Open in MFE" panel linking to Store Manager / SC Planner / Executive |
| `ArchitectureDiagram.tsx` | SVG architecture diagram with node highlights per active step |
| `BeforeAfterPanel.tsx` | Side-by-side DB state comparison (before trigger / after trigger) |
| `StepProgressBar.tsx` | Progress bar across steps within the current flow |

## Running

```bash
# 1. Start the demo server
cd demo-server && npm run dev   # port 3099

# 2. Start this MFE
cd mfe/demo && npm install && npm run dev   # port 5176
```

Or from the repo root:

```bash
make local-demo
```

Requires all six backend services running and Postgres accessible.

## Testing

```bash
npm test
npm run test:watch
npm run test:coverage
```

Tested components: `ServiceHealthBar` (health status colors), `TriggerButton` (all four states, disabled when running), `ChecklistPanel` (match counting, empty state, checkmark rendering), `EventLogEntry` (time formatting, service truncation, level prefixes and colors).

## SSE event format

Each event from `/api/events/stream` is a JSON `EventLogEntry`:

```json
{
  "id":      "uuid",
  "ts":      "2024-05-17T10:23:45.123Z",
  "flowId":  "flow1",
  "stepId":  "pos-event",
  "service": "SIS",
  "level":   "pass",
  "message": "Sales event ingested: transactionId=...",
  "raw":     "..."
}
```

`level` values: `pass` (✓ green), `fail` (✗ red), `warn` (! amber), `event` (→ cyan), `info` (· slate).

## Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `react` | 18.3.1 | UI framework |
| `react-router-dom` | 6.26.1 | Client-side routing |
| `vitest` | 2.1.9 | Test runner |
| `@testing-library/react` | 16.3.0 | Component testing |

No Recharts, no auth library — this app is purely local.
