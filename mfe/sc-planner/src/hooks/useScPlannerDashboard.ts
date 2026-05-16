import { useState, useEffect, useCallback } from 'react'
import type { ScPlannerDashboardResponse } from '../types'

const POLL_INTERVAL_MS = 2 * 60 * 1000 // 2 minutes

export function useScPlannerDashboard() {
  const [data, setData] = useState<ScPlannerDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const fetch_ = useCallback(async () => {
    try {
      const res = await fetch('/v1/dashboard/sc-planner', {
        headers: { 'X-Dev-Role': 'SC_PLANNER' },
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const json: ScPlannerDashboardResponse = await res.json()
      setData(json)
      setLastUpdated(new Date())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetch_()
    const id = setInterval(fetch_, POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [fetch_])

  return { data, loading, error, lastUpdated, refresh: fetch_ }
}
