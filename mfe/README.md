# MFE — Micro-Frontends

Five React applications that form the SmartRetail user-facing layer. Each runs as an independent Vite dev server in local mode.

## Applications at a glance

| Directory | Name | Port (local) | Purpose |
|-----------|------|-------------|---------|
| `store-manager/` | Store Manager Dashboard | 5173 | Store-level inventory KPIs and stock alerts (Flow 4) |
| `sc-planner/` | SC Planner Console | 5174 | PO approval, exception queue, forecast view, manual replenishment (Flow 9) |
| `executive/` | Executive Dashboard | 5175 | MAPE trend, OTD, supplier ranking, carrying cost (Flow 8) |
| `supplier/` | Supplier Portal | 5077 | External supplier view — PO status, shipment tracking (SUPPLIER\_ADMIN role) |

> The Demo Control Center MFE lives at `demo/ui/` (root-level `demo/` module, not here). Its Node control server is at `demo/server/`. Run both with `make local-demo`.

## MFE → Service mapping

| MFE | Port | Primary backing services |
|-----|------|--------------------------|
| `store-manager/` | 5173 | ARS (:8083), IMS (:8081) |
| `sc-planner/` | 5174 | RE (:8082), ARS (:8083), DFS (:8084), SUP (:8085) |
| `executive/` | 5175 | ARS (:8083), DFS (:8084) |
| `supplier/` | 5077 | SUP (:8085) |

## Technology

- **React 18 + TypeScript 5** — functional components, hooks only
- **Vite 5** — dev server with HMR; `vite.config.ts` configures proxy to backend services
- **Tailwind CSS 3** — utility-first styling; no CSS modules or CSS-in-JS
- **Recharts 2** — charts in SC Planner and Executive Dashboard
- **@smartretail/auth** — shared local package (`mfe/shared/auth/`) wrapping `@aws-amplify/auth 6`; provides `AuthProvider`, `useAuth`, `createApiClient`
- **Vitest 2 + Testing Library** — unit tests with 80 % coverage gate; `npm run test:coverage`

## Shared auth library

```
mfe/shared/auth/
├── src/
│   ├── AuthProvider.tsx   Cognito PKCE flow, token refresh, mock bypass in local mode
│   ├── useAuth.ts         Returns { user, token, isLoading, signOut }
│   └── apiClient.ts       Axios factory — injects Bearer token, handles 401
└── package.json           name: @smartretail/auth  (file: reference in each MFE)
```

In `LOCAL` mode the auth library bypasses Cognito and returns a mock user automatically.

**Important:** build the shared auth package before running any MFE for the first time:
```bash
cd mfe/shared/auth && npm install && npm run build
```

## Running locally

```bash
# Prerequisites: backend services running (make local-sis local-ims ... )

cd mfe/store-manager && npm install && npm run dev   # → http://localhost:5173
cd mfe/sc-planner    && npm install && npm run dev   # → http://localhost:5174
cd mfe/executive     && npm install && npm run dev   # → http://localhost:5175
cd demo/ui            && npm install && npm run dev   # → http://localhost:5176 (Demo Control Center — lives in /demo, not here)
cd mfe/supplier      && npm install && npm run dev   # → http://localhost:5077
```

Or via Make from the repo root:

```bash
make local-mfe-store      # starts store-manager
make local-mfe-planner    # starts sc-planner
make local-mfe-exec       # starts executive
make local-mfe-demo       # starts demo runner
make local-mfe-supplier   # starts supplier portal
```

## Testing

```bash
cd mfe/<app>
npm test                 # run once
npm run test:watch       # watch mode
npm run test:coverage    # coverage report + threshold check (lines 80 %, branches 70 %)
```

Coverage is checked on `src/components/**`, `src/hooks/**`, and `src/utils/**`. `src/main.tsx` and `src/App.tsx` are excluded.

## Environment configuration

Each app has a `vite.config.ts` with a `server.proxy` block that forwards API calls to the correct backend port in local mode. For AWS mode the `VITE_API_BASE_URL` environment variable is injected at build time by the CDK stack.
