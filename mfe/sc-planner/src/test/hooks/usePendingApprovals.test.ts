import { renderHook, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { usePendingApprovals } from '../../hooks/usePendingApprovals'
import type { PurchaseOrderListResponse } from '../../types'

const mockPo = { poId: 'po-001', supplierId: 'sup-1', skuId: 'SKU-001', dcId: 'DC-LONDON', quantity: 500, totalValue: 2500, workflowStatus: 'PENDING_APPROVAL', version: 1, createdAt: '2026-05-18T00:00:00Z' }
const mockResponse: PurchaseOrderListResponse = { orders: [mockPo], dataFreshness: '2026-05-18T00:00:00Z' }

afterEach(() => vi.restoreAllMocks())

describe('usePendingApprovals', () => {
  it('fetches orders on mount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => usePendingApprovals())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.orders).toEqual([mockPo])
    expect(result.current.error).toBeNull()
  })

  it('sets error on non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => usePendingApprovals())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'server', status: 503 })
    expect(result.current.orders).toEqual([])
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('timeout')))
    const { result } = renderHook(() => usePendingApprovals())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toMatchObject({ kind: 'network' })
  })

  it('removeOrder removes the specified PO from state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => mockResponse }))
    const { result } = renderHook(() => usePendingApprovals())
    await waitFor(() => expect(result.current.orders).toHaveLength(1))
    act(() => result.current.removeOrder('po-001'))
    expect(result.current.orders).toHaveLength(0)
  })

  it('removeOrder does not affect other POs', async () => {
    const two = { orders: [mockPo, { ...mockPo, poId: 'po-002' }], dataFreshness: '2026-05-18T00:00:00Z' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => two }))
    const { result } = renderHook(() => usePendingApprovals())
    await waitFor(() => expect(result.current.orders).toHaveLength(2))
    act(() => result.current.removeOrder('po-001'))
    expect(result.current.orders).toHaveLength(1)
    expect(result.current.orders[0].poId).toBe('po-002')
  })
})
