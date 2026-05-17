import type { FlowDef } from '../types'

const PERSONA_STYLE: Record<string, string> = {
  STORE_MANAGER: 'bg-purple-900 text-purple-200 border border-purple-700',
  SC_PLANNER:    'bg-teal-900   text-teal-200   border border-teal-700',
  EXECUTIVE:     'bg-rose-900   text-rose-200   border border-rose-700',
}

interface Props {
  flow:        FlowDef
  stepTitle:   string
  stepNarrative: string
}

export default function NarrativeHero({ flow, stepTitle, stepNarrative }: Props) {
  return (
    <div className={`bg-gradient-to-r ${flow.colorClass} rounded-lg p-4 mb-4`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <span className="text-xs font-mono text-white/50 uppercase tracking-widest">
              Chapter {flow.chapterNumber}
            </span>
            {flow.persona && (
              <span className={`text-xs px-2 py-0.5 rounded font-semibold ${PERSONA_STYLE[flow.persona] ?? 'bg-slate-700 text-slate-300'}`}>
                {flow.persona}
              </span>
            )}
          </div>
          <h2 className="text-xl font-bold text-white">{flow.title}</h2>
          <p className="text-sm text-white/60 mt-0.5">{flow.subtitle}</p>
        </div>
      </div>
      <div className="mt-3 pt-3 border-t border-white/10">
        <div className="text-xs font-semibold text-white/50 uppercase tracking-wider mb-1">{stepTitle}</div>
        <p className="text-sm text-white/80 leading-relaxed">{stepNarrative}</p>
      </div>
    </div>
  )
}
