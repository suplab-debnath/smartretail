import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useExceptionQueue } from '../../hooks/useExceptionQueue'
import type { StockAlertListResponse } from '../../types'

const mockResponse: StockAlertListResponse = {
  alerts: [
    { alertId: 'a1', positionId: 'p1', skuId: 'SKU-001', dcId: 'DC-LONDON', alertType: 'LOW_STOCK', severity: 'HIGH', status: 'ACTIVE', actualValue: 30, thresholdValue: 100, raisedAt: '2026-05-18T00:00:00Z' },
  ],
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('useExceptionQueue', () => {
  it('starts with loading=false and no data', () => {
    const { result } = renderHook(() => useExceptionQueue())
    expect(result.current.loading).toBe(false)
    expect(result.current.data).toBeNull()
  })

  it('fetches data on refetch()', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useExceptionQueue())
    await act(async () => { await result.current.refetch() })
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useExceptionQueue())
    await act(async () => { await result.current.refetch() })
    expect(result.current.error).toMatchObject({ kind: 'server', status: 503 })
    expect(result.current.data).toBeNull()
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useExceptionQueue())
    await act(async () => { await result.current.refetch() })
    expect(result.current.error).toMatchObject({ kind: 'network' })
  })

  it('appends dcId to query when provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useExceptionQueue('DC-LONDON'))
    await act(async () => { await result.current.refetch() })
    expect(fetchMock.mock.calls[0][0]).toContain('dcId=DC-LONDON')
  })

  it('sets loading=true during fetch then false after', async () => {
    let resolve!: (v: unknown) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise(r => { resolve = r })))
    const { result } = renderHook(() => useExceptionQueue())
    act(() => { result.current.refetch() })
    expect(result.current.loading).toBe(true)
    await act(async () => { resolve({ ok: true, json: async () => mockResponse }) })
    expect(result.current.loading).toBe(false)
  })
})
