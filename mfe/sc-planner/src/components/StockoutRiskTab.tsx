import { useState } from 'react'
import { ErrorBanner, Tooltip } from '@smartretail/auth'
import { useInventoryPositions } from '../hooks/useInventoryPositions'
import type { InventoryPosition, StockoutRisk } from '../types'

interface Props {
  onTriggerReplenishment: (skuId: string, dcId: string) => void
}

function computeAtp(pos: InventoryPosition): number {
  return pos.onHand + pos.inTransit - pos.reserved
}

function computeRisk(pos: InventoryPosition): StockoutRisk {
  const atp = computeAtp(pos)
  if (atp <= 0) return 'CRITICAL'
  if (atp < pos.reorderPoint * 0.5) return 'HIGH'
  if (atp < pos.reorderPoint) return 'MODERATE'
  return 'OK'
}

const riskOrder: Record<StockoutRisk, number> = { CRITICAL: 0, HIGH: 1, MODERATE: 2, OK: 3 }

const riskChip: Record<StockoutRisk, string> = {
  CRITICAL: 'bg-red-100 text-red-700',
  HIGH: 'bg-orange-100 text-orange-700',
  MODERATE: 'bg-yellow-100 text-yellow-700',
  OK: 'bg-green-100 text-green-700',
}

export function StockoutRiskTab({ onTriggerReplenishment }: Props) {
  const { data, loading, error, refetch } = useInventoryPositions(undefined)
  const [showAll, setShowAll] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const positions = data?.positions ?? []
  const withRisk = positions
    .map(pos => ({ pos, risk: computeRisk(pos) }))
    .sort((a, b) => riskOrder[a.risk] - riskOrder[b.risk])

  const displayed = showAll ? withRisk : withRisk.filter(r => r.risk !== 'OK')

  function toggleSelect(key: string) {
    setSelected(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })
  }

  function handleBulkTrigger() {
    selected.forEach(key => {
      const [skuId, dcId] = key.split('|')
      onTriggerReplenishment(skuId, dcId)
    })
    setSelected(new Set())
  }

  const actionableRisks: StockoutRisk[] = ['CRITICAL', 'HIGH']

  return (
    <div>
      <ErrorBanner error={error} onRetry={refetch} />

      {loading && !data ? (
        <div className="p-8 text-gray-500">Loading inventory positions…</div>
      ) : (
        <>
          <div className="mb-4 flex items-center gap-4">
            <label className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
              <input
                type="checkbox"
                checked={showAll}
                onChange={e => setShowAll(e.target.checked)}
                className="rounded"
              />
              Show OK rows
            </label>
            {selected.size > 0 && (
              <button
                onClick={handleBulkTrigger}
                className="px-4 py-1.5 bg-red-600 text-white text-sm rounded hover:bg-red-700"
              >
                Raise POs for {selected.size} selected
              </button>
            )}
          </div>

          {displayed.length === 0 ? (
            <div className="py-12 text-center text-gray-400">
              {error ? 'Data unavailable' : 'No at-risk positions'}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200 text-sm">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-3 w-8"></th>
                    {[
                      { label: 'SKU', term: 'SKU' },
                      { label: 'DC', term: 'DC' },
                      { label: 'On-Hand', term: 'ON_HAND' },
                      { label: 'ATP', term: 'ATP' },
                      { label: 'Reorder Point', term: 'REORDER_POINT' },
                      { label: 'Risk', term: 'STOCKOUT_RISK' },
                      { label: 'Action' },
                    ].map(h => (
                      <th key={h.label} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        {h.term ? <Tooltip term={h.term}>{h.label}</Tooltip> : h.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-100">
                  {displayed.map(({ pos, risk }) => {
                    const key = `${pos.skuId}|${pos.dcId}`
                    const isActionable = actionableRisks.includes(risk)
                    const atp = computeAtp(pos)
                    return (
                      <tr key={pos.positionId} className="hover:bg-gray-50">
                        <td className="px-4 py-3">
                          {isActionable && (
                            <input
                              type="checkbox"
                              checked={selected.has(key)}
                              onChange={() => toggleSelect(key)}
                              className="rounded"
                            />
                          )}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs">{pos.skuId}</td>
                        <td className="px-4 py-3 text-xs">{pos.dcId}</td>
                        <td className="px-4 py-3 text-right">{pos.onHand.toLocaleString()}</td>
                        <td className="px-4 py-3 text-right font-semibold">{atp.toLocaleString()}</td>
                        <td className="px-4 py-3 text-right">{pos.reorderPoint.toLocaleString()}</td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold ${riskChip[risk]}`}>
                            {risk}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          {isActionable && (
                            <button
                              onClick={() => onTriggerReplenishment(pos.skuId, pos.dcId)}
                              className="px-3 py-1 bg-blue-600 text-white text-xs rounded hover:bg-blue-700"
                            >
                              Create PO
                            </button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

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
