import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { SupplierOrderListResponse } from '../types'

export function useSupplierOrders(status?: string, refreshKey = 0) {
  const [data, setData] = useState<SupplierOrderListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  const refetch = useCallback(() => setRetryKey(k => k + 1), [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    const params = new URLSearchParams()
    if (status && status !== 'ALL') params.set('status', status)
    const query = params.toString() ? `?${params.toString()}` : ''

    fetchJson<SupplierOrderListResponse>(`/v1/supplier/orders${query}`, {
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
  }, [status, retryKey, refreshKey])

  return { data, loading, error, refetch }
}
