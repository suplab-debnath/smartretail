import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { InventoryPositionListResponse } from '../types'

export function useInventoryPositions(dcId?: string, refreshKey = 0) {
  const [data, setData] = useState<InventoryPositionListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  const refetch = useCallback(() => setRetryKey(k => k + 1), [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    const params = new URLSearchParams()
    if (dcId) params.set('dcId', dcId)
    const query = params.toString() ? `?${params.toString()}` : ''

    fetchJson<InventoryPositionListResponse>(`/v1/inventory/positions${query}`, {
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
  }, [dcId, retryKey, refreshKey])

  return { data, loading, error, refetch }
}
