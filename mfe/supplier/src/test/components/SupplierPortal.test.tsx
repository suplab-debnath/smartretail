import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { SupplierPortal } from '../../components/SupplierPortal'
import { useSupplierOrders } from '../../hooks/useSupplierOrders'
import type { SupplierOrder } from '../../hooks/useSupplierOrders'

// Mock both auth and the hook so this test is purely presentational
vi.mock('@smartretail/auth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../hooks/useSupplierOrders')
const mockedHook = vi.mocked(useSupplierOrders)

import { useAuth } from '@smartretail/auth'
const mockedUseAuth = vi.mocked(useAuth as () => {
  isLoading: boolean
  isAuthenticated: boolean
  signIn: () => void
  user: { email?: string } | null
})

const FRESHNESS = '2026-05-18T12:00:00Z'
const noop = vi.fn()

const makeOrder = (overrides: Partial<SupplierOrder> = {}): SupplierOrder => ({
  supplierPoId: `spo-${Math.random()}`,
  poId: 'c1b2c3d4-0000-0000-0000-000000000001',
  supplierId: 'sup-1',
  supplierName: 'Acme Beverages Ltd',
  skuId: 'SKU-BEV-001',
  dcId: 'DC-LONDON',
  quantity: 500,
  shipmentStatus: 'DISPATCHED',
  confirmedAt: null,
  dispatchedAt: null,
  eta: '2026-05-20',
  lastUpdateAt: null,
  ...overrides,
})

function authLoading() {
  mockedUseAuth.mockReturnValue({ isLoading: true, isAuthenticated: false, signIn: noop, user: null })
}
function authUnauthenticated(signIn = noop) {
  mockedUseAuth.mockReturnValue({ isLoading: false, isAuthenticated: false, signIn, user: null })
}
function authAuthenticated(email = 'supplier@acme.com') {
  mockedUseAuth.mockReturnValue({ isLoading: false, isAuthenticated: true, signIn: noop, user: { email } })
}

function hookReady(orders: SupplierOrder[] = [], error: string | null = null) {
  mockedHook.mockReturnValue({
    orders,
    dataFreshness: FRESHNESS,
    isLoading: false,
    error,
    refresh: noop,
  })
}
function hookLoading() {
  mockedHook.mockReturnValue({ orders: [], dataFreshness: FRESHNESS, isLoading: true, error: null, refresh: noop })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SupplierPortal', () => {
  describe('auth states', () => {
    it('shows loading spinner while auth is initialising', () => {
      authLoading()
      hookReady()
      render(<SupplierPortal />)
      expect(screen.getByText(/loading/i)).toBeInTheDocument()
    })

    it('shows sign-in screen when not authenticated', () => {
      authUnauthenticated()
      hookReady()
      render(<SupplierPortal />)
      expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
      expect(screen.getByText(/SmartRetail Supplier Portal/i)).toBeInTheDocument()
    })

    it('calls signIn when Sign In button is clicked', async () => {
      const signIn = vi.fn()
      authUnauthenticated(signIn)
      hookReady()
      render(<SupplierPortal />)
      await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
      expect(signIn).toHaveBeenCalledOnce()
    })

    it('shows portal header when authenticated', () => {
      authAuthenticated()
      hookReady()
      render(<SupplierPortal />)
      expect(screen.getByText('Supplier Portal')).toBeInTheDocument()
      expect(screen.getByText('supplier@acme.com')).toBeInTheDocument()
    })
  })

  describe('data states', () => {
    it('shows loading orders message while hook is loading', () => {
      authAuthenticated()
      hookLoading()
      render(<SupplierPortal />)
      expect(screen.getByText(/loading orders/i)).toBeInTheDocument()
    })

    it('shows error message when hook has an error', () => {
      authAuthenticated()
      hookReady([], 'HTTP 503')
      render(<SupplierPortal />)
      expect(screen.getByText(/Failed to load orders.*HTTP 503/)).toBeInTheDocument()
    })

    it('shows order table when orders are loaded', () => {
      authAuthenticated()
      hookReady([makeOrder()])
      render(<SupplierPortal />)
      expect(screen.getByText('SKU-BEV-001')).toBeInTheDocument()
    })

    it('shows empty state when authenticated but no orders', () => {
      authAuthenticated()
      hookReady([])
      render(<SupplierPortal />)
      expect(screen.getByText('No orders found')).toBeInTheDocument()
    })
  })

  describe('exception badge', () => {
    it('shows exception count badge when there are exceptions', () => {
      authAuthenticated()
      hookReady([
        makeOrder({ shipmentStatus: 'EXCEPTION' }),
        makeOrder({ shipmentStatus: 'EXCEPTION' }),
        makeOrder({ shipmentStatus: 'DISPATCHED' }),
      ])
      render(<SupplierPortal />)
      expect(screen.getByText('2 Exceptions')).toBeInTheDocument()
    })

    it('shows singular "Exception" when count is 1', () => {
      authAuthenticated()
      hookReady([makeOrder({ shipmentStatus: 'EXCEPTION' })])
      render(<SupplierPortal />)
      expect(screen.getByText('1 Exception')).toBeInTheDocument()
    })

    it('does not show exception badge when there are no exceptions', () => {
      authAuthenticated()
      hookReady([makeOrder({ shipmentStatus: 'DISPATCHED' })])
      render(<SupplierPortal />)
      expect(screen.queryByText(/Exception/)).not.toBeInTheDocument()
    })
  })

  describe('summary cards', () => {
    it('renders status summary cards for PENDING, CONFIRMED, DISPATCHED, EXCEPTION', () => {
      authAuthenticated()
      hookReady([
        makeOrder({ shipmentStatus: 'PENDING' }),
        makeOrder({ shipmentStatus: 'CONFIRMED' }),
        makeOrder({ shipmentStatus: 'DISPATCHED' }),
      ])
      render(<SupplierPortal />)
      // Each status appears in the summary card label — use getAllByText since
      // the ShipmentStatusBadge inside the table also renders the same text
      expect(screen.getAllByText('PENDING').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('CONFIRMED').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('DISPATCHED').length).toBeGreaterThanOrEqual(1)
      // EXCEPTION card always rendered even with 0 count
      expect(screen.getAllByText('EXCEPTION').length).toBeGreaterThanOrEqual(1)
    })
  })
})
