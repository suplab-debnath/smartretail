import type { StepDef } from '../types'

interface Props {
  steps:       StepDef[]
  activeStepId: string
  onSelect:    (id: string) => void
}

export default function StepProgressBar({ steps, activeStepId, onSelect }: Props) {
  const activeIdx = steps.findIndex(s => s.id === activeStepId)

  return (
    <div className="flex items-center gap-0 mb-4 overflow-x-auto pb-1">
      {steps.map((step, i) => {
        const done    = i < activeIdx
        const active  = i === activeIdx
        const pending = i > activeIdx
        return (
          <div key={step.id} className="flex items-center">
            <button
              onClick={() => onSelect(step.id)}
              title={step.title}
              className={`flex items-center justify-center w-7 h-7 rounded-full text-xs font-bold transition-all shrink-0
                ${done   ? 'bg-emerald-600 text-white'                           : ''}
                ${active ? 'bg-blue-600 text-white ring-2 ring-blue-400 ring-offset-1 ring-offset-slate-900' : ''}
                ${pending ? 'bg-slate-700 text-slate-500 hover:bg-slate-600'     : ''}`}
            >
              {done ? '✓' : i + 1}
            </button>
            {i < steps.length - 1 && (
              <div className={`h-0.5 w-8 shrink-0 ${i < activeIdx ? 'bg-emerald-600' : 'bg-slate-700'}`} />
            )}
          </div>
        )
      })}
      <span className="ml-3 text-xs text-slate-500 shrink-0">
        {steps[activeIdx]?.title}
      </span>
    </div>
  )
}
