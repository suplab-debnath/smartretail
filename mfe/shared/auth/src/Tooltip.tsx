import { useState } from 'react'
import { GLOSSARY } from './glossary.js'

interface Props {
  term?: string
  definition?: string
  children: React.ReactNode
}

export function Tooltip({ term, definition, children }: Props) {
  const [visible, setVisible] = useState(false)
  const text = definition ?? (term ? GLOSSARY[term] : undefined)

  if (!text) return <>{children}</>

  return (
    <span className="relative inline-flex items-center gap-1">
      {children}
      <span
        className="cursor-default select-none leading-none text-slate-400 hover:text-slate-600"
        onClick={e => e.stopPropagation()}
        onMouseEnter={() => setVisible(true)}
        onMouseLeave={() => setVisible(false)}
        aria-label={text}
      >
        ⓘ
      </span>
      {visible && (
        <span className="pointer-events-none absolute bottom-full left-1/2 z-50 mb-2 w-64 -translate-x-1/2 rounded-lg bg-gray-900 px-3 py-2 text-xs leading-relaxed text-gray-100 shadow-xl">
          {text}
          <span className="absolute left-1/2 top-full -translate-x-1/2 border-4 border-transparent border-t-gray-900" />
        </span>
      )}
    </span>
  )
}
