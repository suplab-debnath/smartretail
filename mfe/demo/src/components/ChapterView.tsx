import { useState, useEffect } from 'react'
import type { FlowDef, ServiceId, NodeState } from '../types'
import { useFlowRunner } from '../hooks/useFlowRunner'
import { useDbSnapshot } from '../hooks/useDbSnapshot'
import NarrativeHero from './NarrativeHero'
import StepProgressBar from './StepProgressBar'
import ArchitectureDiagram from './ArchitectureDiagram'
import TriggerButton from './TriggerButton'
import ChecklistPanel from './ChecklistPanel'
import BeforeAfterPanel from './BeforeAfterPanel'
import MfeRevealPanel from './MfeRevealPanel'

interface Props {
  flow:     FlowDef
  hasMatch: (pattern: string) => boolean
}

export default function ChapterView({ flow, hasMatch }: Props) {
  const [stepIdx, setStepIdx] = useState(0)
  const runner = useFlowRunner()

  // Reset step and runner when flow changes
  useEffect(() => {
    setStepIdx(0)
    runner.reset()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flow.id])

  const step = flow.steps[stepIdx]
  if (!step) return null

  // Build node states from active nodes
  const nodeStates: Partial<Record<ServiceId, NodeState>> = {}
  step.activeNodes?.forEach(id => { nodeStates[id] = 'active' })

  // Primary DB query for before/after (first in list)
  const primaryQuery = step.dbQueries?.[0]

  return (
    <div className="flex flex-col h-full overflow-y-auto">
      {/* Step progress */}
      <div className="shrink-0 px-4 pt-4">
        <StepProgressBar
          steps={flow.steps}
          activeStepId={step.id}
          onSelect={id => {
            const idx = flow.steps.findIndex(s => s.id === id)
            if (idx >= 0) { setStepIdx(idx); runner.reset() }
          }}
        />
      </div>

      {/* Narrative hero */}
      <div className="shrink-0 px-4">
        <NarrativeHero
          flow={flow}
          stepTitle={step.title}
          stepNarrative={step.narrative}
        />
      </div>

      {/* Architecture diagram */}
      <div className="shrink-0 px-4 mb-4">
        <div className="bg-slate-900 rounded-lg border border-slate-800 p-3">
          <ArchitectureDiagram
            nodeStates={nodeStates}
            activeEdges={step.flowEdges ?? []}
          />
        </div>
      </div>

      {/* Scrollable content below diagram */}
      <div className="flex-1 px-4 pb-4 space-y-0">

        {/* Trigger button */}
        {step.trigger && (
          <TriggerButton
            label={step.trigger.label}
            description={step.trigger.description}
            state={runner.state}
            onMouseEnter={primaryQuery ? undefined : undefined}
            onTrigger={() => {
              runner.trigger(flow.id, step.id, {
                ...step.trigger!,
                endpoint: step.trigger!.endpoint,
              })
            }}
          />
        )}

        {runner.error && (
          <div className="text-xs text-red-400 bg-red-950 border border-red-800 rounded p-2 mb-4">
            {runner.error}
          </div>
        )}

        {/* MFE reveal */}
        {step.mfeReveal && <MfeRevealPanel reveal={step.mfeReveal} />}

        {/* Checklist */}
        {step.checklist && step.checklist.length > 0 && (
          <ChecklistPanel items={step.checklist} hasMatch={hasMatch} />
        )}

        {/* DB state panels */}
        {step.dbQueries?.map(q => (
          <DbQueryPanel key={q.key} queryDef={q} triggerFired={runner.state !== 'idle'} autoStart={!step.trigger} />
        ))}

        {/* Step navigation */}
        <div className="flex justify-between pt-2">
          <button
            onClick={() => { setStepIdx(i => Math.max(0, i - 1)); runner.reset() }}
            disabled={stepIdx === 0}
            className="text-xs text-slate-600 hover:text-slate-400 disabled:opacity-30 transition-colors"
          >
            ← prev step
          </button>
          <button
            onClick={() => { setStepIdx(i => Math.min(flow.steps.length - 1, i + 1)); runner.reset() }}
            disabled={stepIdx === flow.steps.length - 1}
            className="text-xs text-blue-500 hover:text-blue-400 disabled:opacity-30 transition-colors"
          >
            next step →
          </button>
        </div>
      </div>
    </div>
  )
}

// Inline sub-component: handles a single DB query with before/after
function DbQueryPanel({
  queryDef,
  triggerFired,
  autoStart = false,
}: {
  queryDef:     NonNullable<FlowDef['steps'][0]['dbQueries']>[0]
  triggerFired: boolean
  autoStart?:   boolean
}) {
  const snapshot = useDbSnapshot(queryDef.endpoint, queryDef.params)

  // When trigger fires, start polling for "after" state
  useEffect(() => {
    if (triggerFired && !snapshot.after) {
      snapshot.startPolling()
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggerFired])

  // Capture "before" on mount; for trigger-less steps also start polling immediately
  useEffect(() => {
    snapshot.captureBefore()
    if (autoStart) snapshot.startPolling()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryDef.endpoint])

  return (
    <BeforeAfterPanel
      label={queryDef.label}
      before={snapshot.before}
      after={snapshot.after}
    />
  )
}
