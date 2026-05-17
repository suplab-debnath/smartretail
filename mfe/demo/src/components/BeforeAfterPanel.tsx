import { useState } from 'react'
import type { DbSnapshot } from '../types'

interface Props {
  label:  string
  before: DbSnapshot | null
  after:  DbSnapshot | null
}

function renderValue(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function getColumns(rows: Record<string, unknown>[]): string[] {
  if (rows.length === 0) return []
  return Object.keys(rows[0])
}

export default function BeforeAfterPanel({ label, before, after }: Props) {
  const [collapsed, setCollapsed] = useState(false)

  if (!before && !after) return null

  const cols = getColumns(before?.rows ?? after?.rows ?? [])

  return (
    <div className="bg-slate-900 rounded-lg border border-slate-800 mb-4 overflow-hidden">
      <button
        onClick={() => setCollapsed(c => !c)}
        className="w-full flex items-center justify-between px-3 py-2 text-left hover:bg-slate-800/50 transition-colors"
      >
        <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">{label}</span>
        <span className="text-xs text-slate-600">{collapsed ? '▸ show' : '▾ hide'}</span>
      </button>

      {!collapsed && (
        <div className="grid grid-cols-2 divide-x divide-slate-800 border-t border-slate-800">
          {/* Before */}
          <div>
            <div className="px-3 py-1.5 bg-slate-800/50">
              <span className="text-xs font-semibold text-slate-500">BEFORE</span>
              {before && (
                <span className="text-xs text-slate-700 ml-2">{before.timestamp.slice(11, 19)}</span>
              )}
            </div>
            <DbTable cols={cols} rows={before?.rows ?? []} compareRows={after?.rows} highlight="before" />
          </div>
          {/* After */}
          <div>
            <div className="px-3 py-1.5 bg-slate-800/50">
              <span className="text-xs font-semibold text-emerald-600">AFTER</span>
              {after && (
                <span className="text-xs text-slate-700 ml-2">{after.timestamp.slice(11, 19)}</span>
              )}
            </div>
            <DbTable cols={cols} rows={after?.rows ?? []} compareRows={before?.rows} highlight="after" />
          </div>
        </div>
      )}
    </div>
  )
}

function DbTable({
  cols, rows, compareRows, highlight,
}: {
  cols:        string[]
  rows:        Record<string, unknown>[]
  compareRows: Record<string, unknown>[] | undefined
  highlight:   'before' | 'after'
}) {
  if (rows.length === 0) {
    return <p className="px-3 py-4 text-xs text-slate-700 text-center">No rows</p>
  }

  const keyCol = cols[0] ?? ''

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs font-mono">
        <thead>
          <tr className="border-b border-slate-800">
            {cols.map(c => (
              <th key={c} className="px-2 py-1 text-left text-slate-600 font-normal whitespace-nowrap">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => {
            const compRow = compareRows?.find(r => String(r[keyCol]) === String(row[keyCol]))
            return (
              <tr key={ri} className="border-b border-slate-800/50">
                {cols.map(col => {
                  const val     = renderValue(row[col])
                  const compVal = compRow ? renderValue(compRow[col]) : null
                  const changed = compVal !== null && compVal !== val
                  const cellStyle = changed
                    ? highlight === 'after'
                      ? 'bg-emerald-950 text-emerald-300'
                      : 'bg-amber-950 text-amber-400 line-through opacity-60'
                    : 'text-slate-400'
                  return (
                    <td key={col} className={`px-2 py-1 whitespace-nowrap ${cellStyle}`}>
                      {val.length > 20 ? val.slice(0, 20) + '…' : val}
                    </td>
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
