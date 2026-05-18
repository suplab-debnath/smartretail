import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useSseEventLog } from '../../hooks/useSseEventLog'
import type { EventLogEntry } from '../../types'

const makeEntry = (id: string, message: string, raw = message): EventLogEntry => ({
  id, ts: '2026-05-18T10:00:00Z', flowId: 'flow1', stepId: 'step1',
  service: 'SIS', level: 'pass', message, raw,
})

class MockEventSource {
  static instance: MockEventSource | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(public url: string) { MockEventSource.instance = this }
  emit(data: unknown) { this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent) }
  emitRaw(text: string) { this.onmessage?.({ data: text } as MessageEvent) }
}

beforeEach(() => {
  vi.clearAllMocks()
  MockEventSource.instance = null
  vi.stubGlobal('EventSource', MockEventSource)
})

describe('useSseEventLog', () => {
  it('starts with empty entries', () => {
    const { result } = renderHook(() => useSseEventLog())
    expect(result.current.entries).toHaveLength(0)
  })

  it('opens EventSource to /api/events/stream', () => {
    renderHook(() => useSseEventLog())
    expect(MockEventSource.instance?.url).toBe('/api/events/stream')
  })

  it('adds entry when message arrives', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emit(makeEntry('e1', 'Stock alert raised')))
    expect(result.current.entries).toHaveLength(1)
    expect(result.current.entries[0].message).toBe('Stock alert raised')
  })

  it('accumulates multiple entries', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => {
      MockEventSource.instance!.emit(makeEntry('e1', 'msg1'))
      MockEventSource.instance!.emit(makeEntry('e2', 'msg2'))
    })
    expect(result.current.entries).toHaveLength(2)
  })

  it('ignores malformed JSON messages', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emitRaw('not-valid-json'))
    expect(result.current.entries).toHaveLength(0)
  })

  it('clear() empties entries', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emit(makeEntry('e1', 'msg')))
    act(() => result.current.clear())
    expect(result.current.entries).toHaveLength(0)
  })

  it('hasMatch returns true for matching raw content', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emit(makeEntry('e1', 'msg', 'STOCK_ALERT raised in DC-LONDON')))
    expect(result.current.hasMatch('STOCK_ALERT')).toBe(true)
  })

  it('hasMatch is case-insensitive', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emit(makeEntry('e1', 'msg', 'stock_alert raised')))
    expect(result.current.hasMatch('STOCK_ALERT')).toBe(true)
  })

  it('hasMatch returns false when no entry matches', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => MockEventSource.instance!.emit(makeEntry('e1', 'msg', 'unrelated event')))
    expect(result.current.hasMatch('STOCK_ALERT')).toBe(false)
  })

  it('hasMatch returns false when entries is empty', () => {
    const { result } = renderHook(() => useSseEventLog())
    expect(result.current.hasMatch('ANYTHING')).toBe(false)
  })

  it('closes EventSource on unmount', () => {
    const { unmount } = renderHook(() => useSseEventLog())
    unmount()
    expect(MockEventSource.instance?.close).toHaveBeenCalled()
  })

  it('truncates entries to 200', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => {
      for (let i = 0; i < 210; i++) {
        MockEventSource.instance!.emit(makeEntry(`e${i}`, `msg ${i}`))
      }
    })
    expect(result.current.entries).toHaveLength(200)
  })

  it('keeps the most recent entries when truncating', () => {
    const { result } = renderHook(() => useSseEventLog())
    act(() => {
      for (let i = 0; i < 210; i++) {
        MockEventSource.instance!.emit(makeEntry(`e${i}`, `msg ${i}`))
      }
    })
    expect(result.current.entries[199].message).toBe('msg 209')
  })
})
