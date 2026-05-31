---
applyTo: "**/*.{ts,tsx}"
---

# TypeScript / React Instructions — SmartRetail MFEs

## Functional components only
```tsx
// CORRECT
export function KpiCard({ title, value }: KpiCardProps) { ... }

// WRONG — class component
export class KpiCard extends React.Component { ... }
```

## Props must be typed — no any, no implicit {}
```tsx
// CORRECT
interface KpiCardProps {
  title: string;
  value: number | string;
  isLoading?: boolean;
}

// WRONG
function KpiCard(props: any) { ... }
```

## Data fetching: custom hooks only — no fetch in components
```tsx
// CORRECT — fetching in a hook
export function useStoreManagerDashboard(dcId: string) {
  const { token } = useAuth();
  // ... fetch logic
}

// WRONG — fetching inside a component
function Dashboard() {
  useEffect(() => { fetch('/api/dashboard').then(...) }, []);
}
```

## Tailwind CSS: semantic colour palette
```tsx
// Status colours — use these consistently
// Critical: text-red-700 bg-red-50 border-red-200
// Warning:  text-amber-700 bg-amber-50 border-amber-200
// Good:     text-green-700 bg-green-50 border-green-200
// Info:     text-blue-700 bg-blue-50 border-blue-200
// Muted:    text-gray-500 bg-gray-50 border-gray-200

// Use cn() for conditional classes
import { cn } from '@/lib/cn';
<div className={cn('rounded-lg border p-4', isError && 'border-red-200 bg-red-50')} />
```

## Accessibility requirements
- `role="alert"` on error banners
- `aria-label="Loading..."` on loading skeletons
- Never use colour alone to convey state — pair colour with text or icon
- `<table>` elements must have `<caption>` or `aria-label`

## Generated API client — never edit
Files under `mfe/shared/api-client/src/generated/` are generated from OpenAPI YAML.
Do not edit them. Modify the YAML and run `npm run generate-api`.

## Environment variables — always typed
Declare all `import.meta.env.*` variables in `src/env.d.ts`. Never use untyped env vars.

## Error handling — keep stale data on error
```tsx
// CORRECT — keep previous data visible when a refresh fails
} catch (err) {
  setError(err instanceof Error ? err : new Error('Unknown'));
  // Do NOT clear data — keep showing stale data with ErrorBanner
}
```

## Component size limit
If a component file exceeds 150 lines, split it into sub-components.
