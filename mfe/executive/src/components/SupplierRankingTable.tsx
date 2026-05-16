import type { SupplierPerformanceEntry } from '../types'

interface Props {
  suppliers: SupplierPerformanceEntry[]
}

function otdColor(rate: number): string {
  if (rate >= 0.90) return 'text-green-700 bg-green-50'
  if (rate >= 0.75) return 'text-amber-700 bg-amber-50'
  return 'text-red-700 bg-red-50'
}

export function SupplierRankingTable({ suppliers }: Props) {
  return (
    <div className="bg-white rounded-lg shadow overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200">
        <h2 className="text-lg font-semibold text-gray-800">Supplier Performance Comparison</h2>
        <p className="text-sm text-gray-500 mt-1">Ranked by on-time delivery rate (last 90 days)</p>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider w-8">Rank</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Supplier</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">OTD %</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Fill Rate</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Early</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">On-Time</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Late</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Exceptions</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {suppliers.map((s, idx) => (
              <tr key={s.supplierId} className="hover:bg-gray-50">
                <td className="px-4 py-3 text-gray-500 font-medium">{idx + 1}</td>
                <td className="px-4 py-3 font-medium text-gray-900">{s.supplierName}</td>
                <td className="px-4 py-3 text-right">
                  <span className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${otdColor(s.otdRate)}`}>
                    {(s.otdRate * 100).toFixed(0)}%
                  </span>
                </td>
                <td className="px-4 py-3 text-right text-gray-700">{(s.fillRate * 100).toFixed(0)}%</td>
                <td className="px-4 py-3 text-right text-blue-600">{s.earlyCount}</td>
                <td className="px-4 py-3 text-right text-green-600">{s.onTimeCount}</td>
                <td className="px-4 py-3 text-right text-red-600 font-medium">{s.lateCount}</td>
                <td className="px-4 py-3 text-right">
                  {s.openExceptions > 0 ? (
                    <span className="inline-block px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-700">
                      {s.openExceptions}
                    </span>
                  ) : (
                    <span className="text-gray-400">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
