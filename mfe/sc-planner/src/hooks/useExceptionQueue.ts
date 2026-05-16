import { useState, useCallback } from 'react'
import type { StockAlertListResponse } from '../types'

export function useExceptionQueue(dcId?: string) {
  const [data, setData] = useState<StockAlertListResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refetch = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({ status: 'ACTIVE' })
      if (dcId) params.set('dcId', dcId)
      const res = await fetch(`/v1/inventory/alerts?${params.toString()}`, {
        headers: { 'X-Dev-Role': 'SC_PLANNER' },
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const json: StockAlertListResponse = await res.json()
      setData(json)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }, [dcId])

  return { data, loading, error, refetch }
}
