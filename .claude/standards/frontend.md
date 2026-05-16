# Frontend Coding Standards — React 18 · TypeScript 5 · Tailwind CSS 3
 
---
 
## Design Principles
 
The MFEs are operational dashboards used by supply chain professionals.
Design for clarity, density, and trust — not consumer aesthetics.
 
| Principle | What it means in practice |
|-----------|--------------------------|
| Information density | Show more data, fewer decorations. Planners need context, not whitespace. |
| Status communication | Colour must communicate state. Use red/amber/green consistently and only for status. |
| Data freshness | Always show when data was last updated. Stale data shown without a warning is a defect. |
| Progressive disclosure | Show summaries first. Let users drill into detail on demand. |
| Failure resilience | Never show a blank screen. Show last known data + staleness indicator on error. |
| Accessibility | WCAG 2.1 AA minimum. All interactive elements keyboard-navigable. |
 
---
 
## Project Setup (per MFE)
 
### vite.config.ts
```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
 
export default defineConfig({
plugins: [react()],
resolve: {
alias: {
'@': path.resolve(__dirname, './src'),
'@smartretail/auth': path.resolve(__dirname, '../shared/auth/src'),
'@smartretail/api-client': path.resolve(__dirname, '../shared/api-client/src'),
},
},
server: {
port: 5173, // store-manager: 5173 · sc-planner: 5174 · executive: 5175
proxy: {
'/api': {
target: 'http://localhost:8083', // ARS in local mode
changeOrigin: true,
rewrite: path => path.replace(/^\/api/, ''),
},
},
},
});
```
 
### tsconfig.json (strict mode — no exceptions)
```json
{
"compilerOptions": {
"target": "ES2022",
"lib": ["ES2022", "DOM", "DOM.Iterable"],
"module": "ESNext",
"moduleResolution": "bundler",
"jsx": "react-jsx",
"strict": true,
"noUncheckedIndexedAccess": true,
"noImplicitReturns": true,
"noFallthroughCasesInSwitch": true,
"exactOptionalPropertyTypes": true,
"forceConsistentCasingInFileNames": true,
"skipLibCheck": true,
"baseUrl": ".",
"paths": {
"@/*": ["src/*"],
"@smartretail/auth": ["../shared/auth/src/index"],
"@smartretail/api-client": ["../shared/api-client/src/index"]
}
},
"include": ["src"],
"exclude": ["node_modules", "dist"]
}
```
 
### package.json (per MFE)
```json
{
"scripts": {
"dev": "vite",
"build": "tsc --noEmit && vite build",
"preview": "vite preview",
"lint": "eslint src --ext ts,tsx --report-unused-disable-directives --max-warnings 0",
"type-check": "tsc --noEmit"
},
"dependencies": {
"react": "^18.3.1",
"react-dom": "^18.3.1",
"react-router-dom": "^6.26.0",
"recharts": "^2.12.7",
"date-fns": "^3.6.0",
"clsx": "^2.1.1",
"tailwind-merge": "^2.5.2",
"@headlessui/react": "^2.1.2",
"@heroicons/react": "^2.1.5"
},
"devDependencies": {
"@types/react": "^18.3.3",
"@types/react-dom": "^18.3.0",
"@vitejs/plugin-react": "^4.3.1",
"@typescript-eslint/eslint-plugin": "^8.0.0",
"@typescript-eslint/parser": "^8.0.0",
"eslint": "^9.8.0",
"eslint-plugin-react-hooks": "^5.1.0-rc.0",
"tailwindcss": "^3.4.9",
"autoprefixer": "^10.4.20",
"postcss": "^8.4.41",
"typescript": "^5.5.4",
"vite": "^5.4.0"
}
}
```
 
---
 
## Component Architecture
 
