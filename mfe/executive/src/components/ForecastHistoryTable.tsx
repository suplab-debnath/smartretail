import type { MapeDataPoint } from '../types'

interface Props {
  history: MapeDataPoint[]
}

const MAPE_THRESHOLD = 0.15

export function ForecastHistoryTable({ history }: Props) {
  const rows = history.slice(0, 10)

  return (
    <div className="bg-white rounded-lg shadow overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200">
        <h2 className="text-lg font-semibold text-gray-800">Forecast Accuracy History</h2>
        <p className="text-sm text-gray-500 mt-1">Last 10 completed forecast runs</p>
      </div>
      <table className="min-w-full divide-y divide-gray-200">
        <thead className="bg-gray-50">
          <tr>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Date</th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">MAPE</th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Accuracy</th>
            <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
          </tr>
        </thead>
        <tbody className="bg-white divide-y divide-gray-200">
          {rows.map((point) => {
            const mape = Number(point.mape)
            const accuracy = ((1 - mape) * 100).toFixed(1)
            const withinThreshold = mape < MAPE_THRESHOLD
            return (
              <tr key={point.runDate}>
                <td className="px-6 py-3 whitespace-nowrap text-sm text-gray-900">{point.runDate}</td>
                <td className="px-6 py-3 whitespace-nowrap text-sm text-gray-700">
                  {(mape * 100).toFixed(2)}%
                </td>
                <td className="px-6 py-3 whitespace-nowrap text-sm text-gray-700">{accuracy}%</td>
                <td className="px-6 py-3 whitespace-nowrap">
                  <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                    withinThreshold
                      ? 'bg-green-100 text-green-800'
                      : 'bg-red-100 text-red-800'
                  }`}>
                    {withinThreshold ? 'Within threshold' : 'Threshold breached'}
                  </span>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
