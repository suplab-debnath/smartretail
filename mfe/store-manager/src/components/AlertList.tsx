import { SeverityBadge } from './SeverityBadge'
import type { StockAlertSummary } from '../types'

interface Props {
  alerts: StockAlertSummary[]
  page: number
  totalPages: number
  onPageChange: (page: number) => void
}

export function AlertList({ alerts, page, totalPages, onPageChange }: Props) {
  if (alerts.length === 0) {
    return (
      <div className="bg-white rounded-lg border border-gray-200 p-8 text-center text-gray-400">
        No active alerts for this distribution centre
      </div>
    )
  }

  return (
    <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
      <div className="px-5 py-4 border-b border-gray-200">
        <h2 className="text-sm font-semibold text-gray-700">Active Stock Alerts</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              {['SKU', 'DC', 'Type', 'Severity', 'On Hand', 'Reorder Point', 'Raised At'].map(h => (
                <th
                  key={h}
                  className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-100">
            {alerts.map(alert => (
              <tr key={alert.alertId} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-mono text-xs">{alert.skuId}</td>
                <td className="px-4 py-3 text-xs">{alert.dcId}</td>
                <td className="px-4 py-3 text-xs">{alert.alertType.replace('_', ' ')}</td>
                <td className="px-4 py-3">
                  <SeverityBadge severity={alert.severity} />
                </td>
                <td className="px-4 py-3 text-right">{alert.onHand}</td>
                <td className="px-4 py-3 text-right">{alert.reorderPoint}</td>
                <td className="px-4 py-3 text-xs text-gray-500">
                  {new Date(alert.raisedAt).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="px-5 py-3 border-t border-gray-200 flex items-center justify-between text-sm">
          <span className="text-gray-500">Page {page + 1} of {totalPages}</span>
          <div className="flex gap-2">
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
              className="px-3 py-1 border border-gray-300 rounded text-xs disabled:opacity-40 hover:bg-gray-50"
            >
              Previous
            </button>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={page >= totalPages - 1}
              className="px-3 py-1 border border-gray-300 rounded text-xs disabled:opacity-40 hover:bg-gray-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
