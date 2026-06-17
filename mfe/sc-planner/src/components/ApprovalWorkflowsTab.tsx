import { Fragment, useState } from 'react'
import { ErrorBanner, Tooltip, getApiBase } from '@smartretail/auth'
import { usePendingApprovals } from '../hooks/usePendingApprovals'
import { useSuppliers } from '../hooks/useSuppliers'

interface Toast {
  id: number
  message: string
  type: 'success' | 'warning' | 'error'
}

let toastId = 0

interface Props {
  refreshKey?: number
}

export function ApprovalWorkflowsTab({ refreshKey = 0 }: Props) {
  const { orders, loading, error, removeOrder, refetch } = usePendingApprovals(refreshKey)
  const supplierMap = useSuppliers()
  const [toasts, setToasts] = useState<Toast[]>([])
  const [approvingIds, setApprovingIds] = useState<Set<string>>(new Set())
  const [rejectingId, setRejectingId] = useState<string | null>(null)
  const [rejectReason, setRejectReason] = useState('')

  function addToast(message: string, type: Toast['type']) {
    const id = ++toastId
    setToasts(prev => [...prev, { id, message, type }])
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3000)
  }

  async function handleApprove(poId: string, version: number) {
    setApprovingIds(prev => new Set(prev).add(poId))
    try {
      const res = await fetch(`${getApiBase()}/v1/replenishment/orders/${poId}/approve`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Dev-Role': 'SC_PLANNER',
          'X-Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ version }),
      })
      if (res.ok) {
        removeOrder(poId)
        addToast(`PO ${poId.slice(0, 8)}… approved`, 'success')
      } else if (res.status === 409) {
        addToast('PO status changed — please refresh', 'warning')
      } else {
        addToast(`Approve failed: HTTP ${res.status}`, 'error')
      }
    } catch (e) {
      addToast(`Network error: ${e instanceof Error ? e.message : 'Unknown'}`, 'error')
    } finally {
      setApprovingIds(prev => {
        const next = new Set(prev)
        next.delete(poId)
        return next
      })
    }
  }

  async function handleRejectConfirm(poId: string, version: number) {
    try {
      const res = await fetch(`${getApiBase()}/v1/replenishment/orders/${poId}/reject`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Dev-Role': 'SC_PLANNER',
          'X-Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ version, rejectionReason: rejectReason }),
      })
      if (res.ok) {
        removeOrder(poId)
        addToast(`PO ${poId.slice(0, 8)}… rejected`, 'success')
      } else {
        addToast(`Reject failed: HTTP ${res.status}`, 'error')
      }
    } catch (e) {
      addToast(`Network error: ${e instanceof Error ? e.message : 'Unknown'}`, 'error')
    } finally {
      setRejectingId(null)
      setRejectReason('')
    }
  }

  const toastColorMap: Record<Toast['type'], string> = {
    success: 'bg-green-600',
    warning: 'bg-amber-500',
    error: 'bg-red-600',
  }

  return (
    <div>
      <ErrorBanner error={error} onRetry={refetch} />

      {loading && orders.length === 0 ? (
        <div className="p-8 text-gray-500">Loading pending approvals…</div>
      ) : orders.length === 0 ? (
        <div className="py-12 text-center text-gray-400">
          {error ? 'Data unavailable' : 'No POs awaiting approval'}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {[
                  { label: 'PO ID', term: 'PO' },
                  { label: 'Supplier' },
                  { label: 'SKU', term: 'SKU' },
                  { label: 'DC', term: 'DC' },
                  { label: 'Order Qty' },
                  { label: 'Total Value' },
                  { label: 'PO Date' },
                  { label: 'Actions' },
                ].map(h => (
                  <th key={h.label} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    {h.term ? <Tooltip term={h.term}>{h.label}</Tooltip> : h.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {orders.map(po => (
                <Fragment key={po.poId}>
                  <tr className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs">{po.poId.slice(0, 12)}…</td>
                    <td className="px-4 py-3 text-xs">{supplierMap[po.supplierId] ?? `${po.supplierId.slice(0, 12)}…`}</td>
                    <td className="px-4 py-3 font-mono text-xs">{po.skuId}</td>
                    <td className="px-4 py-3 text-xs">{po.dcId}</td>
                    <td className="px-4 py-3 text-right">{po.quantity.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right">£{po.totalValue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{new Date(po.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-3 flex gap-2">
                      <button
                        onClick={() => handleApprove(po.poId, po.version)}
                        disabled={approvingIds.has(po.poId)}
                        className="px-3 py-1 bg-green-600 text-white text-xs rounded hover:bg-green-700 disabled:opacity-50"
                      >
                        {approvingIds.has(po.poId) ? 'Approving…' : 'Approve'}
                      </button>
                      <button
                        onClick={() => { setRejectingId(po.poId); setRejectReason('') }}
                        className="px-3 py-1 bg-red-100 text-red-700 text-xs rounded hover:bg-red-200"
                      >
                        Reject
                      </button>
                    </td>
                  </tr>
                  {rejectingId === po.poId && (
                    <tr key={`${po.poId}-reject`}>
                      <td colSpan={8} className="px-4 py-3 bg-red-50">
                        <div className="flex items-center gap-3">
                          <input
                            type="text"
                            value={rejectReason}
                            onChange={e => setRejectReason(e.target.value)}
                            placeholder="Rejection reason (required)"
                            className="border border-red-300 rounded px-2 py-1 text-sm flex-1"
                          />
                          <button
                            onClick={() => handleRejectConfirm(po.poId, po.version)}
                            disabled={!rejectReason.trim()}
                            className="px-3 py-1 bg-red-600 text-white text-xs rounded hover:bg-red-700 disabled:opacity-50"
                          >
                            Confirm Reject
                          </button>
                          <button
                            onClick={() => setRejectingId(null)}
                            className="px-3 py-1 text-gray-500 text-xs rounded hover:text-gray-700"
                          >
                            Cancel
                          </button>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Toasts */}
      <div className="fixed bottom-6 right-6 space-y-2 z-50">
        {toasts.map(t => (
          <div key={t.id} className={`${toastColorMap[t.type]} text-white text-sm px-4 py-2 rounded shadow-lg animate-fadeIn`}>
            {t.message}
          </div>
        ))}
      </div>
    </div>
  )
}