### File structure (per MFE)
```
src/
├── app/
│ ├── App.tsx ← root component + router
│ ├── routes.tsx ← route definitions
│ └── providers.tsx ← AuthProvider + QueryClientProvider wrap
├── pages/
│ ├── DashboardPage.tsx ← page-level component (data fetching)
│ └── CallbackPage.tsx ← Cognito PKCE callback handler
├── components/
│ ├── layout/
│ │ ├── AppShell.tsx ← nav + main content area
│ │ ├── Sidebar.tsx
│ │ └── TopBar.tsx
│ ├── dashboard/
│ │ ├── KpiCard.tsx
│ │ ├── AlertTable.tsx
│ │ ├── MapeChart.tsx ← Recharts wrapper
│ │ └── SupplierScorecard.tsx
│ └── shared/
│ ├── DataFreshnessBadge.tsx
│ ├── SeverityBadge.tsx
│ ├── StatusBadge.tsx
│ ├── LoadingSkeleton.tsx
│ ├── ErrorBanner.tsx
│ └── EmptyState.tsx
├── hooks/
│ ├── useDashboard.ts ← data fetching + polling
│ ├── useApproval.ts ← approve/reject with optimistic update
│ └── useSupplierPerformance.ts
├── lib/
│ ├── api.ts ← API client instances (uses generated client)
│ ├── format.ts ← date formatting, number formatting
│ └── cn.ts ← clsx + tailwind-merge utility
└── types/
└── index.ts ← re-exports from @smartretail/api-client
```
 
### Component rules
 
**Functional components only.** No class components.
 
**Single responsibility.** If a component file exceeds 150 lines, split it.
 
**Props must be typed.** No `any`. No implicit `{}` prop types.
 
```typescript
// CORRECT
interface KpiCardProps {
title: string;
value: number | string;
trend?: 'up' | 'down' | 'stable';
isLoading?: boolean;
className?: string;
}
 
export function KpiCard({ title, value, trend, isLoading = false, className }: KpiCardProps) {
// ...
}
 
// WRONG
export function KpiCard(props: any) { ... }
export function KpiCard({ title, value }: { title: any; value: any }) { ... }
```
 
---
 
## Design System
 
### Colour palette (Tailwind classes)
 
Use these semantic colour conventions consistently across all MFEs.
Never use arbitrary colour values.
 
```
Status colours:
Critical / Error: text-red-700 bg-red-50 border-red-200
Warning / High: text-amber-700 bg-amber-50 border-amber-200
Caution / Medium: text-yellow-700 bg-yellow-50 border-yellow-200
Success / Good: text-green-700 bg-green-50 border-green-200
Neutral / Info: text-blue-700 bg-blue-50 border-blue-200
Muted / Secondary: text-gray-500 bg-gray-50 border-gray-200
 
Interactive:
Primary button: bg-blue-600 hover:bg-blue-700 text-white
Danger button: bg-red-600 hover:bg-red-700 text-white
Secondary button: bg-white border border-gray-300 text-gray-700 hover:bg-gray-50
Disabled: opacity-50 cursor-not-allowed
 
Text:
Primary: text-gray-900
Secondary: text-gray-600
Tertiary: text-gray-400
Link: text-blue-600 hover:text-blue-800
```
 
### cn() utility — always use for conditional classes
```typescript
// lib/cn.ts
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
 
export function cn(...inputs: ClassValue[]) {
return twMerge(clsx(inputs));
}
 
// Usage
<div className={cn('rounded-lg border p-4', isError && 'border-red-200 bg-red-50')} />
```
 
### Component patterns
 
