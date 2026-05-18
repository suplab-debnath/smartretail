import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useSupplierOrders } from '../../hooks/useSupplierOrders'
import type { SupplierOrderListResponse } from '../../types'

const mockOrder = { supplierPoId: 'spo-1', poId: 'po-1', supplierId: 'sup-1', supplierName: 'Acme', skuId: 'SKU-001', dcId: 'DC-LONDON', quantity: 100, shipmentStatus: 'DISPATCHED' as const, confirmedAt: null, dispatchedAt: '2026-05-17T00:00:00Z', eta: '2026-05-20T00:00:00Z', lastUpdateAt: '2026-05-17T00:00:00Z' }
const mockResponse: SupplierOrderListResponse = { orders: [mockOrder], dataFreshness: '2026-05-18T00:00:00Z' }

afterEach(() => vi.restoreAllMocks())

describe('useSupplierOrders', () => {
  it('fetches orders on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
  })

  it('includes status filter in URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useSupplierOrders('DISPATCHED'))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toContain('status=DISPATCHED')
  })

  it('omits status param when undefined', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useSupplierOrders(undefined))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/supplier/orders')
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }))
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'server', status: 500 })
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('timeout')))
    const { result } = renderHook(() => useSupplierOrders())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'network' })
  })
})
