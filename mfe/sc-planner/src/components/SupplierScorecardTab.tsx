import { useSupplierPerformance } from '../hooks/useSupplierPerformance'

function otdColor(rate: number): string {
  if (rate >= 90) return 'text-green-600 font-semibold'
  if (rate >= 75) return 'text-amber-600 font-semibold'
  return 'text-red-600 font-semibold'
}

function leadTimeVarianceColor(days: number): string {
  if (days > 0) return 'text-red-600'
  if (days < 0) return 'text-green-600'
  return 'text-gray-600'
}

function formatVariance(days: number): string {
  if (days > 0) return `+${days.toFixed(1)}d`
  if (days < 0) return `${days.toFixed(1)}d`
  return '0d'
}

export function SupplierScorecardTab() {
  const { data, loading, error } = useSupplierPerformance()

  if (loading) return <div className="p-8 text-gray-500">Loading supplier scorecard…</div>
  if (error) return <div className="p-8 text-red-500">Error: {error}</div>

  const suppliers = data?.suppliers ?? []

  return (
    <div>
      {suppliers.length === 0 ? (
        <div className="py-12 text-center text-gray-400">No supplier data available</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {['Supplier', 'OTD Rate', 'PO SLA Compliance', 'Open Exceptions', 'Avg Lead Time Variance', 'Total POs', 'Total Value'].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {suppliers.map(s => (
                <tr key={s.supplierId} className="hover:bg-gray-50">
                  <td className="px-4 py-3">
                    <div className="font-medium text-gray-800">{s.supplierName}</div>
                    <div className="text-xs text-gray-400">{s.supplierId}</div>
                  </td>
                  <td className={`px-4 py-3 ${otdColor(s.onTimeDeliveryRate)}`}>
                    {s.onTimeDeliveryRate.toFixed(1)}%
                  </td>
                  <td className="px-4 py-3">
                    <span className={`${s.poAcknowledgementSlaCompliance >= 90 ? 'text-green-600' : s.poAcknowledgementSlaCompliance >= 75 ? 'text-amber-600' : 'text-red-600'} font-semibold`}>
                      {s.poAcknowledgementSlaCompliance.toFixed(1)}%
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`font-semibold ${s.openExceptions > 0 ? 'text-red-600' : 'text-gray-600'}`}>
                      {s.openExceptions}
                    </span>
                  </td>
                  <td className={`px-4 py-3 ${leadTimeVarianceColor(s.avgLeadTimeVarianceDays)}`}>
                    {formatVariance(s.avgLeadTimeVarianceDays)}
                  </td>
                  <td className="px-4 py-3 text-right text-gray-700">{s.totalPoCount.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right text-gray-700">
                    £{s.totalPoValue.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 0 })}
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
