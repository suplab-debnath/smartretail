import type { CycleTimeDataPoint } from '../types'

interface Props {
  history: CycleTimeDataPoint[]
}

function avgDaysColor(days: number): string {
  if (days < 4) return 'text-green-700 bg-green-50'
  if (days <= 7) return 'text-amber-700 bg-amber-50'
  return 'text-red-700 bg-red-50'
}

export function CycleTimeHistoryTable({ history }: Props) {
  return (
    <div className="bg-white rounded-lg shadow overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200">
        <h2 className="text-lg font-semibold text-gray-800">Cycle Time History (Last 90 Days)</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Week Starting</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Avg Days</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">PO Count</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {history.map((row) => (
              <tr key={row.weekStart} className="hover:bg-gray-50">
                <td className="px-6 py-3 font-mono text-gray-700">{row.weekStart}</td>
                <td className="px-6 py-3 text-right">
                  <span className={`inline-block px-2 py-0.5 rounded font-semibold text-xs ${avgDaysColor(row.averageDays)}`}>
                    {row.averageDays.toFixed(1)}d
                  </span>
                </td>
                <td className="px-6 py-3 text-right text-gray-700">{row.poCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
