import { useState, useEffect, useCallback } from 'react'
import { useAuth } from '@smartretail/auth'

export interface SupplierOrder {
  supplierPoId: string
  poId: string
  supplierId: string
  supplierName: string
  skuId: string
  dcId: string
  quantity: number
  shipmentStatus: 'PENDING' | 'CONFIRMED' | 'DISPATCHED' | 'DELIVERED' | 'COMPLETED' | 'EXCEPTION'
  confirmedAt: string | null
  dispatchedAt: string | null
  eta: string | null
  lastUpdateAt: string | null
}

interface UseSupplierOrdersResult {
  orders: SupplierOrder[]
  dataFreshness: string
  isLoading: boolean
  error: string | null
  refresh: () => void
}

const POLL_INTERVAL_MS = 60_000

export function useSupplierOrders(): UseSupplierOrdersResult {
  const { token } = useAuth()
  const [orders, setOrders] = useState<SupplierOrder[]>([])
  const [dataFreshness, setDataFreshness] = useState(new Date().toISOString())
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchOrders = useCallback(async () => {
    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (token && token !== 'mock-token') {
        headers['Authorization'] = `Bearer ${token}`
      } else {
        headers['X-Dev-Role'] = 'SUPPLIER_ADMIN'
      }

      const response = await fetch('/v1/supplier/orders', { headers })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const data = await response.json()
      setOrders(data.orders ?? [])
      setDataFreshness(data.dataFreshness ?? new Date().toISOString())
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load orders')
    } finally {
      setIsLoading(false)
    }
  }, [token])

  useEffect(() => {
    fetchOrders()
    const id = setInterval(fetchOrders, POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [fetchOrders])

  return { orders, dataFreshness, isLoading, error, refresh: fetchOrders }
}
