import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ReferenceLine, ResponsiveContainer, Legend,
} from 'recharts'
import type { MapeDataPoint } from '../types'

interface Props {
  history: MapeDataPoint[]
}

const MAPE_THRESHOLD = 0.15

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function formatDate(dateStr: string) {
  return dateStr.slice(5) // MM-DD
}

function tooltipFormatter(value: number, name: string) {
  if (name === 'mape') {
    const accuracy = ((1 - value) * 100).toFixed(1)
    const status = value < MAPE_THRESHOLD ? 'Within threshold' : 'Threshold breached'
    return [`${formatPercent(value)} MAPE · ${accuracy}% accuracy · ${status}`, 'MAPE']
  }
  return [value, name]
}

export function MapeTrendChart({ history }: Props) {
  const chartData = [...history].reverse().map(p => ({
    runDate: formatDate(p.runDate),
    mape: Number(p.mape),
  }))

  return (
    <div className="bg-white rounded-lg shadow p-6">
      <h2 className="text-lg font-semibold text-gray-800 mb-4">MAPE Trend — Last 30 Forecast Runs</h2>
      <ResponsiveContainer width="100%" height={280}>
        <LineChart data={chartData} margin={{ top: 8, right: 24, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis
            dataKey="runDate"
            tick={{ fontSize: 11 }}
            interval={4}
          />
          <YAxis
            domain={[0, 0.20]}
            tickFormatter={formatPercent}
            tick={{ fontSize: 11 }}
            width={52}
          />
          <Tooltip formatter={tooltipFormatter} labelFormatter={label => `Date: ${label}`} />
          <Legend />
          <ReferenceLine
            y={MAPE_THRESHOLD}
            stroke="#ef4444"
            strokeDasharray="4 4"
            label={{ value: 'Threshold (15%)', position: 'right', fontSize: 11, fill: '#ef4444' }}
          />
          <Line
            type="monotone"
            dataKey="mape"
            stroke="#3b82f6"
            strokeWidth={2}
            dot={{ r: 2 }}
            activeDot={{ r: 5 }}
            name="mape"
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
