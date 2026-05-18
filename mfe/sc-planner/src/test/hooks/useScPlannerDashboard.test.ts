import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useScPlannerDashboard } from '../../hooks/useScPlannerDashboard'
import type { ScPlannerDashboardResponse } from '../../types'

const mockResponse: ScPlannerDashboardResponse = {
  pendingApprovalCount: 3,
  activeAlertCount: 7,
  forecastAccuracy: { latestMape: 0.08, mapeThreshold: 0.15, lastRunAt: '2026-05-18T00:00:00Z', status: 'WITHIN_THRESHOLD' },
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('useScPlannerDashboard', () => {
  it('fetches dashboard on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useScPlannerDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
    expect(result.current.lastUpdated).toBeInstanceOf(Date)
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useScPlannerDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'server', status: 503 })
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useScPlannerDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'network' })
  })

  it('refresh() re-fetches data', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useScPlannerDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    await result.current.refresh()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('interval callback invokes fetch_ when not cancelled', async () => {
    const POLL_MS = 2 * 60 * 1000
    let capturedCallback: (() => void) | null = null
    const original = globalThis.setInterval.bind(globalThis)
    vi.spyOn(globalThis, 'setInterval').mockImplementation((fn: TimerHandler, delay?: number, ...args: unknown[]) => {
      if (delay === POLL_MS) capturedCallback = fn as () => void
      return original(fn as TimerHandler, delay, ...args)
    })
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useScPlannerDashboard())
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(capturedCallback).not.toBeNull()
    await vi.waitFor(async () => { await capturedCallback!() })
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2)
  })
})
