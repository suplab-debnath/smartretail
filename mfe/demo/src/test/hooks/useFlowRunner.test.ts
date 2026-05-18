import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useFlowRunner } from '../../hooks/useFlowRunner'

const triggerDef = {
  label: 'Trigger Flow',
  endpoint: '/api/trigger/flow1/pos-event',
  body: { skuId: 'SKU-001' },
  description: 'Sends a POS event',
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.restoreAllMocks())

describe('useFlowRunner', () => {
  it('starts idle with no error', () => {
    const { result } = renderHook(() => useFlowRunner())
    expect(result.current.state).toBe('idle')
    expect(result.current.error).toBeNull()
  })

  it('sets state to done on successful trigger', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(result.current.state).toBe('done')
    expect(result.current.error).toBeNull()
  })

  it('sets failed with 409 message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409 }))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(result.current.state).toBe('failed')
    expect(result.current.error).toBe('Flow flow1 is already running')
  })

  it('sets failed with response body text for non-ok', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, text: async () => 'Internal error' }))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(result.current.state).toBe('failed')
    expect(result.current.error).toBe('Internal error')
  })

  it('sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(result.current.state).toBe('failed')
    expect(result.current.error).toBe('Network error')
  })

  it('handles non-Error throw', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue('string error'))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(result.current.error).toBe('string error')
  })

  it('reset() returns to idle', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }))
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    act(() => result.current.reset())
    expect(result.current.state).toBe('idle')
    expect(result.current.error).toBeNull()
  })

  it('posts to correct endpoint with body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useFlowRunner())
    await act(async () => result.current.trigger('flow1', 'step1', triggerDef))
    expect(fetchMock).toHaveBeenCalledWith('/api/trigger/flow1/pos-event', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ skuId: 'SKU-001' }),
    })
  })

  it('uses empty object body when body is not provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useFlowRunner())
    await act(() => result.current.trigger('flow1', 'step1', { ...triggerDef, body: undefined }))
    const call = fetchMock.mock.calls[0][1]
    expect(call.body).toBe('{}')
  })
})
