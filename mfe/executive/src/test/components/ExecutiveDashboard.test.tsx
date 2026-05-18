import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ExecutiveDashboard } from '../../components/ExecutiveDashboard'
import { useExecutiveDashboard } from '../../hooks/useExecutiveDashboard'
import type { ExecutiveDashboardResponse } from '../../types'
import type { FetchError } from '@smartretail/auth'

const { mockedUseAuth } = vi.hoisted(() => ({ mockedUseAuth: vi.fn() }))

vi.mock('@smartretail/auth', () => ({
  useAuth: mockedUseAuth,
  ErrorBanner: ({ error }: { error: { message: string } | null }) =>
    error ? <div data-testid="error-banner">Error: {error.message}</div> : null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('../../hooks/useExecutiveDashboard')
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Line: () => null, Bar: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
  ReferenceLine: () => null,
}))

const mockedDash = vi.mocked(useExecutiveDashboard)

const mockData: ExecutiveDashboardResponse = {
  kpis: {
    forecastAccuracy: {
      latestMape: 0.08, trend: 'IMPROVING',
      history: [{ runDate: '2026-05-10', mape: 0.09 }],
    },
    stockoutFrequency: {
      last30Days: 3, trend: 'DECREASING',
      history: [{ alertDate: '2026-05-10', criticalCount: 1 }],
    },
    replenishmentCycleTime: {
      averageDays: 4.2, trend: 'STABLE',
      history: [{ weekStart: '2026-05-10', averageDays: 4.0, poCount: 5 }],
    },
    onTimeDelivery: { rate: 0.92, trend: 'STABLE' },
    supplierPerformance: [{
      supplierId: 's1', supplierName: 'Acme Ltd', otdRate: 0.92, fillRate: 0.95,
      earlyCount: 5, onTimeCount: 20, lateCount: 2, openExceptions: 0,
    }],
  },
  dataFreshness: '2026-05-18T00:00:00Z',
}

const defaultDashReturn = { data: null, loading: false, error: null, lastUpdated: null, refresh: vi.fn() }

const authWithRole = (role = 'EXECUTIVE') => ({
  isAuthenticated: true, isLoading: false,
  signIn: vi.fn(), signOut: vi.fn(),
  user: { email: 'exec@example.com' },
  hasRole: (r: string) => r === role,
})

beforeEach(() => {
  vi.clearAllMocks()
  mockedDash.mockReturnValue(defaultDashReturn)
})

describe('ExecutiveDashboard', () => {
  it('shows auth loading spinner', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: true, signIn: vi.fn(), signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Checking authentication...')).toBeInTheDocument()
  })

  it('shows redirecting message when not authenticated', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: false, signIn: vi.fn(), signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Redirecting to login...')).toBeInTheDocument()
  })

  it('shows access denied for wrong role', () => {
    mockedUseAuth.mockReturnValue({ isAuthenticated: true, isLoading: false, signIn: vi.fn(), signOut: vi.fn(), user: { email: 'x@x.com' }, hasRole: () => false })
    render(<ExecutiveDashboard />)
    expect(screen.getByText(/Access Denied/)).toBeInTheDocument()
  })

  it('shows loading dashboard while data is loading', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, loading: true })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Loading dashboard…')).toBeInTheDocument()
  })

  it('shows error banner when fetch fails', () => {
    const serverError: FetchError = { kind: 'server', status: 500, message: 'HTTP 500' }
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, error: serverError })
    render(<ExecutiveDashboard />)
    expect(screen.getByTestId('error-banner')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
  })

  it('renders dashboard heading for EXECUTIVE role', () => {
    mockedUseAuth.mockReturnValue(authWithRole('EXECUTIVE'))
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Executive Insights Dashboard')).toBeInTheDocument()
  })

  it('renders dashboard heading for ADMIN role', () => {
    mockedUseAuth.mockReturnValue({ ...authWithRole('EXECUTIVE'), hasRole: (r: string) => r === 'ADMIN' })
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Executive Insights Dashboard')).toBeInTheDocument()
  })

  it('renders dashboard heading for SC_PLANNER role', () => {
    mockedUseAuth.mockReturnValue({ ...authWithRole('EXECUTIVE'), hasRole: (r: string) => r === 'SC_PLANNER' })
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Executive Insights Dashboard')).toBeInTheDocument()
  })

  it('renders all 4 KPI card labels', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('Forecast Accuracy')).toBeInTheDocument()
    expect(screen.getByText('Stockout Frequency (30d)')).toBeInTheDocument()
    expect(screen.getByText('Replenishment Cycle Time')).toBeInTheDocument()
    expect(screen.getByText('On-Time Delivery')).toBeInTheDocument()
  })

  it('renders user email and sign out button', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    expect(screen.getByText('exec@example.com')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('calls signOut when Sign out is clicked', async () => {
    const signOut = vi.fn()
    mockedUseAuth.mockReturnValue({ ...authWithRole(), signOut })
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(signOut).toHaveBeenCalled()
  })

  it('expands forecast detail panel when Forecast Accuracy card is clicked', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    await userEvent.click(screen.getByRole('button', { name: /Forecast Accuracy/ }))
    expect(screen.getByText('MAPE Trend — Last 30 Forecast Runs')).toBeInTheDocument()
  })

  it('expands stockout detail panel when Stockout card is clicked', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    await userEvent.click(screen.getByRole('button', { name: /Stockout Frequency/ }))
    expect(screen.getByText('Stockout Alert History (Last 30 Days)')).toBeInTheDocument()
  })

  it('expands cycle time detail panel when Cycle Time card is clicked', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    await userEvent.click(screen.getByRole('button', { name: /Replenishment Cycle Time/ }))
    expect(screen.getByText('Replenishment Cycle Time — Weekly Average (Last 90 Days)')).toBeInTheDocument()
  })

  it('expands OTD detail panel when OTD card is clicked', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    await userEvent.click(screen.getByRole('button', { name: /On-Time Delivery/ }))
    expect(screen.getByText('Delivery Performance Distribution')).toBeInTheDocument()
  })

  it('collapses panel when same card is clicked again', async () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData })
    render(<ExecutiveDashboard />)
    const btn = screen.getByRole('button', { name: /Forecast Accuracy/ })
    await userEvent.click(btn)
    expect(screen.getByText('MAPE Trend — Last 30 Forecast Runs')).toBeInTheDocument()
    await userEvent.click(btn)
    expect(screen.queryByText('MAPE Trend — Last 30 Forecast Runs')).not.toBeInTheDocument()
  })

  it('shows lastUpdated timestamp when present', () => {
    mockedUseAuth.mockReturnValue(authWithRole())
    const lastUpdated = new Date('2026-05-18T10:30:00Z')
    mockedDash.mockReturnValue({ ...defaultDashReturn, data: mockData, lastUpdated })
    render(<ExecutiveDashboard />)
    expect(screen.getByText(/Last updated:/)).toBeInTheDocument()
  })

  it('calls signIn when not authenticated and not loading', () => {
    const signIn = vi.fn()
    mockedUseAuth.mockReturnValue({ isAuthenticated: false, isLoading: false, signIn, signOut: vi.fn(), user: null, hasRole: () => false })
    render(<ExecutiveDashboard />)
    expect(signIn).toHaveBeenCalled()
  })
})
