import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ScPlannerConsole } from '../../components/ScPlannerConsole'
import { useScPlannerDashboard } from '../../hooks/useScPlannerDashboard'
import { useExceptionQueue } from '../../hooks/useExceptionQueue'
import { useInventoryPositions } from '../../hooks/useInventoryPositions'
import { useForecast } from '../../hooks/useForecast'
import { usePendingApprovals } from '../../hooks/usePendingApprovals'
import { useSupplierOrders } from '../../hooks/useSupplierOrders'
import { useSupplierPerformance } from '../../hooks/useSupplierPerformance'
import { useSuppliers } from '../../hooks/useSuppliers'

// vi.hoisted ensures the mock fn is created before module resolution so the
// @smartretail/auth factory can reference it without a static import.
const { mockedUseAuth } = vi.hoisted(() => ({ mockedUseAuth: vi.fn() }))

vi.mock('@smartretail/auth', () => ({
  useAuth: mockedUseAuth,
  ErrorBanner: () => null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  getApiBase: () => '',   // local/test mode — Vite proxy handles routing, no base URL needed
}))
vi.mock('../../hooks/useScPlannerDashboard')
vi.mock('../../hooks/useExceptionQueue')
vi.mock('../../hooks/useInventoryPositions')
vi.mock('../../hooks/useForecast')
vi.mock('../../hooks/usePendingApprovals')
vi.mock('../../hooks/useSupplierOrders')
vi.mock('../../hooks/useSupplierPerformance')
vi.mock('../../hooks/useSuppliers')
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  ComposedChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Area: () => null, Line: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
}))

const mockedDash = vi.mocked(useScPlannerDashboard)
const mockedExceptions = vi.mocked(useExceptionQueue)

const defaultDashReturn = { data: null, loading: false, error: null, lastUpdated: null, refresh: vi.fn() }
const defaultExcReturn  = { data: null, loading: false, error: null, refetch: vi.fn() }

const authWithRole = (role?: string) => ({
  isAuthenticated: true,
  isLoading: false,
  signIn: vi.fn(), signOut: vi.fn(),
  user: { email: 'planner@example.com' },
  hasRole: (r: string) => r === (role ?? 'SC_PLANNER'),
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useInventoryPositions).mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() })
  vi.mocked(useForecast).mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() })
  vi.mocked(usePendingApprovals).mockReturnValue({ orders: [], loading: false, error: null, removeOrder: vi.fn(), refetch: vi.fn() })
  vi.mocked(useSupplierOrders).mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() })
  vi.mocked(useSupplierPerformance).mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() })
  vi.mocked(useSuppliers).mockReturnValue({})
  mockedExceptions.mockReturnValue(defaultExcReturn)
  mockedDash.mockReturnValue(defaultDashReturn)
  vi.stubGlobal('crypto', { randomUUID: () => 'uuid-1' })
})

