import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { StoreDashboard } from '../../components/StoreDashboard'
import { useStoreManagerDashboard } from '../../hooks/useStoreManagerDashboard'
import type { StoreManagerDashboardResponse } from '../../types'
import type { FetchError } from '@smartretail/auth'

vi.mock('@smartretail/auth', () => ({
  ErrorBanner: ({ error }: { error: FetchError | null }) =>
    error ? <div data-testid="error-banner">Error: {error.message}</div> : null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('../../hooks/useStoreManagerDashboard')

const mockedHook = vi.mocked(useStoreManagerDashboard)

const mockData: StoreManagerDashboardResponse = {
  dcId: 'DC-LONDON',
  alertKpi: { criticalCount: 1, highCount: 2, mediumCount: 3, totalActive: 6 },
  totalOnHandUnits: 12000,
  pendingReplenishmentCount: 4,
  forecastCoveragePct: 78.3,
  alerts: [
    {
      alertId: 'a1',
      skuId: 'SKU-001',
      dcId: 'DC-LONDON',
      alertType: 'LOW_STOCK',
      severity: 'HIGH',
      onHand: 40,
      reorderPoint: 100,
      raisedAt: '2026-05-18T08:00:00Z',
    },
  ],
  alertsPage: 0,
  alertsTotalPages: 1,
  dataFreshness: '2026-05-18T08:00:00Z',
}

const noopRefresh = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
})

describe('StoreDashboard', () => {
  it('shows loading spinner when loading and no data yet', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.getByText('Loading dashboard…')).toBeInTheDocument()
  })

  it('shows error banner on fetch failure', () => {
    const serverError: FetchError = { kind: 'server', status: 503, message: 'HTTP 503' }
    mockedHook.mockReturnValue({ data: null, loading: false, error: serverError, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.getByTestId('error-banner')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()
  })

  it('renders KPI row and alert list when data is loaded', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.getByText('Low Stock Alerts')).toBeInTheDocument()
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
  })

  it('renders page heading with DC id', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.getByText('Store Manager Dashboard')).toBeInTheDocument()
    expect(screen.getByText('SmartRetail · DC-LONDON')).toBeInTheDocument()
  })

  it('hides loading state once data arrives', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.queryByText('Loading dashboard…')).not.toBeInTheDocument()
  })

  it('resets page to 0 and updates dcId when DC selector changes', async () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    const select = screen.getByRole('combobox')
    await userEvent.selectOptions(select, 'DC-MANCHESTER')
    // After change, hook is called with new DC and page 0
    const lastCall = mockedHook.mock.calls[mockedHook.mock.calls.length - 1]
    expect(lastCall[0]).toBe('DC-MANCHESTER')
    expect(lastCall[1]).toBe(0)
  })

  it('shows data freshness indicator when data is loaded', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refresh: noopRefresh })
    render(<StoreDashboard />)
    expect(screen.getByRole('button', { name: /refresh/i })).toBeInTheDocument()
  })
})
