import { useEffect, useRef } from 'react'
import type { EventLogEntry } from '../types'
import EventLogEntryRow from './EventLogEntry'

interface Props {
  entries: EventLogEntry[]
  onClear: () => void
}

export default function EventLog({ entries, onClear }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [entries.length])

  return (
    <div className="flex flex-col h-full bg-slate-950 border-l border-slate-800">
      {/* header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-slate-800 shrink-0">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
          <span className="text-xs font-semibold text-slate-300 uppercase tracking-wider">Event Log</span>
          <span className="text-xs text-slate-600">{entries.length}</span>
        </div>
        <button
          onClick={onClear}
          className="text-xs text-slate-600 hover:text-slate-400 transition-colors"
        >
          clear
        </button>
      </div>

      {/* entries */}
      <div className="flex-1 overflow-y-auto log-scroll px-3 py-2">
        {entries.length === 0 ? (
          <p className="text-xs text-slate-700 mt-4 text-center">Waiting for events…</p>
        ) : (
          entries.map(e => <EventLogEntryRow key={e.id} entry={e} />)
        )}
        <div ref={bottomRef} />
      </div>
    </div>
  )
}
