import { useState, useEffect, useCallback } from 'react'
import type { ExecutiveDashboardResponse } from '../types'

const POLL_INTERVAL_MS = 5 * 60 * 1000 // 5 minutes

export function useExecutiveDashboard() {
  const [data, setData] = useState<ExecutiveDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const fetch_ = useCallback(async () => {
    try {
      const res = await fetch('/v1/dashboard/executive', {
        headers: { 'X-Dev-Role': 'EXECUTIVE' },
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const json: ExecutiveDashboardResponse = await res.json()
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
