import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { ExecutiveDashboardResponse } from '../types'

const POLL_INTERVAL_MS = 5 * 60 * 1000 // 5 minutes

export function useExecutiveDashboard() {
  const [data, setData] = useState<ExecutiveDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const fetch_ = useCallback(async () => {
    try {
      const json = await fetchJson<ExecutiveDashboardResponse>('/v1/dashboard/executive', {
        headers: { 'X-Dev-Role': 'EXECUTIVE' },
      })
      setData(json)
      setLastUpdated(new Date())
      setError(null)
    } catch (e) {
      setError(isFetchError(e) ? e : { kind: 'network', message: 'Unknown error' })
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
