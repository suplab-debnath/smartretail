import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
} from 'recharts'
import type { CycleTimeDataPoint } from '../types'

interface Props {
  history: CycleTimeDataPoint[]
  overallAverage: number
}

function formatWeek(dateStr: string): string {
  return dateStr.slice(5) // MM-DD
}

export function CycleTimeChart({ history, overallAverage }: Props) {
  const chartData = [...history].reverse().map(p => ({
    week: formatWeek(p.weekStart),
    avgDays: Number(p.averageDays.toFixed(1)),
    poCount: p.poCount,
  }))

  return (
    <div className="bg-white rounded-lg shadow p-6">
      <h2 className="text-lg font-semibold text-gray-800 mb-4">
        Replenishment Cycle Time — Weekly Average (Last 90 Days)
      </h2>
      <ResponsiveContainer width="100%" height={240}>
        <LineChart data={chartData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey="week" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis domain={[0, 'auto']} tick={{ fontSize: 11 }} unit="d" />
          <Tooltip
            formatter={(value: number, name: string) =>
              name === 'avgDays' ? [`${value}d`, 'Avg cycle time'] : [value, 'PO count']
            }
            labelFormatter={(label) => `Week of: ${label}`}
          />
          <ReferenceLine
            y={overallAverage}
            stroke="#9ca3af"
            strokeDasharray="4 4"
            label={{ value: `Avg ${overallAverage}d`, position: 'insideTopRight', fontSize: 11, fill: '#6b7280' }}
          />
          <Line
            type="monotone"
            dataKey="avgDays"
            stroke="#3b82f6"
            strokeWidth={2}
            dot={{ r: 3 }}
            activeDot={{ r: 5 }}
            name="avgDays"
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
