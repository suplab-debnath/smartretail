import type { EventLogEntry } from '../types'

const LEVEL_STYLE: Record<string, string> = {
  pass:  'text-emerald-400',
  fail:  'text-red-400',
  warn:  'text-amber-400',
  event: 'text-cyan-400',
  info:  'text-slate-400',
}

const SERVICE_STYLE: Record<string, string> = {
  sis:         'bg-blue-900 text-blue-300',
  ims:         'bg-purple-900 text-purple-300',
  re:          'bg-amber-900 text-amber-300',
  ars:         'bg-rose-900 text-rose-300',
  dfs:         'bg-teal-900 text-teal-300',
  sup:         'bg-green-900 text-green-300',
  firehose:    'bg-orange-900 text-orange-300',
  eventbridge: 'bg-indigo-900 text-indigo-300',
}

const LEVEL_PREFIX: Record<string, string> = {
  pass: '✓',
  fail: '✗',
  warn: '!',
  event: '→',
  info: '·',
}

interface Props {
  entry: EventLogEntry
}

export default function EventLogEntryRow({ entry }: Props) {
  const time = entry.ts.slice(11, 19) // HH:MM:SS
  const svcStyle = SERVICE_STYLE[entry.service.toLowerCase()] ?? 'bg-slate-800 text-slate-400'
  const levelStyle = LEVEL_STYLE[entry.level] ?? 'text-slate-400'
  const prefix = LEVEL_PREFIX[entry.level] ?? '·'

  return (
    <div className="flex items-start gap-2 py-0.5 text-xs font-mono leading-5 group">
      <span className="text-slate-600 shrink-0 w-16">{time}</span>
      <span className={`shrink-0 px-1.5 rounded text-[10px] font-semibold uppercase ${svcStyle}`}>
        {entry.service.slice(0, 6)}
      </span>
      <span className={`shrink-0 w-3 ${levelStyle}`}>{prefix}</span>
      <span className={`break-all ${levelStyle}`}>{entry.message}</span>
    </div>
  )
}
