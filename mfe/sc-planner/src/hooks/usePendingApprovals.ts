import { useState, useEffect, useCallback } from 'react'
import { fetchJson, isFetchError, type FetchError } from '@smartretail/auth'
import type { PurchaseOrder, PurchaseOrderListResponse } from '../types'

export function usePendingApprovals() {
  const [orders, setOrders] = useState<PurchaseOrder[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<FetchError | null>(null)

  const fetch_ = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const json = await fetchJson<PurchaseOrderListResponse>(
        '/v1/replenishment/orders?status=PENDING_APPROVAL',
        { headers: { 'X-Dev-Role': 'SC_PLANNER' } },
      )
      setOrders(json.orders)
    } catch (e) {
      setError(isFetchError(e) ? e : { kind: 'network', message: 'Unknown error' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetch_()
  }, [fetch_])

  const removeOrder = useCallback((poId: string) => {
    setOrders(prev => prev.filter(o => o.poId !== poId))
  }, [])

  return { orders, loading, error, removeOrder, refetch: fetch_ }
}