#### KPI Card
```typescript
interface KpiCardProps {
title: string;
value: string | number;
subtitle?: string;
trend?: { direction: 'up' | 'down' | 'stable'; label: string };
severity?: 'critical' | 'high' | 'medium' | 'good' | 'neutral';
isLoading?: boolean;
}
 
export function KpiCard({ title, value, subtitle, trend, severity = 'neutral', isLoading }: KpiCardProps) {
if (isLoading) {
return (
<div className="bg-white rounded-lg border border-gray-200 p-4 animate-pulse">
<div className="h-4 bg-gray-200 rounded w-3/4 mb-3" />
<div className="h-8 bg-gray-200 rounded w-1/2" />
</div>
);
}
 
const severityClasses = {
critical: 'border-red-200 bg-red-50',
high: 'border-amber-200 bg-amber-50',
medium: 'border-yellow-200 bg-yellow-50',
good: 'border-green-200 bg-green-50',
neutral: 'border-gray-200 bg-white',
};
 
return (
<div className={cn('rounded-lg border p-4', severityClasses[severity])}>
<p className="text-sm font-medium text-gray-600">{title}</p>
<p className="mt-1 text-2xl font-semibold text-gray-900">{value}</p>
{subtitle && <p className="mt-1 text-xs text-gray-500">{subtitle}</p>}
{trend && (
<div className={cn('mt-2 flex items-center text-xs',
trend.direction === 'up' ? 'text-green-600' :
trend.direction === 'down' ? 'text-red-600' : 'text-gray-500')}>
<span>{trend.label}</span>
</div>
)}
</div>
);
}
```
 
#### Data Freshness Badge
```typescript
import { formatDistanceToNow, parseISO } from 'date-fns';
 
interface DataFreshnessBadgeProps {
freshness: string; // ISO-8601 datetime
staleThresholdMinutes?: number;
}
 
export function DataFreshnessBadge({ freshness, staleThresholdMinutes = 5 }: DataFreshnessBadgeProps) {
const date = parseISO(freshness);
const ageMs = Date.now() - date.getTime();
const isStale = ageMs > staleThresholdMinutes * 60 * 1000;
 
return (
<span className={cn(
'inline-flex items-center gap-1 text-xs',
isStale ? 'text-amber-600' : 'text-gray-400'
)}>
{isStale && <span aria-hidden>⚠</span>}
Data as of {formatDistanceToNow(date, { addSuffix: true })}
</span>
);
}
```
 
#### Severity Badge
```typescript
type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM';
 
const SEVERITY_STYLES: Record<Severity, string> = {
CRITICAL: 'bg-red-100 text-red-800 border border-red-200',
HIGH: 'bg-amber-100 text-amber-800 border border-amber-200',
MEDIUM: 'bg-yellow-100 text-yellow-800 border border-yellow-200',
};
 
export function SeverityBadge({ severity }: { severity: Severity }) {
return (
<span className={cn(
'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
SEVERITY_STYLES[severity]
)}>
{severity}
</span>
);
}
```
 
#### Loading Skeleton
```typescript
interface SkeletonProps {
rows?: number;
className?: string;
}
 
export function TableSkeleton({ rows = 5 }: SkeletonProps) {
return (
<div className="animate-pulse space-y-2" aria-label="Loading...">
{Array.from({ length: rows }).map((_, i) => (
<div key={i} className="flex space-x-4">
<div className="h-4 bg-gray-200 rounded flex-1" />
<div className="h-4 bg-gray-200 rounded w-24" />
<div className="h-4 bg-gray-200 rounded w-20" />
</div>
))}
</div>
);
}
```
 
#### Error Banner
```typescript
interface ErrorBannerProps {
message?: string;
lastKnownData?: string; // ISO-8601 timestamp of last successful fetch
onRetry?: () => void;
}
 
export function ErrorBanner({ message, lastKnownData, onRetry }: ErrorBannerProps) {
return (
<div className="rounded-md bg-amber-50 border border-amber-200 p-3" role="alert">
<div className="flex items-start justify-between">
<div>
<p className="text-sm font-medium text-amber-800">
{message ?? 'Unable to fetch latest data'}
</p>
{lastKnownData && (
<p className="mt-1 text-xs text-amber-700">
Showing data from {formatDistanceToNow(parseISO(lastKnownData), { addSuffix: true })}
</p>
)}
</div>
{onRetry && (
<button
onClick={onRetry}
className="ml-4 text-xs text-amber-800 underline hover:no-underline"
> 
Retry
</button>
)}
</div>
</div>
);
}
```
 
