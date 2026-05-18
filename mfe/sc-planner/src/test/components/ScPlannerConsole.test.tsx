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

// vi.hoisted ensures the mock fn is created before module resolution so the
// @smartretail/auth factory can reference it without a static import.
const { mockedUseAuth } = vi.hoisted(() => ({ mockedUseAuth: vi.fn() }))

vi.mock('@smartretail/auth', () => ({ useAuth: mockedUseAuth }))
vi.mock('../../hooks/useScPlannerDashboard')
vi.mock('../../hooks/useExceptionQueue')
vi.mock('../../hooks/useInventoryPositions')
vi.mock('../../hooks/useForecast')
vi.mock('../../hooks/usePendingApprovals')
vi.mock('../../hooks/useSupplierOrders')
vi.mock('../../hooks/useSupplierPerformance')
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
  vi.mocked(useInventoryPositions).mockReturnValue({ data: null, loading: false, error: null })
  vi.mocked(useForecast).mockReturnValue({ data: null, loading: false, error: null })
  vi.mocked(usePendingApprovals).mockReturnValue({ orders: [], loading: false, error: null, removeOrder: vi.fn() })
  vi.mocked(useSupplierOrders).mockReturnValue({ data: null, loading: false, error: null })
  vi.mocked(useSupplierPerformance).mockReturnValue({ data: null, loading: false, error: null })
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

  it('renders all 8 tab buttons', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    render(<ScPlannerConsole />)
    for (const label of ['Exception Queue', 'Inventory Overview', 'Demand Forecast', 'Stockout Risk', 'Approvals', 'Supplier Orders', 'Forecast Adjustment', 'Supplier Scorecard']) {
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
    expect(screen.getByText(/Forecast MAPE/)).toBeInTheDocument()
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
})
