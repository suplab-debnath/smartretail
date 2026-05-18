import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useForecast } from '../../hooks/useForecast'
import type { ForecastDataResponse } from '../../types'

const mockResponse: ForecastDataResponse = {
  skuId: 'SKU-BEV-001', dcId: 'DC-LONDON', horizonDays: 30, latestMape: 0.08,
  bands: [{ forecastDate: '2026-05-18', p10: 80, p50: 100, p90: 120, actualUnits: 95 }],
  dataFreshness: '2026-05-18T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('useForecast', () => {
  it('fetches forecast data on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useForecast('SKU-BEV-001', 'DC-LONDON', 30))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
  })

  it('does not fetch when skuId is empty', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useForecast('', 'DC-LONDON', 30))
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not fetch when dcId is empty', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useForecast('SKU-001', '', 30))
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }))
    const { result } = renderHook(() => useForecast('SKU-BEV-001', 'DC-LONDON', 7))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('HTTP 404')
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('timeout')))
    const { result } = renderHook(() => useForecast('SKU-001', 'DC-LONDON', 14))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('timeout')
  })

  it('includes horizonDays in URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useForecast('SKU-001', 'DC-LONDON', 14))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toContain('horizonDays=14')
  })

  it('re-fetches when horizonDays changes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { rerender } = renderHook(
      ({ h }: { h: 7 | 14 | 30 }) => useForecast('SKU-001', 'DC-LONDON', h),
      { initialProps: { h: 7 as const } }
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    rerender({ h: 30 })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })
})
