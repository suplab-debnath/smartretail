import type { FlowDef, FlowStatus } from '../types'

const STATUS_DOT: Record<FlowStatus, string> = {
  not_started: 'bg-slate-600',
  in_progress: 'bg-amber-400 animate-pulse',
  complete:    'bg-emerald-500',
  failed:      'bg-red-500',
}

interface Props {
  flows:       FlowDef[]
  activeId:    string
  statuses:    Record<string, FlowStatus>
  onSelect:    (id: string) => void
}

export default function FlowRail({ flows, activeId, statuses, onSelect }: Props) {
  return (
    <nav className="flex flex-col h-full bg-slate-900 border-r border-slate-800 w-56 shrink-0">
      {/* header */}
      <div className="px-4 py-4 border-b border-slate-800">
        <div className="text-xs font-bold text-slate-400 uppercase tracking-widest">SmartRetail</div>
        <div className="text-xs text-slate-600 mt-0.5">Demo Control Center</div>
      </div>

      {/* chapters */}
      <div className="flex-1 overflow-y-auto py-2">
        {flows.map(flow => {
          const status  = statuses[flow.id] ?? 'not_started'
          const isActive = flow.id === activeId
          return (
            <button
              key={flow.id}
              onClick={() => onSelect(flow.id)}
              className={`w-full text-left px-4 py-3 flex items-start gap-3 transition-colors
                ${isActive ? 'bg-slate-800 border-l-2 border-blue-500' : 'border-l-2 border-transparent hover:bg-slate-800/50'}`}
            >
              <div className="flex flex-col items-center gap-1 shrink-0 mt-0.5">
                <span className="text-xs text-slate-600 font-mono">
                  {String(flow.chapterNumber).padStart(2, '0')}
                </span>
                <span className={`w-2 h-2 rounded-full ${STATUS_DOT[status]}`} />
              </div>
              <div>
                <div className={`text-sm font-medium leading-tight ${isActive ? 'text-white' : 'text-slate-400'}`}>
                  {flow.title}
                </div>
                <div className="text-xs text-slate-600 mt-0.5 leading-tight line-clamp-2">
                  {flow.subtitle}
                </div>
              </div>
            </button>
          )
        })}
      </div>

      {/* legend */}
      <div className="px-4 py-3 border-t border-slate-800 space-y-1">
        {(['not_started', 'in_progress', 'complete', 'failed'] as FlowStatus[]).map(s => (
          <div key={s} className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${STATUS_DOT[s]}`} />
            <span className="text-xs text-slate-600">{s.replace('_', ' ')}</span>
          </div>
        ))}
      </div>
    </nav>
  )
}
