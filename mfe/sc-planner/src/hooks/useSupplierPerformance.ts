import { useState, useEffect } from 'react'
import type { SupplierPerformanceDashboardResponse } from '../types'

export function useSupplierPerformance() {
  const [data, setData] = useState<SupplierPerformanceDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    fetch('/v1/dashboard/supplier-performance', {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<SupplierPerformanceDashboardResponse>
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
  }, [])

  return { data, loading, error }
}
