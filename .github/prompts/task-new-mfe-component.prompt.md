---
mode: 'agent'
description: 'Task: Create a new React MFE component with TypeScript types, Tailwind styling, accessibility, and Vitest tests'
tools: ['codebase', 'findTestFiles', 'new', 'runCommand', 'runTests', 'usages', 'workspaceDetails']
---

Create a new React component for a SmartRetail MFE.

## Component details
- **MFE:** ${input:mfe}
  (one of: store-manager, sc-planner, executive, supplier)
- **Component name:** ${input:componentName}
  (PascalCase, e.g. `StockAlertTable`, `ForecastBandChart`, `SupplierScorecard`)
- **Component purpose:** ${input:purpose}
  (e.g. `Displays stock alerts for a DC with severity badges and sort/filter`)
- **Data source:** ${input:dataSource}
  (e.g. `ARS GET /v1/dashboard/store-manager/{dcId}`)

## What to create

### Component file
`mfe/{mfe}/src/components/dashboard/{ComponentName}.tsx`
- Functional component only -- no class components
- Full TypeScript interface for props (`{ComponentName}Props`)
- No `any` types
- Tailwind CSS with semantic colour palette only:
  - Critical/Error: `text-red-700 bg-red-50 border-red-200`
  - Warning/High: `text-amber-700 bg-amber-50 border-amber-200`
  - Good: `text-green-700 bg-green-50 border-green-200`
- Loading skeleton state (when `isLoading` prop is true) with `aria-label="Loading..."`
- Error state using `<ErrorBanner>` (never blank screen)
- `<DataFreshnessBadge>` if component shows data with a timestamp
- WCAG 2.1 AA: aria-labels on interactive elements, `role="alert"` on errors, keyboard-navigable

### Hook file (if new API call needed)
`mfe/{mfe}/src/hooks/use{ComponentName}.ts`
- Use generated client from `@smartretail/api-client`
- Keep stale data on error (never `setData(null)` in catch)
- Export: `{ data, isLoading, error, lastUpdated, refetch }`

### Test file
`mfe/{mfe}/src/components/dashboard/{ComponentName}.test.tsx`
- `vi.mock('@smartretail/api-client')` or mock the hook
- Test: loading state (skeleton visible), error state (ErrorBanner visible), success state
- Test: correct aria-labels, correct severity colours for status values

After creation, run `npm run type-check` and `npm run lint` in `mfe/{mfe}/`.
