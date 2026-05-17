import { useState } from 'react'
import { FLOWS } from './flows'
import type { FlowStatus } from './types'
import { useSseEventLog } from './hooks/useSseEventLog'
import { useServiceHealth } from './hooks/useServiceHealth'
import MissionControlLayout from './components/MissionControlLayout'
import FlowRail from './components/FlowRail'
import ChapterView from './components/ChapterView'
import EventLog from './components/EventLog'
import ServiceHealthBar from './components/ServiceHealthBar'

export default function App() {
  const [activeFlowId, setActiveFlowId] = useState(FLOWS[0].id)
  const { entries, clear, hasMatch } = useSseEventLog()
  const health = useServiceHealth()

  // Derive flow statuses from log entries — a flow is 'complete' if its smoke verify passed
  const statuses: Record<string, FlowStatus> = Object.fromEntries(
    FLOWS.map(f => {
      const flowEntries = entries.filter(e => e.flowId === f.id)
      if (flowEntries.length === 0) return [f.id, 'not_started']
      const hasFail = flowEntries.some(e => e.level === 'fail')
      const hasExit = flowEntries.some(e => e.message.includes('exited with code 0'))
      if (hasExit && !hasFail) return [f.id, 'complete']
      if (hasFail) return [f.id, 'failed']
      return [f.id, 'in_progress']
    })
  )

  const activeFlow = FLOWS.find(f => f.id === activeFlowId) ?? FLOWS[0]

  return (
    <MissionControlLayout
      topBar={<ServiceHealthBar health={health} />}
      rail={
        <FlowRail
          flows={FLOWS}
          activeId={activeFlowId}
          statuses={statuses}
          onSelect={setActiveFlowId}
        />
      }
      center={
        <ChapterView
          key={activeFlowId}
          flow={activeFlow}
          hasMatch={hasMatch}
        />
      }
      logPane={
        <EventLog entries={entries} onClear={clear} />
      }
    />
  )
}
