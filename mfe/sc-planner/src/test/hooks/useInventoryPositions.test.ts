import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useInventoryPositions } from '../../hooks/useInventoryPositions'
import type { InventoryPositionListResponse } from '../../types'

const mockPos = { positionId: 'p1', skuId: 'SKU-001', dcId: 'DC-LONDON', onHand: 500, inTransit: 100, reserved: 50, reorderPoint: 200, safetyStock: 80, version: 1, lastUpdatedAt: '2026-05-18T00:00:00Z' }
const mockResponse: InventoryPositionListResponse = { positions: [mockPos], dataFreshness: '2026-05-18T00:00:00Z' }

afterEach(() => vi.restoreAllMocks())

describe('useInventoryPositions', () => {
  it('fetches positions on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => useInventoryPositions('DC-LONDON'))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual(mockResponse)
    expect(result.current.error).toBeNull()
  })

  it('fetches without dcId filter when undefined', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useInventoryPositions(undefined))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/inventory/positions')
  })

  it('appends dcId query param when provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    renderHook(() => useInventoryPositions('DC-MANCHESTER'))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toContain('dcId=DC-MANCHESTER')
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }))
    const { result } = renderHook(() => useInventoryPositions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('HTTP 500')
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { result } = renderHook(() => useInventoryPositions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('offline')
  })

  it('re-fetches when dcId changes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse })
    vi.stubGlobal('fetch', fetchMock)
    const { rerender } = renderHook(
      ({ dc }: { dc: string }) => useInventoryPositions(dc),
      { initialProps: { dc: 'DC-LONDON' } }
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    rerender({ dc: 'DC-BIRMINGHAM' })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })
})
