import { useState } from 'react'
import { useSupplierOrders } from '../hooks/useSupplierOrders'
import type { ShipmentStatus, SupplierOrder } from '../types'

type StatusFilter = 'ALL' | ShipmentStatus

const statusBadge: Record<ShipmentStatus, string> = {
  PENDING: 'bg-gray-100 text-gray-700',
  CONFIRMED: 'bg-blue-100 text-blue-700',
  DISPATCHED: 'bg-indigo-100 text-indigo-700',
  DELIVERED: 'bg-green-100 text-green-700',
  COMPLETED: 'bg-green-200 text-green-800',
  EXCEPTION: 'bg-red-100 text-red-700',
}

const STEPS: ShipmentStatus[] = ['CONFIRMED', 'DISPATCHED', 'DELIVERED']

function ShipmentProgressBar({ status }: { status: ShipmentStatus }) {
  if (status === 'EXCEPTION') {
    return (
      <div className="flex items-center gap-1 text-xs text-red-600 font-semibold">
        <span className="text-red-500">⚑</span> EXCEPTION
      </div>
    )
  }
  const activeIdx = STEPS.indexOf(status)
  return (
    <div className="flex items-center gap-1">
      {STEPS.map((step, idx) => (
        <div key={step} className="flex items-center gap-1">
          <div
            className={`w-2 h-2 rounded-full ${idx <= activeIdx ? 'bg-blue-500' : 'bg-gray-300'}`}
            title={step}
          />
          {idx < STEPS.length - 1 && (
            <div className={`h-0.5 w-4 ${idx < activeIdx ? 'bg-blue-500' : 'bg-gray-300'}`} />
          )}
        </div>
      ))}
    </div>
  )
}

function sortOrders(orders: SupplierOrder[]): SupplierOrder[] {
  return [...orders].sort((a, b) => {
    if (a.shipmentStatus === 'EXCEPTION' && b.shipmentStatus !== 'EXCEPTION') return -1
    if (b.shipmentStatus === 'EXCEPTION' && a.shipmentStatus !== 'EXCEPTION') return 1
    if (a.eta && b.eta) return a.eta.localeCompare(b.eta)
    if (a.eta && !b.eta) return -1
    if (!a.eta && b.eta) return 1
    return 0
  })
}

export function SupplierOrderTrackingTab() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const { data, loading, error } = useSupplierOrders(statusFilter === 'ALL' ? undefined : statusFilter)

  if (loading) return <div className="p-8 text-gray-500">Loading supplier orders…</div>
  if (error) return <div className="p-8 text-red-500">Error: {error}</div>

  const orders = sortOrders(data?.orders ?? [])

  return (
    <div>
      <div className="mb-4 flex items-center gap-3">
        <label className="text-sm font-medium text-gray-700">Status:</label>
        <select
          value={statusFilter}
          onChange={e => setStatusFilter(e.target.value as StatusFilter)}
          className="border border-gray-300 rounded px-2 py-1 text-sm"
        >
          {(['ALL', 'PENDING', 'CONFIRMED', 'DISPATCHED', 'DELIVERED', 'EXCEPTION'] as StatusFilter[]).map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      {orders.length === 0 ? (
        <div className="py-12 text-center text-gray-400">No supplier orders found</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {['Supplier', 'SKU', 'DC', 'Qty', 'Status', 'Progress', 'ETA', 'Last Update'].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {orders.map(order => (
                <tr
                  key={order.supplierPoId}
                  className={order.shipmentStatus === 'EXCEPTION' ? 'bg-red-50' : 'hover:bg-gray-50'}
                >
                  <td className="px-4 py-3 text-xs">{order.supplierName}</td>
                  <td className="px-4 py-3 font-mono text-xs">{order.skuId}</td>
                  <td className="px-4 py-3 text-xs">{order.dcId}</td>
                  <td className="px-4 py-3 text-right">{order.quantity.toLocaleString()}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold ${statusBadge[order.shipmentStatus]}`}>
                      {order.shipmentStatus}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <ShipmentProgressBar status={order.shipmentStatus} />
                  </td>
                  <td className="px-4 py-3 text-xs text-gray-500">
                    {order.eta ? new Date(order.eta).toLocaleDateString() : '—'}
                  </td>
                  <td className="px-4 py-3 text-xs text-gray-500">
                    {order.lastUpdateAt ? new Date(order.lastUpdateAt).toLocaleString() : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data?.dataFreshness && (
        <p className="mt-4 text-xs text-gray-400">
          Data as of {new Date(data.dataFreshness).toLocaleTimeString()}
        </p>
      )}
    </div>
  )
}
