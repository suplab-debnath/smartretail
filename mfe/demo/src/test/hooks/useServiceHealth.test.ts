import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useServiceHealth } from '../../hooks/useServiceHealth'

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.restoreAllMocks())

const allOk = { sis: 'ok', ims: 'ok', re: 'ok', ars: 'ok', dfs: 'ok', sup: 'ok' }

describe('useServiceHealth', () => {
  it('starts with all services down', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    const { result } = renderHook(() => useServiceHealth())
    expect(result.current.sis).toBe('down')
    expect(result.current.ims).toBe('down')
    expect(result.current.re).toBe('down')
  })

  it('updates to ok on successful fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => allOk }))
    const { result } = renderHook(() => useServiceHealth())
    await waitFor(() => expect(result.current.sis).toBe('ok'))
    expect(result.current.ars).toBe('ok')
  })

  it('stays down when response is not ok', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useServiceHealth())
    await new Promise(r => setTimeout(r, 30))
    expect(result.current.sis).toBe('down')
  })

  it('stays down when fetch throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))
    const { result } = renderHook(() => useServiceHealth())
    await new Promise(r => setTimeout(r, 30))
    expect(result.current.sis).toBe('down')
  })

  it('registers a polling interval', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    let capturedCallback: (() => void) | null = null
    const original = global.setInterval.bind(global)
    const spy = vi.spyOn(global, 'setInterval')
    spy.mockImplementation((fn, delay, ...args) => {
      if (delay === 30_000) capturedCallback = fn as () => void
      return original(fn as TimerHandler, delay, ...args)
    })
    renderHook(() => useServiceHealth())
    await new Promise(r => setTimeout(r, 30))
    expect(capturedCallback).not.toBeNull()
  })

  it('accepts custom interval parameter', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    const original = global.setInterval.bind(global)
    const intervalSpy = vi.spyOn(global, 'setInterval')
    intervalSpy.mockImplementation((fn, delay, ...args) => original(fn as TimerHandler, delay, ...args))
    renderHook(() => useServiceHealth(5000))
    await new Promise(r => setTimeout(r, 30))
    const calls = intervalSpy.mock.calls.filter(c => c[1] === 5000)
    expect(calls.length).toBeGreaterThan(0)
  })
})
