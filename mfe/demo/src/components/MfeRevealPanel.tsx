import { useState } from 'react'
import type { MfeReveal } from '../types'

const MFE_COLORS: Record<string, string> = {
  'store-manager': 'border-purple-700',
  'sc-planner':    'border-teal-700',
  'executive':     'border-rose-700',
}

interface Props {
  reveal: MfeReveal
}

export default function MfeRevealPanel({ reveal }: Props) {
  const [expanded, setExpanded] = useState(false)
  const url = `http://localhost:${reveal.localPort}${reveal.path}`
  const borderColor = MFE_COLORS[reveal.mfe] ?? 'border-slate-700'

  return (
    <div className={`rounded-lg border-2 ${borderColor} overflow-hidden mb-4 animate-fade-in`}>
      {/* toolbar */}
      <div className="flex items-center justify-between px-3 py-2 bg-slate-900">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-emerald-500" />
          <span className="text-xs font-semibold text-slate-300">{reveal.label}</span>
          <span className="text-xs text-slate-600 font-mono">:{reveal.localPort}</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setExpanded(e => !e)}
            className="text-xs text-slate-500 hover:text-slate-300 transition-colors"
          >
            {expanded ? '⊟ collapse' : '⊞ expand'}
          </button>
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-xs text-blue-500 hover:text-blue-400 transition-colors"
          >
            ↗ new tab
          </a>
        </div>
      </div>

      {/* iframe — only mounted when rendered (lazy) */}
      <div style={{ height: expanded ? 600 : 380 }} className="transition-all duration-300">
        <iframe
          src={url}
          title={reveal.label}
          className="w-full h-full border-0"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
        />
      </div>
    </div>
  )
}
