import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useDbSnapshot } from '../../hooks/useDbSnapshot'

const mockRows = [{ id: '1', status: 'PENDING' }]

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.restoreAllMocks())

describe('useDbSnapshot', () => {
  it('starts with null before, after, and polling false', () => {
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    expect(result.current.before).toBeNull()
    expect(result.current.after).toBeNull()
    expect(result.current.polling).toBe(false)
  })

  it('captureBefore sets before snapshot on success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ rows: mockRows }),
    }))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    await act(() => result.current.captureBefore())
    expect(result.current.before).not.toBeNull()
    expect(result.current.before!.rows).toEqual(mockRows)
    expect(result.current.before!.timestamp).toBeTruthy()
  })

  it('captureBefore ignores errors silently', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    await act(() => result.current.captureBefore())
    expect(result.current.before).toBeNull()
  })

  it('captureBefore uses missing rows as empty array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, json: async () => ({}),
    }))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    await act(() => result.current.captureBefore())
    expect(result.current.before!.rows).toEqual([])
  })

  it('startPolling sets polling to true', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ rows: [] }) }))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    act(() => result.current.startPolling())
    expect(result.current.polling).toBe(true)
  })

  it('startPolling does not start a second interval if already polling', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ rows: [] }) }))
    const original = globalThis.setInterval.bind(globalThis)
    const intervalSpy = vi.spyOn(globalThis, 'setInterval')
    intervalSpy.mockImplementation((fn: TimerHandler, delay?: number, ...args: unknown[]) => original(fn, delay, ...args))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    act(() => { result.current.startPolling(); result.current.startPolling() })
    const pollingCalls = intervalSpy.mock.calls.filter((c: unknown[]) => c[1] === 2000)
    expect(pollingCalls.length).toBe(1)
  })

  it('reset clears before, after and polling', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ rows: mockRows }),
    }))
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    await act(() => result.current.captureBefore())
    act(() => result.current.startPolling())
    act(() => result.current.reset())
    expect(result.current.before).toBeNull()
    expect(result.current.after).toBeNull()
    expect(result.current.polling).toBe(false)
  })

  it('builds URL with query params', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ rows: [] }) })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/orders', { status: 'ACTIVE', dcId: 'DC-LONDON' }))
    await act(() => result.current.captureBefore())
    const calledUrl: string = fetchMock.mock.calls[0][0]
    expect(calledUrl).toContain('status=ACTIVE')
    expect(calledUrl).toContain('dcId=DC-LONDON')
  })

  it('polling callback updates after state', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ rows: mockRows }) })
    vi.stubGlobal('fetch', fetchMock)
    let capturedFn: (() => void) | null = null
    const original = globalThis.setInterval.bind(globalThis)
    const spy = vi.spyOn(globalThis, 'setInterval')
    spy.mockImplementation((fn: TimerHandler, delay?: number, ...args: unknown[]) => {
      if (delay === 2000) capturedFn = fn as () => void
      return original(fn, delay, ...args)
    })
    const { result } = renderHook(() => useDbSnapshot('/api/dbstate/alerts'))
    act(() => result.current.startPolling())
    expect(capturedFn).not.toBeNull()
    await act(async () => { capturedFn!() })
    expect(result.current.after).not.toBeNull()
  })
})
