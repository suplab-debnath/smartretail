import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useSuppliers } from '../../hooks/useSuppliers'
import type { SupplierPerformanceDashboardResponse } from '../../types'

const mockResponse: SupplierPerformanceDashboardResponse = {
  suppliers: [
    {
      supplierId: 'sup-1', supplierName: 'Acme Beverages',
      onTimeDeliveryRate: 0.9, poAcknowledgementSlaCompliance: 0.9,
      openExceptions: 0, avgLeadTimeVarianceDays: 0.5,
      totalPoCount: 10, totalPoValue: 50000,
    },
    {
      supplierId: 'sup-2', supplierName: 'Fresh Dairy Co',
      onTimeDeliveryRate: 0.75, poAcknowledgementSlaCompliance: 0.75,
      openExceptions: 1, avgLeadTimeVarianceDays: 1.2,
      totalPoCount: 8, totalPoValue: 32000,
    },
  ],
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('useSuppliers', () => {
  it('returns empty map initially', () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    const { result } = renderHook(() => useSuppliers())
    expect(result.current).toEqual({})
  })

  it('resolves to supplierId → supplierName map on success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useSuppliers())
    await waitFor(() => expect(Object.keys(result.current).length).toBeGreaterThan(0))
    expect(result.current).toEqual({
      'sup-1': 'Acme Beverages',
      'sup-2': 'Fresh Dairy Co',
    })
  })

  it('calls ARS /v1/dashboard/supplier-performance (not SUP)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useSuppliers())
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/v1/dashboard/supplier-performance')
    expect(url).not.toContain('/v1/supplier')
    expect((init?.headers as Record<string, string>)?.['X-Dev-Role']).toBe('SC_PLANNER')
  })

  it('returns empty map on network failure (swallows error)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useSuppliers())
    await waitFor(() => {
      expect(Object.keys(result.current).length).toBe(0)
    }, { timeout: 300 })
    expect(result.current).toEqual({})
  })

  it('returns empty map on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }))
    const { result } = renderHook(() => useSuppliers())
    await waitFor(() => {
      expect(Object.keys(result.current).length).toBe(0)
    }, { timeout: 300 })
    expect(result.current).toEqual({})
  })
})
