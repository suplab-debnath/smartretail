import { useState, useEffect } from 'react'
import type { InventoryPositionListResponse } from '../types'

export function useInventoryPositions(dcId?: string) {
  const [data, setData] = useState<InventoryPositionListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    const params = new URLSearchParams()
    if (dcId) params.set('dcId', dcId)
    const query = params.toString() ? `?${params.toString()}` : ''

    fetch(`/v1/inventory/positions${query}`, {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<InventoryPositionListResponse>
      })
      .then(json => {
        if (!cancelled) {
          setData(json)
          setError(null)
        }
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Unknown error')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [dcId])

  return { data, loading, error }
}
