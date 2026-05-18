import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useExecutiveDashboard } from '../../hooks/useExecutiveDashboard'
import type { ExecutiveDashboardResponse } from '../../types'

const mockData: ExecutiveDashboardResponse = {
  kpis: {
    forecastAccuracy: { latestMape: 0.08, trend: 'IMPROVING', history: [] },
    stockoutFrequency: { last30Days: 3, trend: 'DECREASING', history: [] },
    replenishmentCycleTime: { averageDays: 4.2, trend: 'STABLE', history: [] },
    onTimeDelivery: { rate: 0.92, trend: 'STABLE' },
    supplierPerformance: [],
  },
  dataFreshness: '2026-05-18T00:00:00Z',
}

beforeEach(() => vi.clearAllMocks())

describe('useExecutiveDashboard', () => {
  it('starts in loading state', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockData,
    }))
    const { result } = renderHook(() => useExecutiveDashboard())
    expect(result.current.loading).toBe(true)
    expect(result.current.data).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('sets data on successful fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockData,
    }))
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockData)
    expect(result.current.error).toBeNull()
    expect(result.current.lastUpdated).not.toBeNull()
  })

  it('sets error on HTTP error response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('HTTP 503')
    expect(result.current.data).toBeNull()
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('Network error')
  })

  it('sets unknown error message for non-Error throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue('string error'))
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('Unknown error')
  })

  it('fetches from /v1/dashboard/executive with correct header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockData })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(fetchMock).toHaveBeenCalledWith('/v1/dashboard/executive', {
      headers: { 'X-Dev-Role': 'EXECUTIVE' },
    })
  })

  it('refresh() re-fetches data', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockData })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    await result.current.refresh()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('registers a polling interval', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockData })
    vi.stubGlobal('fetch', fetchMock)
    let capturedCallback: (() => void) | null = null
    const original = global.setInterval.bind(global)
    vi.spyOn(global, 'setInterval').mockImplementation((fn, delay, ...args) => {
      if (delay === 5 * 60 * 1000) capturedCallback = fn as () => void
      return original(fn as TimerHandler, delay, ...args)
    })
    const { result } = renderHook(() => useExecutiveDashboard())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(capturedCallback).not.toBeNull()
  })
})
