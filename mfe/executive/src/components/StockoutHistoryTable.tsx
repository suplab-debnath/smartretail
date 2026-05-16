import type { StockoutAlertDataPoint } from '../types'

interface Props {
  history: StockoutAlertDataPoint[]
}

export function StockoutHistoryTable({ history }: Props) {
  return (
    <div className="bg-white rounded-lg shadow overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200">
        <h2 className="text-lg font-semibold text-gray-800">Stockout Alert History (Last 30 Days)</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Date</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">CRITICAL Alerts</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {history.map((row) => (
              <tr key={row.alertDate} className="hover:bg-gray-50">
                <td className="px-6 py-3 font-mono text-gray-700">{row.alertDate}</td>
                <td className="px-6 py-3 text-right font-semibold text-gray-800">{row.criticalCount}</td>
                <td className="px-6 py-3">
                  {row.criticalCount > 0 ? (
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800">
                      CRITICAL
                    </span>
                  ) : (
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">
                      CLEAR
                    </span>
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
