import { useEffect, useRef, useState, useCallback } from 'react'
import type { EventLogEntry } from '../types'

const MAX_ENTRIES = 200

export function useSseEventLog() {
  const [entries, setEntries] = useState<EventLogEntry[]>([])
  const esRef = useRef<EventSource | null>(null)

  useEffect(() => {
    const es = new EventSource('/api/events/stream')
    esRef.current = es

    es.onmessage = (e) => {
      try {
        const entry = JSON.parse(e.data) as EventLogEntry
        setEntries(prev => {
          const next = [...prev, entry]
          return next.length > MAX_ENTRIES ? next.slice(next.length - MAX_ENTRIES) : next
        })
      } catch { /* malformed event */ }
    }

    es.onerror = () => {
      // Browser auto-reconnects on error; no action needed
    }

    return () => { es.close() }
  }, [])

  const clear = useCallback(() => setEntries([]), [])

  /** Returns true if any entry message contains the given pattern */
  const hasMatch = useCallback((pattern: string) =>
    entries.some(e => e.raw.toLowerCase().includes(pattern.toLowerCase())),
    [entries]
  )

  return { entries, clear, hasMatch }
}
