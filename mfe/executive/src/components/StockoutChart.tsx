import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'
import type { StockoutAlertDataPoint } from '../types'

interface Props {
  history: StockoutAlertDataPoint[]
}

function formatDate(dateStr: string): string {
  return dateStr.slice(5) // MM-DD
}

export function StockoutChart({ history }: Props) {
  const chartData = [...history].reverse().map(p => ({
    date: formatDate(p.alertDate),
    count: p.criticalCount,
  }))

  return (
    <div className="bg-white rounded-lg shadow p-6">
      <h2 className="text-lg font-semibold text-gray-800 mb-4">
        Stockout Frequency — Daily CRITICAL Alerts (Last 30 Days)
      </h2>
      <ResponsiveContainer width="100%" height={240}>
        <BarChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey="date" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
          <Tooltip
            formatter={(value: number) => [value, 'CRITICAL alerts']}
            labelFormatter={(label) => `Date: ${label}`}
          />
          <Bar dataKey="count" fill="#ef4444" radius={[3, 3, 0, 0]} name="CRITICAL alerts" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
