import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { StockAlertListResponse } from '../types'

export function useExceptionQueue(dcId?: string, refreshKey = 0) {
  const [data, setData] = useState<StockAlertListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  const refetch = useCallback(() => setRetryKey(k => k + 1), [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    const params = new URLSearchParams({ status: 'ACTIVE' })
    if (dcId) params.set('dcId', dcId)
    fetchJson<StockAlertListResponse>(
      `/v1/inventory/alerts?${params.toString()}`,
      { headers: { 'X-Dev-Role': 'SC_PLANNER' } },
    )
      .then(json => { if (!cancelled) { setData(json); setError(null) } })
      .catch(e => { if (!cancelled) setError(isFetchError(e) ? e : { kind: 'network', message: 'Unknown error' }) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [dcId, retryKey, refreshKey])

  return { data, loading, error, refetch }
}
