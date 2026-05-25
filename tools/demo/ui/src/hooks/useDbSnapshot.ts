import { useState, useCallback, useRef } from 'react'
import type { DbSnapshot } from '../types'

async function fetchSnapshot(endpoint: string, params?: Record<string, string>): Promise<DbSnapshot> {
  const url = new URL(endpoint, window.location.origin)
  if (params) Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v))
  const res = await fetch(url.toString())
  const json = await res.json()
  return { rows: json.rows ?? [], timestamp: new Date().toISOString() }
}

export function useDbSnapshot(endpoint: string, params?: Record<string, string>) {
  const [before, setBefore]   = useState<DbSnapshot | null>(null)
  const [after,  setAfter]    = useState<DbSnapshot | null>(null)
  const [polling, setPolling] = useState(false)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  /** Call on mouse-enter of trigger button — pre-fetches the "before" state */
  const captureBefore = useCallback(async () => {
    try {
      const snap = await fetchSnapshot(endpoint, params)
      setBefore(snap)
    } catch { /* ignore pre-fetch errors */ }
  }, [endpoint, params])

  /** Call after trigger fires — polls until change detected or timeout */
  const startPolling = useCallback(() => {
    if (timerRef.current) return
    setPolling(true)
    let attempts = 0
    timerRef.current = setInterval(async () => {
      attempts++
      try {
        const snap = await fetchSnapshot(endpoint, params)
        setAfter(snap)
      } catch { /* ignore */ }
      if (attempts >= 6) { // 6 × 2s = 12s
        clearInterval(timerRef.current!)
        timerRef.current = null
        setPolling(false)
      }
    }, 2000)
  }, [endpoint, params])

  /** Re-capture the current DB state as the new "before", clear "after", and start a fresh poll. */
  const retriggerPolling = useCallback(async () => {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null }
    setPolling(false)
    try {
      const snap = await fetchSnapshot(endpoint, params)
      setBefore(snap)
      setAfter(null)
    } catch { /* ignore */ }
    // startPolling is stable (useCallback with no deps that change)
    // We call it after state updates to get a fresh poll window
    setTimeout(() => startPolling(), 0)
  }, [endpoint, params, startPolling])

  const reset = useCallback(() => {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null }
    setBefore(null)
    setAfter(null)
    setPolling(false)
  }, [])

  return { before, after, polling, captureBefore, startPolling, retriggerPolling, reset }
}
