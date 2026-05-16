import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { SupplierPerformanceEntry } from '../types'

interface Props {
  suppliers: SupplierPerformanceEntry[]
}

function shortName(name: string): string {
  return name.split(' ')[0] // First word only e.g. "Acme"
}

export function DeliveryHistogram({ suppliers }: Props) {
  const chartData = suppliers.map(s => ({
    supplier: shortName(s.supplierName),
    Early: s.earlyCount,
    'On-Time': s.onTimeCount,
    Late: s.lateCount,
  }))

  return (
    <div className="bg-white rounded-lg shadow p-6">
      <h2 className="text-lg font-semibold text-gray-800 mb-1">
        Delivery Performance Distribution
      </h2>
      <p className="text-sm text-gray-500 mb-4">Early / On-Time / Late shipments per supplier</p>
      <ResponsiveContainer width="100%" height={260}>
        <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey="supplier" tick={{ fontSize: 12 }} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend />
          <Bar dataKey="Early" fill="#3b82f6" radius={[3, 3, 0, 0]} />
          <Bar dataKey="On-Time" fill="#22c55e" radius={[3, 3, 0, 0]} />
          <Bar dataKey="Late" fill="#ef4444" radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
