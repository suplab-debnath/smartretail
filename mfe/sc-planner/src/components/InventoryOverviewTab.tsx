import { useState } from 'react'
import { ErrorBanner, Tooltip } from '@smartretail/auth'
import { useInventoryPositions } from '../hooks/useInventoryPositions'
import type { InventoryPosition } from '../types'

type DcId = 'DC-LONDON' | 'DC-MANCHESTER' | 'DC-BIRMINGHAM'

function computeAtp(pos: InventoryPosition): number {
  return pos.onHand + pos.inTransit - pos.reserved
}

function reorderStatus(pos: InventoryPosition): { label: string; cls: string } {
  const atp = computeAtp(pos)
  if (atp <= 0) return { label: 'CRITICAL', cls: 'bg-red-100 text-red-700' }
  if (atp < pos.reorderPoint) return { label: 'REORDER SOON', cls: 'bg-amber-100 text-amber-700' }
  return { label: 'OK', cls: 'bg-green-100 text-green-700' }
}

interface PositionCardProps {
  pos: InventoryPosition
}

function PositionCard({ pos }: PositionCardProps) {
  const { label, cls } = reorderStatus(pos)
  const atp = computeAtp(pos)
  return (
    <div className="bg-white rounded-lg shadow p-4 border-t-4 border-gray-200">
      <div className="flex items-start justify-between mb-2">
        <span className="font-mono text-sm font-semibold text-gray-800">{pos.skuId}</span>
        <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold ${cls}`}>
          {label}
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-1 text-xs text-gray-600">
        <dt className="text-gray-500"><Tooltip term="ON_HAND">On-Hand</Tooltip></dt>
        <dd className="text-right font-medium">{pos.onHand.toLocaleString()}</dd>
        <dt className="text-gray-500"><Tooltip term="IN_TRANSIT">In-Transit</Tooltip></dt>
        <dd className="text-right font-medium">{pos.inTransit.toLocaleString()}</dd>
        <dt className="text-gray-500"><Tooltip term="RESERVED">Reserved</Tooltip></dt>
        <dd className="text-right font-medium">{pos.reserved.toLocaleString()}</dd>
        <dt className="text-gray-500"><Tooltip term="ATP">ATP</Tooltip></dt>
        <dd className={`text-right font-bold ${atp <= 0 ? 'text-red-600' : atp < pos.reorderPoint ? 'text-amber-600' : 'text-green-600'}`}>
          {atp.toLocaleString()}
        </dd>
      </dl>
    </div>
  )
}

export function InventoryOverviewTab() {
  const [selectedDc, setSelectedDc] = useState<DcId>('DC-LONDON')
  const { data, loading, error, refetch } = useInventoryPositions(selectedDc)

  return (
    <div>
      <ErrorBanner error={error} onRetry={refetch} />

      <div className="mb-4 flex items-center gap-3">
        <label className="text-sm font-medium text-gray-700">
          <Tooltip term="DC">Distribution Centre</Tooltip>:
        </label>
        <select
          value={selectedDc}
          onChange={e => setSelectedDc(e.target.value as DcId)}
          className="border border-gray-300 rounded px-2 py-1 text-sm"
        >
          {(['DC-LONDON', 'DC-MANCHESTER', 'DC-BIRMINGHAM'] as DcId[]).map(dc => (
            <option key={dc} value={dc}>{dc}</option>
          ))}
        </select>
      </div>

      {loading && !data ? (
        <div className="p-8 text-gray-500">Loading inventory positions…</div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
            {(data?.positions ?? []).map(pos => (
              <PositionCard key={pos.positionId} pos={pos} />
            ))}
            {(data?.positions ?? []).length === 0 && (
              <div className="col-span-3 py-12 text-center text-gray-400">
                {error ? 'Data unavailable' : 'No inventory positions found'}
              </div>
            )}
          </div>
          {data?.dataFreshness && (
            <p className="mt-4 text-xs text-gray-400">
              Data as of {new Date(data.dataFreshness).toLocaleTimeString()}
            </p>
          )}
        </>
      )}
    </div>
  )
}
