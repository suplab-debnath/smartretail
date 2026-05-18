import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { SupplierPerformanceDashboardResponse } from '../types'

export function useSupplierPerformance() {
  const [data, setData] = useState<SupplierPerformanceDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  const refetch = useCallback(() => setRetryKey(k => k + 1), [])

  useEffect(() => {
    let cancelled = false

    fetchJson<SupplierPerformanceDashboardResponse>('/v1/dashboard/supplier-performance', {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(json => {
        if (!cancelled) {
          setData(json)
          setError(null)
        }
      })
      .catch(e => {
        if (!cancelled)
          setError(isFetchError(e) ? e : { kind: 'network', message: 'Unknown error' })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [retryKey])

  return { data, loading, error, refetch }
}
