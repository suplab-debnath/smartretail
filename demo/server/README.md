# Demo Server

Node.js Express server that acts as the control plane for the SmartRetail architect demo. It bridges the Demo Control Center MFE (`demo/ui/`) to the real backend services — executing flow trigger scripts, streaming live log output over SSE, and serving live database snapshots.

**Port:** `3099` (localhost only — never bound to `0.0.0.0`)

## Responsibilities

| Route | Method | Purpose |
|-------|--------|---------|
| `POST /api/trigger/:flowId/:stepId` | POST | Spawns the configured script for a flow step; responds immediately while streaming output over SSE |
| `DELETE /api/trigger/:flowId` | DELETE | Kills a running flow script |
| `GET /api/events/stream` | GET (SSE) | Server-sent events — broadcasts script stdout in real time to all connected Demo MFE tabs |
| `GET /api/dbstate/:key` | GET | Returns a live DB snapshot (e.g. latest stock alerts, PO list) for the evidence panel |
| `GET /api/health` | GET | Checks reachability of all six backend services; returns per-service `ok \| down` |
| `GET /api/env` | GET | Returns the current `SMARTRETAIL_ENV` and MFE base URLs |

## Source layout

```
demo/server/
├── server.js              Express app setup, port binding
├── lib/
│   ├── envConfig.js       Reads SMARTRETAIL_ENV; computes service and MFE base URLs
│   ├── flowConfig.js      Maps (flowId, stepId) → { cmd, args, env } per environment
│   ├── scriptRunner.js    Child-process runner; pipes stdout to sseBroadcaster
│   ├── sseBroadcaster.js  Fan-out broadcaster — one script → all SSE clients
│   └── dbClient.js        pg Pool for live DB query snapshots
└── routes/
    ├── trigger.js         POST/DELETE /api/trigger
    ├── events.js          GET /api/events/stream (SSE)
    ├── dbstate.js         GET /api/dbstate/:key
    └── health.js          GET /api/health
```

## Running

```bash
cd demo/server
npm install
npm run dev      # node --watch server.js  (hot-reload)
npm start        # node server.js
```

Or via Make from the repo root:

```bash
make local-demo-server
```

Requires the six backend services to be running (ports 8080–8085) and Postgres accessible.

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SMARTRETAIL_ENV` | `local` | `local` or `aws`; controls which service URLs are used |
| `DATABASE_URL` | `postgres://smartretail:smartretail@localhost:5432/smartretail` | Postgres connection string for DB snapshots |

## Flow trigger mechanics

When the Demo MFE clicks a trigger button, it calls `POST /api/trigger/flow1/pos-event`. The server:

1. Looks up the script definition in `lib/flowConfig.js` (environment-aware — uses `scripts/shared/publish-pos-event.py` locally, direct HTTP calls on AWS).
2. Spawns the script as a child process via `lib/scriptRunner.js`.
3. Streams each line of stdout through `lib/sseBroadcaster.js` to all SSE clients as structured `EventLogEntry` JSON.
4. Returns `{ ok: true }` immediately — the response does not wait for the script to finish.

A 409 is returned if the same flow is already running.

## Dependencies

```json
{
  "express":  "^4.19.2",
  "cors":     "^2.8.5",
  "pg":       "^8.12.0"
}
```

No TypeScript, no build step — plain CommonJS Node.js.
