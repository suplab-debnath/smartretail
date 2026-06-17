import { useState } from 'react'
import { ErrorBanner, Tooltip } from '@smartretail/auth'
import { useExceptionQueue } from '../hooks/useExceptionQueue'
import { SeverityBadge } from './SeverityBadge'
import type { AlertType } from '../types'

type DcFilter = 'ALL' | 'DC-LONDON' | 'DC-MANCHESTER' | 'DC-BIRMINGHAM'

interface Props {
  onTriggerReplenishment: (skuId: string, dcId: string) => void
  refreshKey?: number
}

function alertTypeChip(type: AlertType) {
  if (type === 'LOW_STOCK') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-orange-100 text-orange-700">
        LOW STOCK
      </span>
    )
  }
  return (
    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-blue-100 text-blue-700">
      OVERSTOCK
    </span>
  )
}

function formatDate(iso: string) {
  const d = new Date(iso)
  return d.toLocaleString()
}

const TABLE_HEADERS: { label: string; term?: string }[] = [
  { label: 'SKU', term: 'SKU' },
  { label: 'DC', term: 'DC' },
  { label: 'Alert Type' },
  { label: 'Severity' },
  { label: 'On-Hand', term: 'ON_HAND' },
  { label: 'Reorder Point', term: 'REORDER_POINT' },
  { label: 'Flagged At' },
  { label: 'Action' },
]

export function ExceptionQueueTab({ onTriggerReplenishment, refreshKey = 0 }: Props) {
  const [dcFilter, setDcFilter] = useState<DcFilter>('ALL')
  const { data, loading, error, refetch } = useExceptionQueue(undefined, refreshKey)

  const alerts = data?.alerts ?? []
  const filtered = dcFilter === 'ALL' ? alerts : alerts.filter(a => a.dcId === dcFilter)

  return (
    <div>
      <ErrorBanner error={error} onRetry={refetch} />

      <div className="mb-4 flex items-center gap-3">
        <label className="text-sm font-medium text-gray-700">DC Filter:</label>
        <select
          value={dcFilter}
          onChange={e => setDcFilter(e.target.value as DcFilter)}
          className="border border-gray-300 rounded px-2 py-1 text-sm"
        >
          {(['ALL', 'DC-LONDON', 'DC-MANCHESTER', 'DC-BIRMINGHAM'] as DcFilter[]).map(dc => (
            <option key={dc} value={dc}>{dc}</option>
          ))}
        </select>
      </div>

      {loading && !data ? (
        <div className="p-8 text-gray-500">Loading exception queue…</div>
      ) : filtered.length === 0 ? (
        <div className="py-12 text-center text-gray-400">
          {error ? 'Data unavailable' : 'No active exceptions'}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {TABLE_HEADERS.map(h => (
                  <th key={h.label} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    {h.term ? <Tooltip term={h.term}>{h.label}</Tooltip> : h.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {filtered.map(alert => (
                <tr key={alert.alertId} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs">{alert.skuId}</td>
                  <td className="px-4 py-3 text-xs">{alert.dcId}</td>
                  <td className="px-4 py-3">{alertTypeChip(alert.alertType)}</td>
                  <td className="px-4 py-3"><SeverityBadge severity={alert.severity} /></td>
                  <td className="px-4 py-3 text-right">{alert.actualValue.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right">{alert.thresholdValue.toLocaleString()}</td>
                  <td className="px-4 py-3 text-xs text-gray-500">{formatDate(alert.raisedAt)}</td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => onTriggerReplenishment(alert.skuId, alert.dcId)}
                      className="px-3 py-1 bg-blue-600 text-white text-xs rounded hover:bg-blue-700"
                    >
                      Raise Purchase Order
                    </button>
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
