import { useState, useEffect } from 'react'
import type { ForecastDataResponse } from '../types'

export function useForecast(skuId: string, dcId: string, horizonDays: 7 | 14 | 30) {
  const [data, setData] = useState<ForecastDataResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!skuId || !dcId) return

    let cancelled = false
    setLoading(true)
    setError(null)

    fetch(`/v1/forecast/${encodeURIComponent(skuId)}/${encodeURIComponent(dcId)}?horizonDays=${horizonDays}`, {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<ForecastDataResponse>
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
  }, [skuId, dcId, horizonDays])

  return { data, loading, error }
}
