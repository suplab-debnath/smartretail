import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { SupplierScorecardTab } from '../../components/SupplierScorecardTab'
import { useSupplierPerformance } from '../../hooks/useSupplierPerformance'
import type { SupplierPerformanceDashboardResponse } from '../../types'
import type { FetchError } from '@smartretail/auth'

vi.mock('@smartretail/auth', () => ({
  ErrorBanner: ({ error }: { error: FetchError | null }) =>
    error ? <div data-testid="error-banner">Error: {error.message}</div> : null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('../../hooks/useSupplierPerformance')
const mockedHook = vi.mocked(useSupplierPerformance)

const mockData: SupplierPerformanceDashboardResponse = {
  suppliers: [
    { supplierId: 'sup-1', supplierName: 'Acme Corp', onTimeDeliveryRate: 92.5, poAcknowledgementSlaCompliance: 88.0, openExceptions: 1, avgLeadTimeVarianceDays: 0.5, totalPoCount: 120, totalPoValue: 480000 },
    { supplierId: 'sup-2', supplierName: 'Beta Ltd',  onTimeDeliveryRate: 72.0, poAcknowledgementSlaCompliance: 65.0, openExceptions: 0, avgLeadTimeVarianceDays: -1.2, totalPoCount: 80, totalPoValue: 200000 },
    { supplierId: 'sup-3', supplierName: 'Gamma Inc', onTimeDeliveryRate: 60.0, poAcknowledgementSlaCompliance: 55.0, openExceptions: 3, avgLeadTimeVarianceDays: 3.0, totalPoCount: 40, totalPoValue: 100000 },
  ],
  dataFreshness: '2026-05-18T00:00:00Z',
}

const serverError: FetchError = { kind: 'server', status: 500, message: 'HTTP 500' }

beforeEach(() => vi.clearAllMocks())

describe('SupplierScorecardTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByText('Loading supplier scorecard…')).toBeInTheDocument()
  })

  it('shows error banner', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: serverError, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByTestId('error-banner')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
  })

  it('shows empty state when no suppliers', () => {
    mockedHook.mockReturnValue({ data: { suppliers: [], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByText('No supplier data available')).toBeInTheDocument()
  })

  it('renders supplier names and OTD rates', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
    expect(screen.getByText('Beta Ltd')).toBeInTheDocument()
    expect(screen.getByText('92.5%')).toBeInTheDocument()
  })

  it('applies green color for OTD >= 90', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    // 92.5% → green
    const cell = screen.getByText('92.5%')
    expect(cell.className).toContain('text-green-600')
  })

  it('applies red color for OTD < 75', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    // 72.0% → red (< 75)
    const cell = screen.getByText('72.0%')
    expect(cell.className).toContain('text-red-600')
  })

  it('formats positive lead time variance with + prefix', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByText('+0.5d')).toBeInTheDocument()
  })

  it('formats negative lead time variance without + prefix', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    expect(screen.getByText('-1.2d')).toBeInTheDocument()
  })

  it('shows open exceptions count in red when > 0', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierScorecardTab />)
    // sup-1 has 1 exception
    const cells = screen.getAllByText('1')
    expect(cells.some(c => c.className.includes('text-red-600'))).toBe(true)
  })
})
