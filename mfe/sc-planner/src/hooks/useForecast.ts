import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { ForecastDataResponse } from '../types'

export function useForecast(skuId: string, dcId: string, horizonDays: 7 | 14 | 30) {
  const [data, setData] = useState<ForecastDataResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<FetchError | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  const refetch = useCallback(() => setRetryKey(k => k + 1), [])

  useEffect(() => {
    if (!skuId || !dcId) return

    let cancelled = false
    setLoading(true)
    setError(null)

    fetchJson<ForecastDataResponse>(
      `/v1/forecast/${encodeURIComponent(skuId)}/${encodeURIComponent(dcId)}?horizonDays=${horizonDays}`,
      { headers: { 'X-Dev-Role': 'SC_PLANNER' } },
    )
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
  }, [skuId, dcId, horizonDays, retryKey])

  return { data, loading, error, refetch }
}
