import type { ChecklistItem } from '../types'

interface Props {
  items:    ChecklistItem[]
  hasMatch: (pattern: string) => boolean
}

export default function ChecklistPanel({ items, hasMatch }: Props) {
  if (items.length === 0) return null

  const checked = items.filter(i => hasMatch(i.matchPattern)).length

  return (
    <div className="bg-slate-900 rounded-lg border border-slate-800 p-3 mb-4">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Evidence checklist</span>
        <span className="text-xs text-slate-600">{checked}/{items.length}</span>
      </div>
      <div className="space-y-1">
        {items.map(item => {
          const done = hasMatch(item.matchPattern)
          return (
            <div key={item.id} className="flex items-center gap-2">
              <span className={`w-4 h-4 rounded flex items-center justify-center text-xs shrink-0
                ${done ? 'bg-emerald-900 text-emerald-400' : 'bg-slate-800 text-slate-600'}`}>
                {done ? '✓' : '○'}
              </span>
              <span className={`text-xs ${done ? 'text-emerald-400' : 'text-slate-600'}`}>
                {item.text}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
