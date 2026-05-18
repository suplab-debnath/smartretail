import { useState, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { StockAlertListResponse } from '../types'

export function useExceptionQueue(dcId?: string) {
  const [data, setData] = useState<StockAlertListResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<FetchError | null>(null)

  const refetch = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({ status: 'ACTIVE' })
      if (dcId) params.set('dcId', dcId)
      const json = await fetchJson<StockAlertListResponse>(
        `/v1/inventory/alerts?${params.toString()}`,
        { headers: { 'X-Dev-Role': 'SC_PLANNER' } },
      )
      setData(json)
    } catch (e) {
      setError(isFetchError(e) ? e : { kind: 'network', message: 'Unknown error' })
    } finally {
      setLoading(false)
    }
  }, [dcId])

  return { data, loading, error, refetch }
}