---
 
## Data Fetching Pattern
 
Use custom hooks for all data fetching. No fetch/axios calls in components.
 
```typescript
// hooks/useDashboard.ts
import { useState, useEffect, useCallback, useRef } from 'react';
import { createArsApi } from '@smartretail/api-client';
import type { StoreManagerDashboardResponse } from '@smartretail/api-client';
import { useAuth } from '@smartretail/auth';
 
interface UseDashboardResult {
data: StoreManagerDashboardResponse | null;
isLoading: boolean;
error: Error | null;
lastUpdated: Date | null;
refetch: () => void;
}
 
const POLL_INTERVAL_MS = 60_000; // 60 seconds for store manager
 
export function useStoreManagerDashboard(dcId: string): UseDashboardResult {
const { token } = useAuth();
const api = createArsApi(import.meta.env.VITE_ARS_API_URL, () => token);
 
const [data, setData] = useState<StoreManagerDashboardResponse | null>(null);
const [isLoading, setIsLoading] = useState(true);
const [error, setError] = useState<Error | null>(null);
const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
const abortControllerRef = useRef<AbortController | null>(null);
 
const fetch = useCallback(async () => {
abortControllerRef.current?.abort();
abortControllerRef.current = new AbortController();
 
try {
const response = await api.getStoreManagerDashboard({ dcId });
setData(response.data);
setLastUpdated(new Date());
setError(null);
} catch (err) {
if (err instanceof Error && err.name === 'AbortError') return;
setError(err instanceof Error ? err : new Error('Unknown error'));
// Keep stale data — do not clear data on error
} finally {
setIsLoading(false);
}
}, [dcId, token]);
 
useEffect(() => {
setIsLoading(true);
fetch();
const interval = setInterval(fetch, POLL_INTERVAL_MS);
return () => {
clearInterval(interval);
abortControllerRef.current?.abort();
};
}, [fetch]);
 
return { data, isLoading, error, lastUpdated, refetch: fetch };
}
```
 
---
 
## Approve/Reject Pattern
 
```typescript
// hooks/useApproval.ts
import { useState, useRef } from 'react';
import { createReApi } from '@smartretail/api-client';
import { useAuth } from '@smartretail/auth';
 
type ApprovalState = 'idle' | 'pending' | 'success' | 'error';
 
interface UseApprovalResult {
approve: (poId: string, notes?: string) => Promise<void>;
reject: (poId: string, reason: string) => Promise<void>;
state: ApprovalState;
errorMessage: string | null;
}
 
export function useApproval(onSuccess: (poId: string) => void): UseApprovalResult {
const { token } = useAuth();
const api = createReApi(import.meta.env.VITE_RE_API_URL, () => token);
const idempotencyKeyRef = useRef(crypto.randomUUID());
 
const [state, setState] = useState<ApprovalState>('idle');
const [errorMessage, setErrorMessage] = useState<string | null>(null);
 
const approve = async (poId: string, notes?: string) => {
setState('pending');
setErrorMessage(null);
 
try {
await api.approvePurchaseOrder(
poId,
idempotencyKeyRef.current, // from header
notes ? { notes } : undefined
);
setState('success');
onSuccess(poId);
} catch (err: unknown) {
setState('error');
if (isApiError(err) && err.response?.status === 409) {
const errorCode = err.response.data?.errorCode;
if (errorCode === 'INVALID_STATUS_TRANSITION') {
setErrorMessage('PO status has changed. Please refresh the list.');
} else if (errorCode === 'CONCURRENT_MODIFICATION') {
setErrorMessage('Another user modified this PO. Please refresh.');
} else {
setErrorMessage('Unable to approve. Please try again.');
}
} else {
setErrorMessage('An unexpected error occurred.');
}
}
};
 
const reject = async (poId: string, reason: string) => {
setState('pending');
try {
await api.rejectPurchaseOrder(poId, { reason });
setState('success');
onSuccess(poId);
} catch (err: unknown) {
setState('error');
setErrorMessage('Unable to reject PO. Please try again.');
}
};
 
return { approve, reject, state, errorMessage };
}
 
function isApiError(err: unknown): err is { response: { status: number; data: { errorCode?: string } } } {
return typeof err === 'object' && err !== null && 'response' in err;
}
```
 
