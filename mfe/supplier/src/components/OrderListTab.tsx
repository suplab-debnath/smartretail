import { useState } from 'react'
import { ShipmentStatusBadge } from './ShipmentStatusBadge'
import type { SupplierOrder } from '../hooks/useSupplierOrders'

const PAGE_SIZE = 10

interface Props {
  orders: SupplierOrder[]
}

type SortKey = 'eta' | 'shipmentStatus' | 'skuId' | 'dcId'

export function OrderListTab({ orders }: Props) {
  const [page, setPage] = useState(0)
  const [sortKey, setSortKey] = useState<SortKey>('eta')
  const [sortAsc, setSortAsc] = useState(true)

  const sorted = [...orders].sort((a, b) => {
    const av = a[sortKey] ?? ''
    const bv = b[sortKey] ?? ''
    return sortAsc
      ? String(av).localeCompare(String(bv))
      : String(bv).localeCompare(String(av))
  })

  const totalPages = Math.ceil(sorted.length / PAGE_SIZE)
  const page_items = sorted.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)

  function handleSort(key: SortKey) {
    if (key === sortKey) {
      setSortAsc(a => !a)
    } else {
      setSortKey(key)
      setSortAsc(true)
    }
    setPage(0)
  }

  function SortHeader({ label, col }: { label: string; col: SortKey }) {
    const active = sortKey === col
    return (
      <th
        className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer select-none hover:bg-gray-100"
        onClick={() => handleSort(col)}
      >
        {label} {active ? (sortAsc ? '↑' : '↓') : ''}
      </th>
    )
  }

  if (orders.length === 0) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-400">
        No orders found
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="overflow-x-auto rounded-lg border border-gray-200">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">PO Reference</th>
              <SortHeader label="SKU" col="skuId" />
              <SortHeader label="DC" col="dcId" />
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Qty</th>
              <SortHeader label="Status" col="shipmentStatus" />
              <SortHeader label="ETA" col="eta" />
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Last Update</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-100">
            {page_items.map(order => (
              <tr key={order.supplierPoId} className="hover:bg-gray-50 animate-fadeIn">
                <td className="px-4 py-3 text-sm font-mono text-gray-700">
                  {order.poId.slice(0, 8)}…
                </td>
                <td className="px-4 py-3 text-sm text-gray-900">{order.skuId}</td>
                <td className="px-4 py-3 text-sm text-gray-600">{order.dcId}</td>
                <td className="px-4 py-3 text-sm text-gray-900 tabular-nums">{order.quantity.toLocaleString()}</td>
                <td className="px-4 py-3">
                  <ShipmentStatusBadge status={order.shipmentStatus} />
                </td>
                <td className="px-4 py-3 text-sm text-gray-600 tabular-nums">
                  {order.eta ?? '—'}
                </td>
                <td className="px-4 py-3 text-sm text-gray-500">
                  {order.lastUpdateAt
                    ? new Date(order.lastUpdateAt).toLocaleString()
                    : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-gray-600">
          <span>
            Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, orders.length)} of {orders.length} orders
          </span>
          <div className="flex gap-2">
            <button
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
              className="px-3 py-1 rounded border border-gray-300 disabled:opacity-40 hover:bg-gray-50"
            >
              Previous
            </button>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => setPage(p => p + 1)}
              className="px-3 py-1 rounded border border-gray-300 disabled:opacity-40 hover:bg-gray-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
