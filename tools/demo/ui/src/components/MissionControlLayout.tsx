import type { ReactNode } from 'react'

interface Props {
  rail:    ReactNode
  center:  ReactNode
  logPane: ReactNode
  topBar?: ReactNode
}

export default function MissionControlLayout({ rail, center, logPane, topBar }: Props) {
  return (
    <div className="flex flex-col h-screen bg-slate-950 text-slate-100 overflow-hidden">
      {topBar && <div className="shrink-0">{topBar}</div>}
      <div className="flex flex-1 overflow-hidden">
        {/* Left rail */}
        <div className="shrink-0 overflow-hidden">{rail}</div>

        {/* Center canvas */}
        <div className="flex-1 overflow-hidden">
          {center}
        </div>

        {/* Right event log */}
        <div className="w-80 shrink-0 overflow-hidden">{logPane}</div>
      </div>
    </div>
  )
}