---
 
## Recharts Configuration
 
### MAPE Trend Chart (Flow 8)
```typescript
import {
LineChart, Line, XAxis, YAxis, CartesianGrid,
Tooltip, ReferenceLine, ResponsiveContainer, Legend
} from 'recharts';
import { format, parseISO } from 'date-fns';
 
interface MapeDataPoint {
runDate: string; // ISO date string
mape: number; // 0.0 to 1.0
}
 
export function MapeChart({ data, threshold = 0.15 }: { data: MapeDataPoint[]; threshold?: number }) {
const formatted = data.map(d => ({
...d,
label: format(parseISO(d.runDate), 'MMM d'),
mapePercent: +(d.mape * 100).toFixed(2),
}));
 
return (
<ResponsiveContainer width="100%" height={240}>
<LineChart data={formatted} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
<CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
<XAxis
dataKey="label"
tick={{ fontSize: 11, fill: '#6b7280' }}
tickLine={false}
axisLine={{ stroke: '#e5e7eb' }}
/>
<YAxis
tick={{ fontSize: 11, fill: '#6b7280' }}
tickFormatter={v => `${v}%`}
domain={[0, Math.ceil((Math.max(...data.map(d => d.mape)) * 100) + 2)]}
tickLine={false}
axisLine={false}
/>
<Tooltip
formatter={(value: number) => [`${value}%`, 'MAPE']}
labelFormatter={label => `Date: ${label}`}
contentStyle={{ fontSize: 12, borderRadius: 6, border: '1px solid #e5e7eb' }}
/>
<ReferenceLine
y={threshold * 100}
stroke="#ef4444"
strokeDasharray="4 4"
label={{ value: `Threshold (${threshold * 100}%)`, position: 'right', fontSize: 10, fill: '#ef4444' }}
/>
<Line
type="monotone"
dataKey="mapePercent"
stroke="#2563eb"
strokeWidth={2}
dot={false}
activeDot={{ r: 4, fill: '#2563eb' }}
name="MAPE"
/>
</LineChart>
</ResponsiveContainer>
);
}
```
 
---
 
## Accessibility Requirements
 
- All interactive elements must have `aria-label` or visible label
- Color alone must not convey state — use icon + color together
- Tables must have `<caption>` or `aria-label`
- Loading states must use `aria-label="Loading..."`
- Error messages must use `role="alert"`
- Keyboard navigation must work on all interactive elements
- Focus indicators must be visible (do not override outline: none without replacement)
```typescript
// CORRECT — color + text
<span className="bg-red-100 text-red-800" aria-label="Critical severity">
<ExclamationCircleIcon className="h-4 w-4 inline mr-1" aria-hidden="true" />
CRITICAL
</span>
 
// WRONG — color only
<span className="bg-red-100 text-red-800" />
```
 
---
 
## Environment Variables (Vite)
 
All environment variables must be typed:
 
```typescript
// src/env.d.ts (in each MFE)
/// <reference types="vite/client" />
 
interface ImportMetaEnv {
readonly VITE_ARS_API_URL: string;
readonly VITE_RE_API_URL: string;
readonly VITE_AUTH_MODE: 'cognito' | 'mock';
readonly VITE_COGNITO_POOL_ID: string;
readonly VITE_COGNITO_CLIENT_ID: string;
readonly VITE_COGNITO_DOMAIN: string;
readonly VITE_ENV: 'local' | 'dev' | 'prod';
}
 
interface ImportMeta {
readonly env: ImportMetaEnv;
}
```
 
 