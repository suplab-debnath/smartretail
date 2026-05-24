import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { SupplierOrderTrackingTab } from '../../components/SupplierOrderTrackingTab'
import { useSupplierOrders } from '../../hooks/useSupplierOrders'
import type { SupplierOrderListResponse } from '../../types'
import type { FetchError } from '@smartretail/auth'

vi.mock('@smartretail/auth', () => ({
  ErrorBanner: ({ error }: { error: FetchError | null }) =>
    error ? <div data-testid="error-banner">Error: {error.message}</div> : null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('../../hooks/useSupplierOrders')
const mockedHook = vi.mocked(useSupplierOrders)

const makeOrder = (overrides = {}) => ({
  supplierPoId: 'spo-1', poId: 'po-1', supplierId: 'sup-1', supplierName: 'Acme',
  skuId: 'SKU-001', dcId: 'DC-LONDON', quantity: 100, shipmentStatus: 'DISPATCHED' as const,
  confirmedAt: null, dispatchedAt: '2026-05-17T00:00:00Z',
  eta: '2026-05-20T00:00:00Z', lastUpdateAt: '2026-05-17T00:00:00Z',
  ...overrides,
})

const mockData: SupplierOrderListResponse = { orders: [makeOrder()], dataFreshness: '2026-05-18T00:00:00Z' }
const serverError: FetchError = { kind: 'server', status: 500, message: 'HTTP 500' }

beforeEach(() => vi.clearAllMocks())

describe('SupplierOrderTrackingTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    expect(screen.getByText('Loading supplier orders…')).toBeInTheDocument()
  })

  it('shows error banner', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: serverError, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    expect(screen.getByTestId('error-banner')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
  })

  it('shows empty state when no orders', () => {
    mockedHook.mockReturnValue({ data: { orders: [], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    expect(screen.getByText('No supplier orders found')).toBeInTheDocument()
  })

  it('renders order data', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    expect(screen.getByText('Acme')).toBeInTheDocument()
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
    // DISPATCHED appears in both select option and badge — at least 2 occurrences expected
    expect(screen.getAllByText('DISPATCHED').length).toBeGreaterThanOrEqual(2)
  })

  it('renders EXCEPTION row with red background indicator', () => {
    const excOrder = makeOrder({ shipmentStatus: 'EXCEPTION' as const })
    mockedHook.mockReturnValue({ data: { orders: [excOrder], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    // EXCEPTION appears in both select option and the EXCEPTION indicator
    expect(screen.getAllByText('EXCEPTION').length).toBeGreaterThanOrEqual(2)
  })

  it('shows — when eta is null', () => {
    const order = makeOrder({ eta: null })
    mockedHook.mockReturnValue({ data: { orders: [order], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('calls hook with selected status filter', async () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    await userEvent.selectOptions(screen.getByRole('combobox'), 'EXCEPTION')
    expect(mockedHook).toHaveBeenCalledWith('EXCEPTION', 0)
  })

  it('calls hook with undefined when ALL selected', async () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<SupplierOrderTrackingTab />)
    await userEvent.selectOptions(screen.getByRole('combobox'), 'EXCEPTION')
    await userEvent.selectOptions(screen.getByRole('combobox'), 'ALL')
    expect(mockedHook).toHaveBeenCalledWith(undefined, 0)
  })
})
