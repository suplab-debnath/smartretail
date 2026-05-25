import { useState, useEffect } from 'react'
import { fetchJson } from '@smartretail/auth'
import type { SupplierPerformanceDashboardResponse } from '../types'

export function useSuppliers(): Record<string, string> {
  const [map, setMap] = useState<Record<string, string>>({})

  useEffect(() => {
    fetchJson<SupplierPerformanceDashboardResponse>('/v1/dashboard/supplier-performance', {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(data => {
        const resolved: Record<string, string> = {}
        for (const s of data.suppliers) resolved[s.supplierId] = s.supplierName
        setMap(resolved)
      })
      .catch(() => { /* leave map empty — callers fall back to UUID */ })
  }, [])

  return map
}
