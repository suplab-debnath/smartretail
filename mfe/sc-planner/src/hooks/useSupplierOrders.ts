import { useState, useEffect } from 'react'
import type { SupplierOrderListResponse } from '../types'

export function useSupplierOrders(status?: string) {
  const [data, setData] = useState<SupplierOrderListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    const params = new URLSearchParams()
    if (status && status !== 'ALL') params.set('status', status)
    const query = params.toString() ? `?${params.toString()}` : ''

    fetch(`/v1/supplier/orders${query}`, {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<SupplierOrderListResponse>
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
  }, [status])

  return { data, loading, error }
}
