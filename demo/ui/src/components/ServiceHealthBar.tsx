import type { HealthStatus } from '../types'

const SERVICES: Array<{ key: keyof HealthStatus; label: string }> = [
  { key: 'sis', label: 'SIS' },
  { key: 'ims', label: 'IMS' },
  { key: 're',  label: 'RE'  },
  { key: 'ars', label: 'ARS' },
  { key: 'dfs', label: 'DFS' },
  { key: 'sup', label: 'SUP' },
]

interface Props {
  health: HealthStatus
}

export default function ServiceHealthBar({ health }: Props) {
  return (
    <div className="flex items-center gap-3 px-4 py-2 bg-slate-950 border-b border-slate-800">
      <span className="text-xs text-slate-600 uppercase tracking-wider font-semibold">Services</span>
      {SERVICES.map(({ key, label }) => {
        const ok = health[key] === 'ok'
        return (
          <div key={key} className="flex items-center gap-1">
            <span className={`w-1.5 h-1.5 rounded-full ${ok ? 'bg-emerald-500' : 'bg-red-500'}`} />
            <span className={`text-xs font-mono ${ok ? 'text-emerald-400' : 'text-red-500'}`}>{label}</span>
          </div>
        )
      })}
    </div>
  )
}
