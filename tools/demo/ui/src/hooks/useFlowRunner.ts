import { useState, useCallback } from 'react'
import type { TriggerDef } from '../types'

export type RunState = 'idle' | 'running' | 'done' | 'failed'

export function useFlowRunner() {
  const [state, setState] = useState<RunState>('idle')
  const [error, setError] = useState<string | null>(null)

  const trigger = useCallback(async (flowId: string, _stepId: string, def: TriggerDef) => {
    setState('running')
    setError(null)
    try {
      const res = await fetch(def.endpoint, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(def.body ?? {}),
      })
      if (res.status === 409) {
        setError(`Flow ${flowId} is already running`)
        setState('failed')
        return
      }
      if (!res.ok) {
        const text = await res.text()
        setError(text)
        setState('failed')
        return
      }
      // Script output streams via SSE — mark done immediately after HTTP 200
      setState('done')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setState('failed')
    }
  }, [])

  const reset = useCallback(() => { setState('idle'); setError(null) }, [])

  return { state, error, trigger, reset }
}
