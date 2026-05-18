import { renderHook, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useStoreManagerDashboard } from '../../hooks/useStoreManagerDashboard'
import type { StoreManagerDashboardResponse } from '../../types'

const mockResponse: StoreManagerDashboardResponse = {
  dcId: 'DC-LONDON',
  alertKpi: { criticalCount: 0, highCount: 1, mediumCount: 2, totalActive: 3 },
  totalOnHandUnits: 8000,
  pendingReplenishmentCount: 2,
  forecastCoveragePct: 90.0,
  alerts: [],
  alertsPage: 0,
  alertsTotalPages: 0,
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useStoreManagerDashboard', () => {
  it('starts in loading state with no data', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    expect(result.current.loading).toBe(true)
    expect(result.current.data).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('populates data after a successful fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
  })

  it('sets error on non-ok HTTP response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'server', status: 503 })
    expect(result.current.data).toBeNull()
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'network' })
    expect(result.current.data).toBeNull()
  })

  it('handles unknown thrown value as network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue('oops'))
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'network' })
  })

  it('builds the URL with dcId, page, and size', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useStoreManagerDashboard('DC-MANCHESTER', 2, 20))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const url: string = fetchMock.mock.calls[0][0]
    expect(url).toContain('dcId=DC-MANCHESTER')
    expect(url).toContain('page=2')
    expect(url).toContain('size=20')
  })

  it('re-fetches when dcId changes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { rerender } = renderHook(
      ({ dcId }: { dcId: string }) => useStoreManagerDashboard(dcId, 0),
      { initialProps: { dcId: 'DC-LONDON' } }
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    rerender({ dcId: 'DC-BIRMINGHAM' })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const secondUrl: string = fetchMock.mock.calls[1][0]
    expect(secondUrl).toContain('dcId=DC-BIRMINGHAM')
  })

  it('refresh() triggers an additional fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await result.current.refresh()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('interval callback invokes fetchDashboard when not cancelled', async () => {
    const POLL_MS = 60_000
    let capturedCallback: (() => void) | null = null
    const originalSetInterval = globalThis.setInterval.bind(globalThis)
    vi.spyOn(globalThis, 'setInterval').mockImplementation(
      (fn: TimerHandler, delay?: number, ...args: unknown[]) => {
        if (delay === POLL_MS) capturedCallback = fn as () => void
        return originalSetInterval(fn as TimerHandler, delay, ...args)
      }
    )
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)

    renderHook(() => useStoreManagerDashboard('DC-LONDON', 0))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(capturedCallback).not.toBeNull()

    await act(async () => { capturedCallback!() })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
