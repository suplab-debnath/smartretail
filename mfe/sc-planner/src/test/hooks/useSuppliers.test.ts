import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useSuppliers } from '../../hooks/useSuppliers'
import type { SupplierListResponse } from '../../types'

const mockResponse: SupplierListResponse = {
  suppliers: [
    { supplierId: 'sup-1', supplierName: 'Acme Beverages' },
    { supplierId: 'sup-2', supplierName: 'Fresh Dairy Co' },
  ],
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

  it('calls /v1/supplier/suppliers with X-Dev-Role header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useSuppliers())
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/v1/supplier/suppliers')
    expect((init?.headers as Record<string, string>)?.['X-Dev-Role']).toBe('SC_PLANNER')
  })

  it('returns empty map on network failure (swallows error)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useSuppliers())
    // map stays empty; no throw
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
