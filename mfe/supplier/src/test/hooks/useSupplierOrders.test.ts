import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useSupplierOrders } from '../../hooks/useSupplierOrders'

// useAuth is called directly inside the hook — must be mocked before rendering
vi.mock('@smartretail/auth', () => ({
  useAuth: vi.fn(),
}))

import { useAuth } from '@smartretail/auth'
const mockedUseAuth = vi.mocked(useAuth as () => { token: string | null })

const FRESHNESS = '2026-05-18T12:00:00Z'
const makeOrder = (overrides = {}) => ({
  supplierPoId: 'spo-1',
  poId: 'po-1',
  supplierId: 'sup-1',
  supplierName: 'Acme Beverages Ltd',
  skuId: 'SKU-BEV-001',
  dcId: 'DC-LONDON',
  quantity: 500,
  shipmentStatus: 'DISPATCHED' as const,
  confirmedAt: null,
  dispatchedAt: '2026-05-17T00:00:00Z',
  eta: '2026-05-20',
  lastUpdateAt: '2026-05-17T08:00:00Z',
  ...overrides,
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useSupplierOrders', () => {
  it('starts with isLoading=true before fetch resolves', () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    const { result } = renderHook(() => useSupplierOrders())
    expect(result.current.isLoading).toBe(true)
    expect(result.current.orders).toEqual([])
    expect(result.current.error).toBeNull()
  })

  it('populates orders and dataFreshness on success', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    const order = makeOrder()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ orders: [order], dataFreshness: FRESHNESS }),
      })
    )
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.orders).toHaveLength(1)
    expect(result.current.orders[0].supplierPoId).toBe('spo-1')
    expect(result.current.dataFreshness).toBe(FRESHNESS)
    expect(result.current.error).toBeNull()
  })

  it('sets error message on non-ok HTTP response', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 503 })
    )
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.error).toBe('HTTP 503')
    expect(result.current.orders).toEqual([])
  })

  it('sets error message on network failure', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network timeout')))
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.error).toBe('Network timeout')
  })

  it('sends X-Dev-Role header when token is mock-token', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ orders: [], dataFreshness: FRESHNESS }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const headers = fetchMock.mock.calls[0][1].headers
    expect(headers['X-Dev-Role']).toBe('SUPPLIER_ADMIN')
    expect(headers['Authorization']).toBeUndefined()
  })

  it('sends Authorization header when real JWT token is present', async () => {
    mockedUseAuth.mockReturnValue({ token: 'real.jwt.token' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ orders: [], dataFreshness: FRESHNESS }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const headers = fetchMock.mock.calls[0][1].headers
    expect(headers['Authorization']).toBe('Bearer real.jwt.token')
    expect(headers['X-Dev-Role']).toBeUndefined()
  })

  it('defaults orders to empty array when response.orders is missing', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ dataFreshness: FRESHNESS }), // no orders key
      })
    )
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.orders).toEqual([])
  })

  it('exposes a refresh function that re-fetches', async () => {
    mockedUseAuth.mockReturnValue({ token: 'mock-token' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ orders: [], dataFreshness: FRESHNESS }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const callsBefore = fetchMock.mock.calls.length
    result.current.refresh()
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore))
  })
})