describe('ScPlannerConsole', () => {
  it('shows auth loading spinner', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: true, signIn: vi.fn(), signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ScPlannerConsole />)
    expect(screen.getByText('Checking authentication…')).toBeInTheDocument()
  })

  it('shows redirecting message when not authenticated', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: false, signIn: vi.fn(), signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ScPlannerConsole />)
    expect(screen.getByText('Redirecting to login…')).toBeInTheDocument()
  })

  it('shows access denied when authenticated but wrong role', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: true, isLoading: false, signIn: vi.fn(), signOut: vi.fn(), user: { email: 'x@x.com' }, hasRole: () => false })
    render(<ScPlannerConsole />)
    expect(screen.getByText(/Access Denied/)).toBeInTheDocument()
  })

  it('renders main console for SC_PLANNER role', () => {
    mockedUseAuth.mockReturnValue(authWithRole('SC_PLANNER'))
    render(<ScPlannerConsole />)
    expect(screen.getByText('SC Planner Console')).toBeInTheDocument()
  })

  it('renders main console for ADMIN role', () => {
    mockedUseAuth.mockReturnValue({ ...authWithRole('SC_PLANNER'), hasRole: (r: string) => r === 'ADMIN' })
    render(<ScPlannerConsole />)
    expect(screen.getByText('SC Planner Console')).toBeInTheDocument()
  })

  it('renders user email and sign out button', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    expect(screen.getByText('planner@example.com')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('renders all 7 tab buttons', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    for (const label of ['Exception Queue', 'Inventory Overview', 'Demand Forecast', 'Stockout Risk', 'Approvals', 'Supplier Orders', 'Supplier Scorecard']) {
      expect(screen.getByRole('button', { name: new RegExp(label) })).toBeInTheDocument()
    }
  })

  it('shows alert badge on Exception Queue tab when alerts exist', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: { pendingApprovalCount: 0, activeAlertCount: 5, forecastAccuracy: { latestMape: 0.08, mapeThreshold: 0.15, lastRunAt: '', status: 'WITHIN_THRESHOLD' }, dataFreshness: '' } })
    render(<ScPlannerConsole />)
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('shows MAPE in header when dashboard data available', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: { pendingApprovalCount: 2, activeAlertCount: 3, forecastAccuracy: { latestMape: 0.12, mapeThreshold: 0.15, lastRunAt: '', status: 'WITHIN_THRESHOLD' }, dataFreshness: '' } })
    render(<ScPlannerConsole />)
    expect(screen.getByText(/Forecast Error/)).toBeInTheDocument()
    expect(screen.getByText('12.0%')).toBeInTheDocument()
  })

  it('switches to Inventory Overview tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Inventory Overview' }))
    expect(screen.getByText('No inventory positions found')).toBeInTheDocument()
  })

  it('calls signOut when Sign out is clicked', async () => {
    const signOut = vi.fn()
    mockedUseAuth.mockReturnValue({ ...authWithRole(), signOut })
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(signOut).toHaveBeenCalled()
  })

  it('switches to Demand Forecast tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Demand Forecast' }))
    // Tab is now visible (visitedTabs includes 'forecast')
    expect(screen.getByRole('button', { name: 'Demand Forecast' })).toBeInTheDocument()
  })

  it('switches to Approvals tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Approvals' }))
    expect(screen.getByRole('button', { name: 'Approvals' })).toBeInTheDocument()
  })

  it('switches to Supplier Orders tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Supplier Orders' }))
    expect(screen.getByRole('button', { name: 'Supplier Orders' })).toBeInTheDocument()
  })

  it('switches to Supplier Scorecard tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Supplier Scorecard' }))
    expect(screen.getByRole('button', { name: 'Supplier Scorecard' })).toBeInTheDocument()
  })

  it('switches to Demo tab on click', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    await userEvent.click(screen.getByRole('button', { name: 'Demo' }))
    expect(screen.getByRole('button', { name: 'Demo' })).toBeInTheDocument()
  })

  it('shows 99+ badge when alert count exceeds 99', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({
      ...defaultDashReturn,
      data: { pendingApprovalCount: 0, activeAlertCount: 100, forecastAccuracy: { latestMape: 0.08, mapeThreshold: 0.15, lastRunAt: '', status: 'WITHIN_THRESHOLD' }, dataFreshness: '' },
    })
    render(<ScPlannerConsole />)
    expect(screen.getByText('99+')).toBeInTheDocument()
  })

  it('shows approval badge count on Approvals tab', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({
      ...defaultDashReturn,
      data: { pendingApprovalCount: 7, activeAlertCount: 0, forecastAccuracy: { latestMape: 0.08, mapeThreshold: 0.15, lastRunAt: '', status: 'WITHIN_THRESHOLD' }, dataFreshness: '' },
    })
    render(<ScPlannerConsole />)
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('shows MAPE above-threshold label when status is ABOVE_THRESHOLD', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({
      ...defaultDashReturn,
      data: { pendingApprovalCount: 0, activeAlertCount: 0, forecastAccuracy: { latestMape: 0.25, mapeThreshold: 0.15, lastRunAt: '', status: 'ABOVE_THRESHOLD' }, dataFreshness: '' },
    })
    render(<ScPlannerConsole />)
    expect(screen.getByText(/Above threshold/)).toBeInTheDocument()
  })

  it('shows last updated timestamp when present', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    const lastUpdated = new Date('2026-05-18T10:30:00Z')
    mockedDash.mockReturnValue({ ...defaultDashReturn, lastUpdated })
    render(<ScPlannerConsole />)
    expect(screen.getByText(/Last updated:/)).toBeInTheDocument()
  })

  it('calls signIn when not authenticated and not loading', () => {
    const signIn = vi.fn()
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: false, signIn, signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ScPlannerConsole />)
    expect(signIn).toHaveBeenCalled()
  })
})
