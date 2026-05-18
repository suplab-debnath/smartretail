import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useSupplierPerformance } from '../../hooks/useSupplierPerformance'
import type { SupplierPerformanceDashboardResponse } from '../../types'

const mockResponse: SupplierPerformanceDashboardResponse = {
  suppliers: [
    { supplierId: 'sup-1', supplierName: 'Acme', onTimeDeliveryRate: 92.5, poAcknowledgementSlaCompliance: 88.0, openExceptions: 1, avgLeadTimeVarianceDays: 0.5, totalPoCount: 120, totalPoValue: 480000 },
  ],
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('useSupplierPerformance', () => {
  it('fetches supplier performance on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useSupplierPerformance())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }))
    const { result } = renderHook(() => useSupplierPerformance())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('HTTP 500')
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
    const { result } = renderHook(() => useSupplierPerformance())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('Network error')
  })

  it('calls the correct endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useSupplierPerformance())
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/dashboard/supplier-performance')
  })
})
