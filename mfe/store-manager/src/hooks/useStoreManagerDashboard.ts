import { useState, useEffect, useCallback } from 'react'
import type { StoreManagerDashboardResponse } from '../types'

const POLL_INTERVAL_MS = 60_000

export function useStoreManagerDashboard(dcId: string, page: number, size = 10) {
  const [data, setData] = useState<StoreManagerDashboardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchDashboard = useCallback(async () => {
    setError(null)
    try {
      const url = `/v1/dashboard/store-manager?dcId=${encodeURIComponent(dcId)}&page=${page}&size=${size}`
      const res = await fetch(url, {
        headers: { 'X-Dev-Role': 'STORE_MANAGER' },
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const json: StoreManagerDashboardResponse = await res.json()
      setData(json)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }, [dcId, page, size])

  useEffect(() => {
    let cancelled = false
    setLoading(true)

    const run = async () => {
      if (!cancelled) await fetchDashboard()
    }

    run()
    const id = setInterval(() => { if (!cancelled) fetchDashboard() }, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [fetchDashboard])

  return { data, loading, error, refresh: fetchDashboard }
}
